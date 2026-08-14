package com.hermes.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.aether.AETHER_PROVIDER_PRESETS
import com.hermes.android.aether.AetherClient
import com.hermes.android.aether.AetherConfig
import com.hermes.android.aether.AetherConversation
import com.hermes.android.aether.AetherConversationSummary
import com.hermes.android.aether.AetherMessage
import com.hermes.android.aether.AetherRepository
import com.hermes.android.aether.AetherSettings
import com.hermes.android.aether.ProviderPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import java.util.UUID
import javax.inject.Inject

data class AetherUiState(
    val config: AetherConfig = AetherConfig(),
    val isConfigured: Boolean = false,
    val conversations: List<AetherConversationSummary> = emptyList(),
    val currentId: String? = null,
    val messages: List<AetherMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val draft: String = "",
    val error: String? = null,
    val modelSuggestions: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
)

sealed interface AetherEffect {
    data class ShowMessage(val text: String) : AetherEffect
}

/**
 * ViewModel for the Aether runtime — direct LLM chat with no Hermes
 * server. Owns the provider config, the conversation list, and the
 * streaming generation loop.
 */
@HiltViewModel
class AetherViewModel @Inject constructor(
    private val client: AetherClient,
    private val settings: AetherSettings,
    private val repository: AetherRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AetherUiState())
    val uiState: StateFlow<AetherUiState> = _uiState.asStateFlow()

    private val _effects = MutableStateFlow<AetherEffect?>(null)
    val effects: StateFlow<AetherEffect?> = _effects.asStateFlow()

    private var activeCall: Call? = null
    private var streamingMessageId: String? = null
    private var saveJob: Job? = null

    val providerPresets: List<ProviderPreset> = AETHER_PROVIDER_PRESETS

    init {
        viewModelScope.launch {
            settings.config.collect { cfg ->
                _uiState.value = _uiState.value.copy(config = cfg, isConfigured = cfg.isComplete)
            }
        }
        refreshConversations()
    }

    fun consumeEffect() { _effects.value = null }

    // ── Config ────────────────────────────────────────────────────────────

    fun applyPreset(preset: ProviderPreset) { settings.applyPreset(preset) }

    fun saveConfig(config: AetherConfig) { settings.save(config) }

    fun discoverModels() {
        val cfg = settings.config.value
        if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Set base URL and API key first")
            return
        }
        _uiState.value = _uiState.value.copy(modelsLoading = true)
        viewModelScope.launch {
            client.listModels(cfg)
                .onSuccess { models ->
                    _uiState.value = _uiState.value.copy(modelSuggestions = models, modelsLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        modelSuggestions = emptyList(),
                        modelsLoading = false,
                        error = e.message ?: "Model discovery failed",
                    )
                }
        }
    }

    // ── Conversations ─────────────────────────────────────────────────────

    fun refreshConversations() {
        viewModelScope.launch {
            val list = repository.listConversations()
            _uiState.value = _uiState.value.copy(conversations = list)
        }
    }

    fun openConversation(id: String) {
        viewModelScope.launch {
            val conv = repository.load(id) ?: return@launch
            _uiState.value = _uiState.value.copy(
                currentId = id,
                messages = conv.messages,
                error = null,
            )
        }
    }

    fun newConversation() {
        stopGeneration()
        _uiState.value = _uiState.value.copy(currentId = null, messages = emptyList(), error = null)
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (_uiState.value.currentId == id) {
                _uiState.value = _uiState.value.copy(currentId = null, messages = emptyList())
            }
            refreshConversations()
        }
    }

    fun setDraft(text: String) {
        _uiState.value = _uiState.value.copy(draft = text)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ── Generation ────────────────────────────────────────────────────────

    fun send() {
        val state = _uiState.value
        val text = state.draft.trim()
        if (text.isEmpty() || state.isGenerating) return
        if (!state.isConfigured) {
            _uiState.value = state.copy(error = "Configure a provider first")
            return
        }

        val cfg = settings.config.value
        val userMsg = AetherMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = text,
            timestamp = System.currentTimeMillis(),
        )
        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = AetherMessage(
            id = assistantId,
            role = "assistant",
            content = "",
            timestamp = System.currentTimeMillis(),
        )

        val messages = state.messages + userMsg + assistantMsg
        _uiState.value = state.copy(
            messages = messages,
            draft = "",
            isGenerating = true,
            error = null,
        )
        streamingMessageId = assistantId

        // Full history sent to the provider: optional system prompt + chat.
        val history = buildList {
            if (cfg.systemPrompt.isNotBlank()) {
                add(AetherMessage("system", "system", cfg.systemPrompt, 0))
            }
            addAll(messages.filter { it.content.isNotBlank() || it.id == assistantId })
        }

        activeCall = client.streamChat(
            config = cfg,
            history = history.filterNot { it.id == assistantId && it.content.isEmpty() },
            onDelta = { delta -> appendDelta(assistantId, delta) },
            onComplete = { result -> onGenerationDone(assistantId, result) },
        )
    }

    fun stopGeneration() {
        activeCall?.cancel()
        activeCall = null
        val id = streamingMessageId
        if (id != null && _uiState.value.isGenerating) {
            _uiState.value = _uiState.value.copy(isGenerating = false)
            persistCurrent()
        }
        streamingMessageId = null
    }

    private fun appendDelta(messageId: String, delta: String) {
        _uiState.value = _uiState.value.let { s ->
            s.copy(messages = s.messages.map {
                if (it.id == messageId) it.copy(content = it.content + delta) else it
            })
        }
    }

    private fun onGenerationDone(messageId: String, result: AetherClient.Result) {
        activeCall = null
        streamingMessageId = null
        when (result) {
            is AetherClient.Result.Success -> {
                _uiState.value = _uiState.value.copy(isGenerating = false, error = null)
                persistCurrent()
            }
            is AetherClient.Result.Failure -> {
                // Keep any partial text; surface the error.
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    error = result.message,
                    messages = _uiState.value.messages.filter {
                        it.id != messageId || it.content.isNotBlank()
                    },
                )
                persistCurrent()
            }
        }
    }

    private fun persistCurrent() {
        val state = _uiState.value
        val msgs = state.messages
        if (msgs.isEmpty()) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            val existing = state.currentId?.let { repository.load(it) }
            val conv = existing?.copy(
                messages = msgs,
                updatedAt = System.currentTimeMillis(),
            ) ?: repository.newConversation().copy(messages = msgs)
            val titled = if (conv.title == "New chat") conv.copy(title = repository.autoTitle(conv)) else conv
            repository.save(titled)
            _uiState.value = _uiState.value.copy(currentId = titled.id)
            refreshConversations()
        }
    }

    // ── Export ────────────────────────────────────────────────────────────

    fun exportMarkdown(onSaved: (String) -> Unit) {
        val state = _uiState.value
        val id = state.currentId ?: return
        viewModelScope.launch {
            val conv = repository.load(id) ?: return@launch
            // App-specific external dir: no runtime permission needed.
            val dir = java.io.File(
                appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS),
                "Aether",
            ).apply { mkdirs() }
            val safeTitle = conv.title.take(30).replace(Regex("[^\\p{L}\\p{N} _-]"), "").ifBlank { "chat" }
            val file = java.io.File(dir, "$safeTitle-${conv.id.take(8)}.md")
            file.writeText(repository.exportMarkdown(conv))
            onSaved(file.absolutePath)
        }
    }
}