package com.mae.ciencia

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CienciaApp() }
    }
}

@Suppress("DEPRECATION")
private fun getApiKey(context: Context): String? = try {
    val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    EncryptedSharedPreferences.create(
        "ciencia_prefs", alias, context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    ).getString("groq_api_key", null)
} catch (e: Exception) { null }

@Suppress("DEPRECATION")
private fun saveApiKey(context: Context, key: String) {
    try {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "ciencia_prefs", alias, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).edit().putString("groq_api_key", key).apply()
    } catch (e: Exception) { }
}

fun htmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#x27;")

fun processMarkdown(text: String): String {
    var result = text

    // Extract $$ and mermaid blocks before line-break processing to preserve their newlines
    val mathBlocks = mutableListOf<String>()
    result = result.replace(Regex("\\$\\$[\\s\\S]*?\\$\\$")) { m ->
        mathBlocks.add(m.value)
        "MATHBLOCK${mathBlocks.size - 1}END"
    }

    val mermaidBlocks = mutableListOf<String>()
    result = result.replace(Regex("```mermaid\\n([\\s\\S]*?)```\\n?")) { m ->
        mermaidBlocks.add("<div class=\"mermaid\">${m.groupValues[1].trim()}</div>")
        "MERMAIDBLOCK${mermaidBlocks.size - 1}END"
    }

    result = result.replace(Regex("```functionplot\\n([\\s\\S]*?)```\\n?")) { m ->
        "<div class=\"functionplot-data\">${m.groupValues[1].trim()}</div>"
    }

    val svgBlocks = mutableListOf<String>()
    result = result.replace(Regex("<svg[^>]*>[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE)) { m ->
        svgBlocks.add(m.value)
        "SVGBLOCK${svgBlocks.size - 1}END"
    }

    result = result.replace(Regex("(?m)^[*-] (.+)"), "&#8226; $1")
    result = result.replace(Regex("\\*\\*([^*\n]+)\\*\\*"), "<strong>$1</strong>")
    result = "<p>" + result.replace("\n\n", "</p><p>") + "</p>"
    result = result.replace("\n", "<br>")

    mathBlocks.forEachIndexed { idx, block ->
        result = result.replace("MATHBLOCK${idx}END", block)
    }
    mermaidBlocks.forEachIndexed { idx, block ->
        result = result.replace("MERMAIDBLOCK${idx}END", block)
    }
    svgBlocks.forEachIndexed { idx, block ->
        result = result.replace("SVGBLOCK${idx}END", block)
    }

    return result
}

private fun injectMessage(webView: WebView?, message: Message) {
    webView ?: return
    val html = if (message.role == "user") htmlEscape(message.content) else processMarkdown(message.content)
    val escaped = JSONObject.quote(html)
    webView.evaluateJavascript("appendMessage('${message.role}', $escaped)", null)
}

@Composable
fun CienciaApp() {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel()
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(getApiKey(context)) }
    var showKeyDialog by remember { mutableStateOf(apiKey == null) }
    var inputText by remember { mutableStateOf("") }
    val isLoading by vm.isLoading.collectAsState()
    val error by vm.error.collectAsState()
    val messages by vm.messages.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var lastInjectedIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(messages, pageLoaded) {
        if (!pageLoaded) return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        for (i in (lastInjectedIndex + 1) until messages.size) {
            injectMessage(wv, messages[i])
        }
        lastInjectedIndex = messages.size - 1
    }

    val mono = FontFamily.Monospace
    val focusManager = LocalFocusManager.current

    if (showKeyDialog) {
        ApiKeyDialog(mono) { key ->
            saveApiKey(context, key)
            apiKey = key
            showKeyDialog = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ComposeColor.Black) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "CIENCIAMAE",
                    modifier = Modifier.weight(1f),
                    style = TextStyle(
                        color = ComposeColor(0xFFFFAB40),
                        fontFamily = mono,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                )
                if (messages.isNotEmpty()) {
                    TextButton(
                        onClick = { webViewRef?.evaluateJavascript("scrollToCurrentResponse()", null) },
                        modifier = Modifier.border(1.dp, ComposeColor(0xFFFFAB40), RoundedCornerShape(4.dp))
                    ) {
                        Text("UP", style = TextStyle(color = ComposeColor(0xFFFFAB40), fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    }
                }
                TextButton(onClick = { showKeyDialog = true }) {
                    Text("key", style = TextStyle(color = ComposeColor(0xFFFFAB40), fontFamily = mono, fontSize = 11.sp))
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF1A1A1A), thickness = 0.5.dp)

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setBackgroundColor(Color.BLACK)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoaded = true
                            }
                        }
                        loadUrl("file:///android_asset/template.html")
                        webViewRef = this
                    }
                }
            )

            error?.let { err ->
                Text(
                    err,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = TextStyle(color = ComposeColor(0xFFFF5555), fontFamily = mono, fontSize = 12.sp)
                )
            }

            HorizontalDivider(color = ComposeColor(0xFF1A1A1A), thickness = 0.5.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .border(2.dp, ComposeColor(0xFFFFAB40), RoundedCornerShape(4.dp)),
                    placeholder = {
                        Text(
                            "Escribe tu pregunta...",
                            style = TextStyle(color = ComposeColor(0xFF5A3A00), fontFamily = mono, fontSize = 14.sp)
                        )
                    },
                    textStyle = TextStyle(color = ComposeColor(0xFFFFAB40), fontFamily = mono, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ComposeColor.Transparent,
                        unfocusedBorderColor = ComposeColor.Transparent,
                        focusedContainerColor = ComposeColor.Transparent,
                        unfocusedContainerColor = ComposeColor.Transparent,
                        cursorColor = ComposeColor(0xFFFFAB40)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() && !isLoading) {
                            sendMessage(context, vm, scope, apiKey, inputText) { showKeyDialog = true }
                            inputText = ""
                            focusManager.clearFocus()
                        }
                    }),
                    maxLines = 4
                )
                TextButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            sendMessage(context, vm, scope, apiKey, inputText) { showKeyDialog = true }
                            inputText = ""
                            focusManager.clearFocus()
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text(
                        if (isLoading) "..." else ">",
                        style = TextStyle(
                            color = if (isLoading) ComposeColor(0xFF1A3A55) else ComposeColor(0xFF42A5F5),
                            fontFamily = mono,
                            fontSize = 40.sp
                        )
                    )
                }
            }
        }
    }
}

