package com.hermes.android.aether

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Aether provider configuration (provider preset, base URL,
 * API key, model, sampling params).
 *
 * Storage: SharedPreferences file `aether_settings`, same pattern as
 * RemoteServerSettings. The key never leaves the device and is never
 * logged.
 */
@Singleton
class AetherSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow(load())

    /** Current config, observable for the settings/chat UI. */
    val config: StateFlow<AetherConfig> = _config.asStateFlow()

    val isConfigured: Boolean get() = _config.value.isComplete

    fun save(config: AetherConfig) {
        val normalized = config.copy(baseUrl = normalizeBase(config.baseUrl))
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(normalized)).apply()
        _config.value = normalized
    }

    /** Apply a provider preset: proto + base URL, keep key/model if base unchanged. */
    fun applyPreset(preset: ProviderPreset) {
        val current = _config.value
        val keepKey = current.baseUrl == normalizeBase(preset.base)
        save(
            current.copy(
                providerId = preset.id,
                protocol = preset.proto.name,
                baseUrl = preset.base,
                apiKey = if (keepKey) current.apiKey else "",
                model = if (keepKey) current.model else "",
            ),
        )
    }

    private fun load(): AetherConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return AetherConfig()
        return runCatching { json.decodeFromString<AetherConfig>(raw) }.getOrDefault(AetherConfig())
    }

    companion object {
        private const val PREFS_NAME = "aether_settings"
        private const val KEY_CONFIG = "config_json"

        /** Trim whitespace/trailing slashes; user input may paste either. */
        fun normalizeBase(input: String): String = input.trim().trimEnd('/')
    }
}