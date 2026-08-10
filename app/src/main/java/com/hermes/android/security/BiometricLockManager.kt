package com.hermes.android.security

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Manages the biometric app lock.
 *
 * The app holds a gateway token that grants full control over the agent
 * (shell exec, config, secrets), so we offer a biometric gate on foreground.
 *
 * Usage:
 *   val lock = BiometricLockManager(activity)
 *   if (lock.isLockRequired()) lock.authenticate(onSuccess = { ... })
 */
class BiometricLockManager(private val activity: FragmentActivity) {

    private var cancellationSignal: CancellationSignal? = null

    /** True if the OS has biometric hardware + enrolled credentials. */
    fun isBiometricAvailable(): Boolean {
        val bm = BiometricManager.from(activity)
        return when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Whether the lock should be shown right now: the feature is enabled in
     * prefs AND (no recent successful unlock OR the timeout has elapsed).
     *
     * [lastUnlockElapsedMs] is the time since the last successful unlock
     * (pass Long.MAX_VALUE if never unlocked this process).
     */
    fun isLockRequired(lastUnlockElapsedMs: Long = Long.MAX_VALUE): Boolean {
        if (!AppPrefsHolder.isEnabled(activity)) return false
        val timeoutMin = AppPrefsHolder.timeoutMinutes(activity)
        if (timeoutMin <= 0) return true
        val timeoutMs = timeoutMin * 60_000L
        return lastUnlockElapsedMs >= timeoutMs
    }

    /**
     * Shows the biometric prompt. [onSuccess] is invoked after a successful
     * authentication. If biometrics aren't enrolled/available, [onUnavailable]
     * is invoked instead so the caller can fall back gracefully.
     */
    fun authenticate(onSuccess: () -> Unit, onUnavailable: (() -> Unit)? = null) {
        if (!isBiometricAvailable()) {
            onUnavailable?.invoke()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // User cancelled / too many attempts — caller decides (e.g. stay locked).
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Hermes Lock")
            .setSubtitle("Unlock to continue")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        cancellationSignal = CancellationSignal()
        prompt.authenticate(promptInfo)
    }

    fun cancel() {
        cancellationSignal?.cancel()
        cancellationSignal = null
    }

    /**
     * Small holder so this class can read prefs without a static context
     * dependency cycle. Reads the same "hermes_chat_prefs" file.
     */
    private object AppPrefsHolder {
        private const val PREFS = "hermes_chat_prefs"
        private const val KEY_ENABLED = "biometric_lock_enabled"
        private const val KEY_TIMEOUT = "biometric_lock_timeout_min"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun timeoutMinutes(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TIMEOUT, 5)
    }
}
