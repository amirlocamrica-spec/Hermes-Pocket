package com.hermes.android.xkiro

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the xkiro.com API key in SharedPreferences.
 * Singleton — lives across the whole app lifecycle.
 */
@Singleton
class XKiroApiStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(loadKey())
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    fun saveKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_API_KEY, trimmed).apply()
        _apiKey.value = trimmed
    }

    fun clearKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
        _apiKey.value = null
    }

    fun hasKey(): Boolean = !loadKey().isNullOrBlank()

    private fun loadKey(): String? = prefs.getString(KEY_API_KEY, null)

    companion object {
        private const val PREFS_NAME = "xkiro_settings"
        private const val KEY_API_KEY = "api_key"
    }
}
