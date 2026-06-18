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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

private fun getApiKey(context: Context): String? = try {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    EncryptedSharedPreferences.create(
        context, "ciencia_prefs", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    ).getString("groq_api_key", null)
} catch (e: Exception) { null }

private fun saveApiKey(context: Context, key: String) {
    try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "ciencia_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).edit().putString("groq_api_key", key).apply()
    } catch (e: Exception) { }
}

fun processMarkdown(text: String): String {
    var result = text

    result = result.replace(Regex("```mermaid\\n([\\s\\S]*?)```\\n?")) { m ->
        "<div class=\"mermaid\">${m.groupValues[1].trim()}</div>"
    }

    result = result.replace(Regex("```functionplot\\n([\\s\\S]*?)```\\n?")) { m ->
        "<div class=\"functionplot-data\">${m.groupValues[1].trim()}</div>"
    }

    result = result.replace(Regex("\\*\\*([^*\n]+)\\*\\*"), "<strong>$1</strong>")

    result = "<p>" + result.replace("\n\n", "</p><p>") + "</p>"
    result = result.replace("\n", "<br>")

    return result
}

private fun injectMessage(webView: WebView?, message: Message) {
    webView ?: return
    val html = if (message.role == "user") message.content else processMarkdown(message.content)
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
                    style = TextStyle(color = ComposeColor(0xFF666666), fontFamily = mono, fontSize = 12.sp, letterSpacing = 2.sp)
                )
                TextButton(onClick = { showKeyDialog = true }) {
                    Text("key", style = TextStyle(color = ComposeColor(0xFF444444), fontFamily = mono, fontSize = 11.sp))
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF1A1A1A), thickness = 0.5.dp)

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Escribe tu pregunta...",
                            style = TextStyle(color = ComposeColor(0xFF333333), fontFamily = mono, fontSize = 14.sp)
                        )
                    },
                    textStyle = TextStyle(color = ComposeColor.White, fontFamily = mono, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ComposeColor(0xFF444444),
                        unfocusedBorderColor = ComposeColor(0xFF2A2A2A),
                        focusedContainerColor = ComposeColor.Transparent,
                        unfocusedContainerColor = ComposeColor.Transparent,
                        cursorColor = ComposeColor.White
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() && !isLoading) {
                            sendMessage(context, vm, scope, apiKey, inputText) { showKeyDialog = true }
                            inputText = ""
                        }
                    }),
                    maxLines = 4
                )
                TextButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            sendMessage(context, vm, scope, apiKey, inputText) { showKeyDialog = true }
                            inputText = ""
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text(
                        if (isLoading) "..." else ">",
                        style = TextStyle(
                            color = if (isLoading) ComposeColor(0xFF444444) else ComposeColor(0xFF888888),
                            fontFamily = mono,
                            fontSize = 20.sp
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
        val result = withContext(Dispatchers.IO) {
            GroqApiClient(key).chat(vm.buildApiMessages())
        }
        vm.setLoading(false)
        result.fold(
            onSuccess = { content -> vm.addMessage(Message("assistant", content)) },
            onFailure = { e ->
                when (e.message) {
                    "INVALID_KEY" -> {
                        vm.setError("API key inválida — toca 'key' para actualizar")
                        onInvalidKey()
                    }
                    "RATE_LIMIT" -> vm.setError("Límite de velocidad alcanzado. Intenta de nuevo.")
                    else -> vm.setError("Sin conexión o error de red.")
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
