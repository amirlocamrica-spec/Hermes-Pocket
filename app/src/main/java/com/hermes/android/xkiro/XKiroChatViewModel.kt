package com.hermes.android.xkiro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class XKiroMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

data class XKiroUiState(
    val messages: List<XKiroMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    val apiKey: String? = null,
    val model: String = XKiroClient.DEFAULT_MODEL,
)

@HiltViewModel
class XKiroChatViewModel @Inject constructor(
    private val apiStore: XKiroApiStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(XKiroUiState())
    val uiState: StateFlow<XKiroUiState> = _uiState.asStateFlow()

    private var client: XKiroClient? = null

    init {
        val key = apiStore.apiKey.value
        if (key != null) {
            client = XKiroClient(apiKey = key)
            _uiState.update { it.copy(isConnected = true, apiKey = key) }
        }
    }

    fun setApiKey(key: String) {
        apiStore.saveKey(key)
        client = XKiroClient(apiKey = key.trim())
        _uiState.update { it.copy(isConnected = true, apiKey = key.trim(), error = null) }
    }

    fun clearApiKey() {
        apiStore.clearKey()
        client = null
        _uiState.update { XKiroUiState() }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty() || _uiState.value.isLoading) return

        val currentClient = client ?: run {
            _uiState.update { it.copy(error = "Set your API key first") }
            return
        }

        val userMsg = XKiroMessage(role = "user", content = text)
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                inputText = "",
                isLoading = true,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val history = _uiState.value.messages.map { ChatMessage(it.role, it.content) }
                val reply = currentClient.sendMessage(history, _uiState.value.model)
                val assistantMsg = XKiroMessage(role = "assistant", content = reply)
                _uiState.update {
                    it.copy(
                        messages = it.messages + assistantMsg,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error",
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
