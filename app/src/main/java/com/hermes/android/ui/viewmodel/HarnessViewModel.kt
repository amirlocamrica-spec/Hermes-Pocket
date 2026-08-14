package com.hermes.android.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.aether.AetherSettings
import com.hermes.android.harness.HarnessAgent
import com.hermes.android.harness.HarnessConfig
import com.hermes.android.harness.HarnessMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HarnessUiMessage {
    data class Chat(val role: String, val content: String) : HarnessUiMessage()
    data class ToolEvent(val toolName: String, val detail: String) : HarnessUiMessage()
}

data class HarnessUiState(
    val config: HarnessConfig = HarnessConfig("", "", ""),
    val isConfigured: Boolean = false,
    val messages: List<HarnessUiMessage> = emptyList(),
    val draft: String = "",
    val isRunning: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel for the Harness on-device agent. Reuses the Aether provider
 * config as its default endpoint (the user can override per-screen).
 */
@HiltViewModel
class HarnessViewModel @Inject constructor(
    private val agent: HarnessAgent,
    private val aetherSettings: AetherSettings,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("harness_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<HarnessUiState> = _uiState.asStateFlow()

    /** In-memory conversation history sent back to the model each turn. */
    private val history = mutableListOf<HarnessMessage>()

    private fun loadState(): HarnessUiState {
        // Fall back to the Aether config so users don't re-enter credentials.
        val aether = aetherSettings.config.value
        val config = HarnessConfig(
            baseUrl = prefs.getString("base_url", null) ?: aether.baseUrl,
            apiKey = prefs.getString("api_key", null) ?: aether.apiKey,
            model = prefs.getString("model", null) ?: aether.model,
            systemPrompt = prefs.getString("system_prompt", null) ?: HarnessConfig.DEFAULT_SYSTEM_PROMPT,
        )
        return HarnessUiState(config = config, isConfigured = config.isComplete)
    }

    fun setDraft(text: String) {
        _uiState.value = _uiState.value.copy(draft = text)
    }

    fun saveConfig(baseUrl: String, apiKey: String, model: String, systemPrompt: String) {
        val config = HarnessConfig(
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            model = model.trim(),
            systemPrompt = systemPrompt.trim().ifBlank { HarnessConfig.DEFAULT_SYSTEM_PROMPT },
        )
        prefs.edit()
            .putString("base_url", config.baseUrl)
            .putString("api_key", config.apiKey)
            .putString("model", config.model)
            .putString("system_prompt", config.systemPrompt)
            .apply()
        _uiState.value = _uiState.value.copy(config = config, isConfigured = config.isComplete)
    }

    fun send() {
        val state = _uiState.value
        val text = state.draft.trim()
        if (text.isEmpty() || state.isRunning || !state.isConfigured) return

        _uiState.value = state.copy(
            draft = "",
            isRunning = true,
            error = null,
            messages = state.messages + HarnessUiMessage.Chat("user", text),
        )

        val snapshot = history.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val config = _uiState.value.config
            val answer = agent.run(
                config = config,
                userText = text,
                history = snapshot,
                onEvent = { event ->
                    when (event) {
                        is HarnessAgent.Event.ToolRunning -> appendUi(
                            HarnessUiMessage.ToolEvent(event.name, event.args),
                        )
                        is HarnessAgent.Event.ToolResult -> appendUi(
                            HarnessUiMessage.ToolEvent(event.name, "→ ${event.output}"),
                        )
                        is HarnessAgent.Event.Final -> Unit
                        is HarnessAgent.Event.Error ->
                            _uiState.value = _uiState.value.copy(error = event.message)
                    }
                },
            )
            history.add(HarnessMessage("user", text))
            history.add(HarnessMessage("assistant", answer))
            appendUi(HarnessUiMessage.Chat("assistant", answer))
            _uiState.value = _uiState.value.copy(isRunning = false)
        }
    }

    private fun appendUi(msg: HarnessUiMessage) {
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + msg)
    }
}