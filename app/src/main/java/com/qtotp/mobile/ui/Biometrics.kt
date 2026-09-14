package com.qtotp.mobile.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * A thin wrapper over BiometricPrompt that hands back the authorized cipher.
 *
 * The vault key is sealed by a Keystore key that requires authentication, so
 * the cipher has to make the round trip through the prompt: it goes in
 * unauthorized and comes back usable exactly once.
 */
class BiometricGate(private val activity: FragmentActivity) {

    /** Strong biometrics only: a Keystore key tied to auth will not accept anything weaker. */
    private val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG

    sealed interface Availability {
        object Ready : Availability
        data class Unavailable(val reason: String) : Availability
    }

    fun availability(): Availability =
        when (BiometricManager.from(activity).canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Ready
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                Availability.Unavailable("This device has no biometric sensor")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                Availability.Unavailable("The biometric sensor is unavailable right now")
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                Availability.Unavailable("Enroll a fingerprint or face in system settings first")
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                Availability.Unavailable("Biometrics need a security update on this device")
            else -> Availability.Unavailable("Biometric unlock is not available on this device")
        }

    val isReady: Boolean get() = availability() is Availability.Ready

    /**
     * Show the prompt for [cipher].
     *
     * [onSuccess] receives the same cipher, now authorized. [onError] carries a
     * message worth showing; a plain user cancellation reports nothing.
     */
    fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String,
        onSuccess: (Cipher) -> Unit,
        onError: (String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized == null) {
                        onError("The device did not return a usable key")
                    } else {
                        onSuccess(authorized)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onError(if (cancelled) null else errString.toString())
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use password")
            .setAllowedAuthenticators(allowed)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
