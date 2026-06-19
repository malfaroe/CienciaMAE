package com.mae.ciencia

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GroqApiClient(private val apiKey: String) {

    companion object {
        val SYSTEM_PROMPT = """You are a concise physics, mathematics, and statistics tutor. Follow these rules exactly:
1. Be brief. Maximum 2-3 short paragraphs. Give the core idea directly. The user will ask for more if needed.
2. Respond in the same language as the user's question (Spanish or English).
3. Use ${'$'}...$ for inline math and ${'$'}${'$'}...${'$'}${'$'} for block equations. Always use LaTeX for any formula — never plain ASCII math.
4. For concept maps or flows use a fenced mermaid block. Use ONLY flowchart TD with simple arrow syntax. Exact format:
   ```mermaid
   flowchart TD
     A[Label] --> B[Label]
     B --> C[Label]
   ```
   Rules: node IDs are single letters or short words only, no spaces or special chars. Labels in square brackets. Arrows with -->. No subgraphs, no styling, no classDef.
5. For simple geometric diagrams (triangles, vectors, axes) emit inline SVG using only: <line>, <circle>, <rect>, <polygon>, <text>.
6. For graphing functions emit a fenced functionplot block with this exact JSON schema:
   ```functionplot
   {"title":"optional","xAxis":{"domain":[-5,5]},"yAxis":{"domain":[-5,5]},"data":[{"fn":"x^2","color":"#FFAB40"}]}
   ```
7. Plain text only — no * or _ for emphasis. Use - for bullet lists, never *.
8. For statistics: LaTeX for formulas, function-plot for continuous distributions, text+PMF for discrete.
9. No filler, no summaries, no apologies. Start immediately."""

        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.3-70b-versatile"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    fun chat(messages: List<Map<String, String>>): Result<String> {
        val allMessages = mutableListOf<Map<String, String>>()
        allMessages.add(mapOf("role" to "system", "content" to SYSTEM_PROMPT))
        allMessages.addAll(messages)

        val body = mapOf(
            "model" to MODEL,
            "messages" to allMessages,
            "temperature" to 0.3,
            "max_tokens" to 2048
        )

        val request = Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val bodyStr = response.body?.string()
                        ?: return Result.failure(Exception("Empty response"))
                    val json = gson.fromJson<Map<String, Any>>(
                        bodyStr,
                        object : TypeToken<Map<String, Any>>() {}.type
                    )
                    @Suppress("UNCHECKED_CAST")
                    val choices = json["choices"] as? List<Map<String, Any>>
                    @Suppress("UNCHECKED_CAST")
                    val message = choices?.firstOrNull()?.get("message") as? Map<String, String>
                    val content = message?.get("content") ?: "No response"
                    Result.success(content)
                }
                401 -> Result.failure(Exception("INVALID_KEY"))
                429 -> Result.failure(Exception("RATE_LIMIT"))
                else -> Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
