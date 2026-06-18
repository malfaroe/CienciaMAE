package com.mae.ciencia

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Message(val role: String, val content: String)

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun addMessage(message: Message) {
        _messages.value = _messages.value + message
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setError(error: String?) {
        _error.value = error
    }

    fun clearError() {
        _error.value = null
    }

    fun buildApiMessages(): List<Map<String, String>> =
        _messages.value.map { mapOf("role" to it.role, "content" to it.content) }
}
