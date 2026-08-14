package com.hermes.android.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted SharedPreferences backed by the Android Keystore.
 *
 * Every secret the app persists (gateway session token, server credentials)
 * must go through this class instead of `getSharedPreferences` — plain
 * SharedPreferences files are world-readable inside adb backups and on
 * rooted devices.
 *
 * Encryption: AES256-SIV for keys, AES256-GCM for values, master key held
 * in hardware-backed Keystore (AES256_GCM scheme).
 *
 * Failure mode: if the Keystore key is invalidated (factory reset artifact,
 * keystore corruption, ROM bug) `EncryptedSharedPreferences.create` throws.
 * Rather than crash-looping, we delete the broken file and recreate it —
 * the user re-enters the secret once, which beats an unusable app.
 */
@Singleton
class SecurePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = HashMap<String, SharedPreferences>()

    /** Returns (and caches) the encrypted store for [fileName]. */
    @Synchronized
    fun prefs(fileName: String): SharedPreferences {
        cache[fileName]?.let { return it }
        val prefs = openOrCreate(fileName)
        cache[fileName] = prefs
        return prefs
    }

    private fun openOrCreate(fileName: String): SharedPreferences = try {
        create(fileName)
    } catch (e: Exception) {
        Timber.w(e, "[SecurePrefs] cannot open $fileName — recreating (secrets inside lost)")
        context.deleteSharedPreferences(fileName)
        try {
            create(fileName)
        } catch (e2: Exception) {
            Timber.e(e2, "[SecurePrefs] recreation also failed for $fileName")
            throw e2
        }
    }

    private fun create(fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}