package com.hermes.android.runtime.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ServerProfiles — multiple Hermes server connections with fast switching
 * (ported from Triad's triad-plus.js profile manager).
 *
 * The ACTIVE profile is mirrored into [RemoteServerSettings], so the rest
 * of the app (gateway, RuntimeViewModel) keeps working unchanged. Profiles
 * themselves persist as JSON in SharedPreferences.
 */
@Singleton
class ServerProfileStore @Inject constructor(
    @ApplicationContext context: Context,
    private val remoteSettings: RemoteServerSettings,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow<List<ServerProfile>>(load())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun add(name: String, serverUrl: String, token: String) {
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { hostOf(serverUrl) },
            serverUrl = RemoteServerSettings.normalizeUrl(serverUrl),
            token = token.trim(),
        )
        val list = _profiles.value + profile
        persist(list)
        // First profile becomes active automatically.
        if (activeId() == null) activate(profile.id)
    }

    fun remove(id: String) {
        val list = _profiles.value.filterNot { it.id == id }
        persist(list)
        if (activeId() == id) {
            prefs.edit().remove(KEY_ACTIVE).apply()
            list.firstOrNull()?.let { activate(it.id) }
        }
    }

    /** Switch the gateway connection to another saved profile. */
    fun activate(id: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        remoteSettings.save(profile.serverUrl, profile.token)
    }

    /** Update the active profile from the Runtime Setup form (keeps them in sync). */
    fun syncActiveFromCurrent(serverUrl: String, token: String) {
        val id = activeId() ?: return
        val list = _profiles.value.map {
            if (it.id == id) it.copy(serverUrl = RemoteServerSettings.normalizeUrl(serverUrl), token = token.trim()) else it
        }
        persist(list)
    }

    fun rename(id: String, name: String) {
        persist(_profiles.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    private fun persist(list: List<ServerProfile>) {
        _profiles.value = list
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(list)).apply()
    }

    private fun load(): List<ServerProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ServerProfile>>(raw) }.getOrDefault(emptyList())
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(RemoteServerSettings.normalizeUrl(url)).host ?: url }.getOrDefault(url)

    companion object {
        private const val PREFS_NAME = "hermes_server_profiles"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_profile_id"
    }
}

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val serverUrl: String,
    val token: String,
)