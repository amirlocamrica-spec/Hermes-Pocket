package com.hermes.android.runtime.remote

import android.content.Context
import com.hermes.android.security.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-entered connection settings for the remote Hermes server.
 *
 * ## Storage
 * Persisted in ENCRYPTED SharedPreferences (Keystore-backed, see
 * [SecurePrefs]) — the gateway token grants full agent control and must
 * never sit in a plaintext prefs file. A one-time migration pulls values
 * out of the legacy plaintext file older versions wrote, then deletes it.
 * The token is entered by the user in the Runtime Setup screen and must
 * match the `HERMES_DASHBOARD_SESSION_TOKEN` the server was started with.
 *
 * ## URL format
 * [serverUrl] is the base URL of the reverse proxy in front of
 * `hermes dashboard`, e.g. `wss://example.com:2083`. The full WebSocket
 * URL is derived as `<serverUrl>/api/ws?token=<token>` — the `/api/ws`
 * path is Hermes' FastAPI WebSocket endpoint and never changes, so the
 * user only enters host/port.
 *
 * Reference: server-side setup — `hermes dashboard --host 127.0.0.1 --port 9119`
 * behind a TLS reverse proxy (Caddy) on the public port.
 */
@Singleton
class RemoteServerSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    securePrefs: SecurePrefs,
) {
    private val prefs = securePrefs.prefs(PREFS_NAME)

    private val _config = MutableStateFlow(load())

    /** Current config, observable so the setup UI stays in sync. */
    val config: StateFlow<RemoteServerConfig> = _config.asStateFlow()

    /** True when both URL and token have been entered. */
    val isConfigured: Boolean get() = _config.value.isComplete

    fun save(serverUrl: String, token: String) {
        val normalized = normalizeUrl(serverUrl)
        prefs.edit()
            .putString(KEY_SERVER_URL, normalized)
            .putString(KEY_TOKEN, token.trim())
            .apply()
        _config.value = RemoteServerConfig(normalized, token.trim())
    }

    /**
     * Full WebSocket URL for [com.hermes.android.gateway.GatewayClient.connect],
     * or null when not configured.
     *
     * The stored base URL uses the web scheme (https/http — OkHttp requires
     * it); here we map it back to the WS scheme for the actual socket.
     */
    fun webSocketUrl(): String? {
        val c = _config.value
        if (!c.isComplete) return null
        val base = when {
            c.serverUrl.startsWith("https://") -> "wss://" + c.serverUrl.removePrefix("https://")
            c.serverUrl.startsWith("http://") -> "ws://" + c.serverUrl.removePrefix("http://")
            else -> c.serverUrl
        }
        // Token is base64-ish and may contain + / = characters which are
        // reserved/ambiguous in query strings — encode it so the server
        // receives the exact bytes (unencoded tokens caused HTTP 401).
        val encodedToken = android.net.Uri.encode(c.token, "")
        return "$base$WS_PATH?token=$encodedToken"
    }

    private fun load(): RemoteServerConfig {
        migrateLegacyIfNeeded()
        return RemoteServerConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
            token = prefs.getString(KEY_TOKEN, "") ?: "",
        )
    }

    /**
     * One-time migration: earlier versions stored the gateway token in a
     * PLAINTEXT SharedPreferences file. Move both values into the encrypted
     * store, then delete the plaintext file so the token no longer sits
     * unencrypted on disk (or inside adb backups).
     *
     * Runs before the first [load]; failures are logged but never crash —
     * worst case the user re-enters the config on the setup screen.
     */
    private fun migrateLegacyIfNeeded() {
        try {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) {
                context.deleteSharedPreferences(LEGACY_PREFS_NAME)
                return
            }
            val legacyUrl = legacy.getString(KEY_SERVER_URL, "") ?: ""
            val legacyToken = legacy.getString(KEY_TOKEN, "") ?: ""
            // Only migrate when the encrypted store doesn't already hold
            // values (don't clobber a fresher config).
            if (prefs.getString(KEY_TOKEN, "").isNullOrBlank()) {
                prefs.edit()
                    .putString(KEY_SERVER_URL, legacyUrl)
                    .putString(KEY_TOKEN, legacyToken)
                    .commit() // commit, not apply: the plaintext file is deleted right after
            }
            legacy.edit().clear().commit()
            context.deleteSharedPreferences(LEGACY_PREFS_NAME)
            Timber.i("[RemoteServerSettings] migrated legacy config into encrypted storage")
        } catch (e: Exception) {
            Timber.w(e, "[RemoteServerSettings] legacy migration failed")
        }
    }

    companion object {
        private const val PREFS_NAME = "hermes_remote_server_secure"
        private const val LEGACY_PREFS_NAME = "hermes_remote_server"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val WS_PATH = "/api/ws"

        /**
         * Normalize user input to a `wss://host[:port]` base URL:
         * - trims whitespace and trailing slashes
         * - strips an accidentally-pasted `/api/ws...` suffix
         * - adds `wss://` when no scheme was given
         * - maps `https`/`http` to `wss`/`ws`
         *
         * NOTE: the value stored is the *web* scheme (https/http) because
         * OkHttp's `Request.Builder().url()` only accepts http/https —
         * it performs the WS upgrade itself. The WS scheme is derived in
         * [webSocketUrl].
         */
        fun normalizeUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            // User pasted the full WS URL — keep only the base.
            url = url.substringBefore(WS_PATH)
            url = when {
                url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
                url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
                url.startsWith("https://") || url.startsWith("http://") -> url
                url.isEmpty() -> url
                // Default to http:// (not https://): the common remote-server
                // case here is a Railway TCP proxy or a plain http endpoint,
                // which has NO TLS termination — assuming https:// forces
                // wss:// downstream and the TLS handshake fails. Users who run
                // a real TLS front (cloudflare/https domain) enter https://
                // explicitly.
                else -> "http://$url"
            }
            return url.trimEnd('/')
        }
    }
}

/** Immutable snapshot of the remote server connection settings. */
data class RemoteServerConfig(
    val serverUrl: String,
    val token: String,
) {
    val isComplete: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}