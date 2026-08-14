package com.hermes.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.android.runtime.remote.ServerProfile
import com.hermes.android.runtime.remote.ServerProfileStore
import com.hermes.android.ui.design.HermesScaffold
import com.hermes.android.ui.i18n.t
import androidx.compose.material3.AlertDialog

/**
 * ServerProfiles — manage multiple Hermes server connections and switch
 * between them with one tap (ported from Triad triad-plus.js).
 */
@Composable
fun ServerProfilesScreen(
    onNavigateBack: () -> Unit = {},
    store: ServerProfileStore,
) {
    val profiles by store.profiles.collectAsState()
    val activeId = store.activeId()
    var showAdd by remember { mutableStateOf(false) }
    var showSwitchConfirm by remember { mutableStateOf<ServerProfile?>(null) }

    HermesScaffold(
        title = t("Server Profiles", "پروفایل سرورها"),
        subtitle = t("Multiple Hermes servers, one tap to switch", "چند سرور هرمس، سوییچ با یک لمس"),
        onBack = onNavigateBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = t("Add server", "افزودن سرور"))
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    t("No server profiles yet", "هنوز پروفایل سروری نیست"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    t("Add your Hermes server to connect", "سرور هرمس خود را اضافه کنید"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.id == activeId,
                        onActivate = { showSwitchConfirm = profile },
                        onDelete = { store.remove(profile.id) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddProfileDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url, token ->
                store.add(name, url, token)
                showAdd = false
            },
        )
    }

    showSwitchConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = null },
            title = { Text(t("Switch server?", "تعویض سرور؟")) },
            text = {
                Text(t(
                    "The app will reconnect to \"${target.name}\". Current chat stays intact.",
                    "اپ به \"${target.name}\" وصل می‌شود. چت فعلی حفظ می‌شود.",
                ))
            },
            confirmButton = {
                Button(onClick = {
                    store.activate(target.id)
                    showSwitchConfirm = null
                }) {
                    Text(t("Switch", "تعویض"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchConfirm = null }) {
                    Text(t("Cancel", "انصراف"))
                }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ServerProfile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    profile.serverUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (!isActive) {
                TextButton(onClick = onActivate) {
                    Text(t("Activate", "فعال کن"))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = t("Delete", "حذف"),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddProfileDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, token: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Add server profile", "افزودن پروفایل سرور")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t("Name (optional)", "نام (اختیاری)")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(t("Server URL", "آدرس سرور")) },
                    singleLine = true,
                    placeholder = { Text("https://example.com:2083") },
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(t("Session token", "توکن")) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, token) },
                enabled = url.isNotBlank() && token.isNotBlank(),
            ) {
                Text(t("Add", "افزودن"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("Cancel", "انصراف"))
            }
        },
    )
}