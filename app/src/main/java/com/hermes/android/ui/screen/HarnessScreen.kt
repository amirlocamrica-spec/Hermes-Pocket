package com.hermes.android.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.android.ui.component.HermesMarkdown
import com.hermes.android.ui.design.HermesScaffold
import com.hermes.android.ui.i18n.t
import com.hermes.android.ui.viewmodel.HarnessUiMessage
import com.hermes.android.ui.viewmodel.HarnessViewModel

/**
 * Harness — on-device agent chat with local tools (calculator, time,
 * notes, http_get). Ported from Triad's Harness mode.
 */
@Composable
fun HarnessScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: HarnessViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.isConfigured) {
        if (!state.isConfigured) showSettings = true
    }

    HermesScaffold(
        title = t("Harness", "هارنس"),
        subtitle = if (state.isConfigured) {
            state.config.model
        } else {
            t("On-device agent with local tools", "ایجنت روی دستگاه با ابزارهای لوکال")
        },
        onBack = onNavigateBack,
        actions = {
            IconButton(onClick = { showSettings = !showSettings }) {
                Icon(
                    if (showSettings) Icons.Default.Close else Icons.Default.Settings,
                    contentDescription = t("Settings", "تنظیمات"),
                )
            }
        },
    ) { padding ->
        if (showSettings) {
            HarnessSettingsPane(padding, viewModel) { showSettings = false }
        } else {
            HarnessChatPane(padding, viewModel)
        }
    }
}

@Composable
private fun HarnessChatPane(
    padding: PaddingValues,
    viewModel: HarnessViewModel,
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(state.messages.size) {
            if (state.messages.isNotEmpty()) {
                listState.animateScrollToItem(state.messages.size - 1)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages) { msg ->
                when (msg) {
                    is HarnessUiMessage.Chat -> ChatBubble(msg)
                    is HarnessUiMessage.ToolEvent -> ToolEventRow(msg)
                }
            }
        }

        state.error?.let { err ->
            Text(
                err,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = { viewModel.setDraft(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(t("Ask the agent…", "از ایجنت بپرس…")) },
                maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.send() },
                enabled = state.draft.isNotBlank() && state.isConfigured && !state.isRunning,
            ) {
                if (state.isRunning) {
                    Text(t("Running…", "در حال اجرا…"))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = t("Send", "ارسال"))
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: HarnessUiMessage.Chat) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            HermesMarkdown(
                markdown = msg.content,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun ToolEventRow(msg: HarnessUiMessage.ToolEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                "🔧 ${msg.toolName}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (msg.detail.isNotBlank()) {
                Text(
                    msg.detail.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HarnessSettingsPane(
    padding: PaddingValues,
    viewModel: HarnessViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var baseUrl by remember { mutableStateOf(state.config.baseUrl) }
    var apiKey by remember { mutableStateOf(state.config.apiKey) }
    var model by remember { mutableStateOf(state.config.model) }
    var systemPrompt by remember { mutableStateOf(state.config.systemPrompt) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t("Harness uses the same OpenAI-compatible endpoint as Aether.", "هارنس از همان اندپوینت OpenAI-compatible اترون استفاده می‌کنه."))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://api.example.com/v1") },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(t("Model", "مدل")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text(t("System prompt", "پرامپت سیستم")) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Text(
            t(
                "Local tools: calculate, current_time, notes_save, notes_read, http_get",
                "ابزارهای لوکال: calculate، current_time، notes_save، notes_read، http_get",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                viewModel.saveConfig(baseUrl, apiKey, model, systemPrompt)
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(t("Save", "ذخیره"))
        }
    }
}