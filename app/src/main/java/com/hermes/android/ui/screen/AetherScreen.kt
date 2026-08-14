package com.hermes.android.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.android.aether.AETHER_PROVIDER_PRESETS
import com.hermes.android.aether.AetherConfig
import com.hermes.android.aether.AetherMessage
import com.hermes.android.aether.AetherProtocol
import com.hermes.android.ui.component.HermesMarkdown
import com.hermes.android.ui.design.HermesScaffold
import com.hermes.android.ui.i18n.t
import com.hermes.android.ui.viewmodel.AetherViewModel

/**
 * Aether — direct LLM chat without a Hermes server (Triad-inspired).
 * Two modes inside one screen: the conversation list/chat, and a
 * provider settings sheet toggled from the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AetherScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: AetherViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Not configured yet → land on settings immediately.
    LaunchedEffect(state.isConfigured) {
        if (!state.isConfigured) showSettings = true
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is com.hermes.android.ui.viewmodel.AetherEffect.ShowMessage ->
                    Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
                null -> Unit
            }
            viewModel.consumeEffect()
        }
    }

    HermesScaffold(
        title = t("Aether", "اتر"),
        subtitle = if (state.isConfigured) {
            "${state.config.model} @ ${providerName(state.config.providerId)}"
        } else {
            t("Direct LLM chat — no server needed", "چت مستقیم با مدل — بدون سرور")
        },
        onBack = onNavigateBack,
        actions = {
            if (!showSettings) {
                IconButton(onClick = { viewModel.newConversation() }) {
                    Icon(Icons.Default.Add, contentDescription = t("New chat", "چت جدید"))
                }
                val savedLabel = t("Saved: ", "ذخیره شد: ")
                IconButton(onClick = {
                    // t() is @Composable — resolve it above; this lambda is not.
                    viewModel.exportMarkdown { path ->
                        Toast.makeText(context, savedLabel + path, Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Default.Download, contentDescription = t("Export", "خروجی"))
                }
            }
            IconButton(onClick = { showSettings = !showSettings }) {
                Icon(
                    if (showSettings) Icons.Default.Close else Icons.Default.Settings,
                    contentDescription = t("Settings", "تنظیمات"),
                )
            }
        },
    ) { padding ->
        if (showSettings) {
            AetherSettingsPane(padding, viewModel, state.config) { showSettings = false }
        } else {
            AetherChatPane(padding, viewModel)
        }
    }
}

private fun providerName(id: String): String =
    AETHER_PROVIDER_PRESETS.firstOrNull { it.id == id }?.name ?: id

// ── Chat pane ─────────────────────────────────────────────────────────────

@Composable
private fun AetherChatPane(
    padding: PaddingValues,
    viewModel: AetherViewModel,
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        // Conversation strip (horizontal chips)
        if (state.conversations.isNotEmpty() && state.messages.isEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.conversations, key = { it.id }) { conv ->
                    Card(onClick = { viewModel.openConversation(conv.id) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conv.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${conv.messageCount} msgs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        } else {
            // Messages
            val listState = rememberLazyListState()
            LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
                if (state.messages.isNotEmpty()) {
                    listState.animateScrollToItem(state.messages.size - 1)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    AetherBubble(msg)
                }
            }
        }

        // Error banner
        state.error?.let { err ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        err,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }

        // Input bar
        val context = LocalContext.current
        var isListening by remember { mutableStateOf(false) }
        val stt = remember {
            com.hermes.android.util.SpeechToTextHelper(
                context = context,
                onResult = { text -> viewModel.setDraft(text) },
                onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
                onListeningChanged = { isListening = it },
            )
        }
        DisposableEffect(Unit) {
            onDispose { stt.destroy() }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = {
                    if (isListening) stt.stop() else stt.start()
                },
                enabled = stt.isAvailable,
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = t("Voice input", "ورودی صوتی"),
                    tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = { viewModel.setDraft(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(t("Message…", "پیام…")) },
                maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            if (state.isGenerating) {
                Button(onClick = { viewModel.stopGeneration() }) {
                    Text(t("Stop", "توقف"))
                }
            } else {
                IconButton(
                    onClick = { viewModel.send() },
                    enabled = state.draft.isNotBlank() && state.isConfigured,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = t("Send", "ارسال"))
                }
            }
        }
    }
}

@Composable
private fun AetherBubble(msg: AetherMessage) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            if (isUser) t("You", "شما") else t("Model", "مدل"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 1f else 1f),
        ) {
            if (msg.content.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(12.dp).height(20.dp).width(20.dp),
                )
            } else {
                HermesMarkdown(
                    markdown = msg.content,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

// ── Settings pane ─────────────────────────────────────────────────────────

@Composable
private fun AetherSettingsPane(
    padding: PaddingValues,
    viewModel: AetherViewModel,
    config: AetherConfig,
    onDone: () -> Unit,
) {
    var draft by remember(config) { mutableStateOf(config) }
    var keyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t("Provider", "پرووایدر"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        // Provider chips (scrollable row via LazyColumn is heavy; FlowRow-ish wrap with simple rows)
        AETHER_PROVIDER_PRESETS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    FilterChip(
                        selected = draft.providerId == preset.id,
                        onClick = {
                            viewModel.applyPreset(preset)
                            draft = draft.copy(
                                providerId = preset.id,
                                protocol = preset.proto.name,
                                baseUrl = preset.base.ifBlank { draft.baseUrl },
                            )
                        },
                        label = { Text(preset.name, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { draft = draft.copy(baseUrl = it) },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { draft = draft.copy(apiKey = it) },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Model row + discovery
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                label = { Text(t("Model", "مدل")) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { viewModel.discoverModels() }) {
                Icon(Icons.Default.Refresh, contentDescription = t("Discover models", "کشف مدل‌ها"))
            }
        }
        val s by viewModel.uiState.collectAsState()
        if (s.modelsLoading) {
            CircularProgressIndicator()
        } else if (s.modelSuggestions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.height(160.dp)) {
                items(s.modelSuggestions) { m ->
                    Card(onClick = { draft = draft.copy(model = m) }) {
                        Text(m, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Sampling params
        Text("${t("Temperature", "دما")}: ${"%.2f".format(draft.temperature)}")
        Slider(
            value = draft.temperature,
            onValueChange = { draft = draft.copy(temperature = it) },
            valueRange = 0f..2f,
        )
        Text("${t("Max tokens", "حداکثر توکن")}: ${draft.maxTokens}")
        Slider(
            value = draft.maxTokens.toFloat(),
            onValueChange = { draft = draft.copy(maxTokens = it.toInt()) },
            valueRange = 256f..32768f,
        )

        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = { draft = draft.copy(systemPrompt = it) },
            label = { Text(t("System prompt (optional)", "پرامپت سیستم (اختیاری)")) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Button(
            onClick = {
                viewModel.saveConfig(draft)
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(t("Save", "ذخیره"))
        }
    }
}