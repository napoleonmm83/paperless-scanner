package com.paperless.scanner.util

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.paperless.scanner.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val biometricManager = BiometricManager.from(context)

    fun canAuthenticate(): BiometricStatus {
        return when (biometricManager.canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SecurityUpdateRequired
            else -> BiometricStatus.Unknown
        }
    }

    fun isAvailable(): Boolean = canAuthenticate() == BiometricStatus.Available

    fun authenticate(
        activity: FragmentActivity,
        title: String? = null,
        subtitle: String? = null,
        negativeButtonText: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onFallback: () -> Unit
    ) {
        val resolvedTitle = title ?: context.getString(R.string.biometric_title)
        val resolvedSubtitle = subtitle ?: context.getString(R.string.biometric_subtitle)
        val resolvedNegativeButtonText = negativeButtonText ?: context.getString(R.string.biometric_negative_button)
        Log.d("BiometricHelper", "authenticate() called with activity: ${activity::class.java.simpleName}")
        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED
                ) {
                    onFallback()
                } else {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Don't call onError here - the system will show its own message
                // and allow retry
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(resolvedTitle)
            .setSubtitle(resolvedSubtitle)
            .setNegativeButtonText(resolvedNegativeButtonText)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo)
    }
}

sealed class BiometricStatus {
    data object Available : BiometricStatus()
    data object NoHardware : BiometricStatus()
    data object HardwareUnavailable : BiometricStatus()
    data object NoneEnrolled : BiometricStatus()
    data object SecurityUpdateRequired : BiometricStatus()
    data object Unknown : BiometricStatus()
}
