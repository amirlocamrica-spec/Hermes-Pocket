package com.hermes.android.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermes.android.ui.design.SettingsGroup
import com.hermes.android.ui.i18n.t
import com.hermes.android.ui.viewmodel.ConfigUiState
import com.hermes.android.ui.viewmodel.ConfigViewModel
import com.hermes.android.util.AppPrefs

/**
 * Voice & Security settings:
 * - Voice replies: speak assistant answers aloud (TextToSpeech)
 * - Auto-play voice: automatically speak each new reply
 * - Biometric lock: require fingerprint/face before showing chat content
 */
@Composable
internal fun VoiceSecuritySection(
    state: ConfigUiState,
    viewModel: ConfigViewModel,
) {
    val context = LocalContext.current

    var voiceReplies by remember { mutableStateOf(AppPrefs.isVoiceRepliesEnabled(context)) }
    var voiceAutoplay by remember { mutableStateOf(AppPrefs.isVoiceAutoplayEnabled(context)) }
    var biometricLock by remember { mutableStateOf(AppPrefs.isBiometricLockEnabled(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SettingsGroup {
            SettingSwitchRow(
                title = t("Voice replies", "پاسخ صوتی"),
                subtitle = t(
                    "Speak assistant replies aloud using the device text-to-speech engine.",
                    "پاسخ‌های دستیار را با موتور گفتار دستگاه بلند بخوان.",
                ),
                checked = voiceReplies,
                onCheckedChange = {
                    voiceReplies = it
                    AppPrefs.setVoiceRepliesEnabled(context, it)
                },
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            SettingSwitchRow(
                title = t("Auto-play voice", "پخش خودکار صدا"),
                subtitle = t(
                    "Automatically speak each new reply when voice replies are enabled.",
                    "وقتی پاسخ صوتی فعال است، هر پاسخ جدید را خودکار پخش کن.",
                ),
                checked = voiceAutoplay,
                onCheckedChange = {
                    voiceAutoplay = it
                    AppPrefs.setVoiceAutoplayEnabled(context, it)
                },
            )
        }

        SettingsGroup {
            Text(
                text = t("Security", "امنیت"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SettingSwitchRow(
                title = t("Biometric lock", "قفل بیومتریک"),
                subtitle = t(
                    "Require fingerprint or face unlock before showing chat content. Protects the gateway token on this device.",
                    "برای نمایش محتوای چت، اثر انگشت یا چهره بخواه. از توکن گیت‌وی روی این دستگاه محافظت می‌کند.",
                ),
                checked = biometricLock,
                onCheckedChange = {
                    biometricLock = it
                    AppPrefs.setBiometricLockEnabled(context, it)
                },
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