private fun sendMessage(
    context: Context,
    vm: ChatViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    apiKey: String?,
    text: String,
    onInvalidKey: () -> Unit
) {
    val key = apiKey ?: run { onInvalidKey(); return }
    vm.clearError()
    vm.addMessage(Message("user", text))
    vm.setLoading(true)

    scope.launch {
        var lastResult: Result<String> = Result.failure(Exception("RATE_LIMIT"))
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                vm.setError("Límite de velocidad — reintentando en 5s...")
                delay(5000L)
                vm.clearError()
            }
            lastResult = withContext(Dispatchers.IO) {
                GroqApiClient(key).chat(vm.buildApiMessages())
            }
            if (lastResult.isSuccess || lastResult.exceptionOrNull()?.message != "RATE_LIMIT") break
        }
        vm.setLoading(false)
        lastResult.fold(
            onSuccess = { content -> vm.addMessage(Message("assistant", content)) },
            onFailure = { e ->
                when (e.message) {
                    "INVALID_KEY" -> {
                        vm.setError("API key invalida — toca 'key' para actualizar")
                        onInvalidKey()
                    }
                    "RATE_LIMIT" -> vm.setError("Límite de velocidad alcanzado. Intenta de nuevo.")
                    else -> vm.setError("Sin conexion o error de red.")
                }
            }
        )
    }
}

@Composable
private fun ApiKeyDialog(mono: FontFamily, onConfirm: (String) -> Unit) {
    var keyInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {},
        containerColor = ComposeColor(0xFF0D0D0D),
        title = {
            Text("Groq API Key", style = TextStyle(color = ComposeColor.White, fontFamily = mono, fontSize = 14.sp))
        },
        text = {
            Column {
                Text(
                    "Ingresa tu API key de groq.com (cuenta gratuita)",
                    style = TextStyle(color = ComposeColor(0xFF888888), fontFamily = mono, fontSize = 12.sp),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    textStyle = TextStyle(color = ComposeColor.White, fontFamily = mono, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ComposeColor(0xFF444444),
                        unfocusedBorderColor = ComposeColor(0xFF333333),
                        focusedContainerColor = ComposeColor.Transparent,
                        unfocusedContainerColor = ComposeColor.Transparent,
                        cursorColor = ComposeColor.White
                    ),
                    placeholder = {
                        Text("gsk_...", style = TextStyle(color = ComposeColor(0xFF333333), fontFamily = mono, fontSize = 12.sp))
                    },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (keyInput.isNotBlank()) onConfirm(keyInput.trim()) },
                enabled = keyInput.isNotBlank()
            ) {
                Text("Guardar", style = TextStyle(color = ComposeColor.White, fontFamily = mono))
            }
        }
    )
}
