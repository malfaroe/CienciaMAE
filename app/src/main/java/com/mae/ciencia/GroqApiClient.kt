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
        val SYSTEM_PROMPT = """You are a patient physics, mathematics, and statistics tutor. Follow these rules exactly:
1. Explain step by step. Adapt depth and vocabulary to the complexity of the question.
2. Respond in the same language as the user's question (Spanish or English).
3. Use ${'$'}...$ for inline math and ${'$'}${'$'}...${'$'}${'$'} for block equations. Always use LaTeX for any formula, symbol, or expression — never plain ASCII math.
4. For concept maps, process flows, or multi-step diagrams use a fenced mermaid block:
   ```mermaid
   <diagram>
   ```
5. For simple geometric diagrams (triangles, vectors, circles, coordinate axes) emit inline SVG using only: <line>, <circle>, <rect>, <polygon>, <text>. Basic shapes and labels only.
6. For graphing mathematical functions emit a fenced functionplot block with this exact JSON schema (no deviations):
   ```functionplot
   {"title":"optional label","xAxis":{"domain":[-5,5]},"yAxis":{"domain":[-5,5]},"data":[{"fn":"x^2","color":"#FFAB40"}]}
   ```
7. Do not use * or _ for emphasis. Plain text only.
8. For statistics: use LaTeX for all formulas. Use function-plot for continuous distribution curves (normal, exponential, chi-squared, t-distribution). For discrete distributions (binomial, Poisson), describe the shape in text and give the PMF formula only.
9. No filler, no apologies. Start the explanation immediately."""

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
