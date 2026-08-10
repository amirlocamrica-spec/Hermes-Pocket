package com.hermes.android.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized access to app preferences.
 *
 * Uses the same "hermes_chat_prefs" SharedPreferences file the rest of the
 * app reads (ChatViewModel, ConfigScreen), so voice/biometric settings live
 * alongside existing client-side appearance settings.
 */
object AppPrefs {

    private const val PREFS_NAME = "hermes_chat_prefs"

    // Voice
    const val KEY_VOICE_REPLIES = "voice_replies_enabled"
    const val KEY_VOICE_AUTOPLAY = "voice_autoplay_enabled"
    const val KEY_VOICE_SPEED = "voice_speed"          // Float, 0.5f..2.0f, default 1.0f

    // Security
    const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
    const val KEY_BIOMETRIC_LOCK_TIMEOUT_MIN = "biometric_lock_timeout_min" // Int, default 5

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Voice ---

    fun isVoiceRepliesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_REPLIES, false)

    fun setVoiceRepliesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_REPLIES, enabled).apply()
    }

    fun isVoiceAutoplayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_AUTOPLAY, false)

    fun setVoiceAutoplayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_AUTOPLAY, enabled).apply()
    }

    fun getVoiceSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_VOICE_SPEED, 1.0f).coerceIn(0.5f, 2.0f)

    fun setVoiceSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(KEY_VOICE_SPEED, speed.coerceIn(0.5f, 2.0f)).apply()
    }

    // --- Security ---

    fun isBiometricLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC_LOCK, false)

    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_LOCK, enabled).apply()
    }

    fun getBiometricLockTimeoutMinutes(context: Context): Int =
        prefs(context).getInt(KEY_BIOMETRIC_LOCK_TIMEOUT_MIN, 5).coerceAtLeast(0)

    fun setBiometricLockTimeoutMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_BIOMETRIC_LOCK_TIMEOUT_MIN, minutes.coerceAtLeast(0)).apply()
    }
}
