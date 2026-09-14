package com.qtotp.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric unlock, done by sealing the vault key rather than the password.
 *
 * A hardware-backed AES key in the Android Keystore wraps the 32-byte vault
 * key. That Keystore key is marked as requiring user authentication, so the
 * wrapped copy can only be opened inside a successful BiometricPrompt: the
 * ciphertext sitting in preferences is useless to anything that cannot produce
 * a live fingerprint or face match.
 *
 * The key is also invalidated when biometric enrollment changes, so adding a
 * new fingerprint forces a fall back to the password. Callers must handle
 * [KeyInvalidatedException] by clearing the stored wrapper and asking for the
 * password again.
 */
object BiometricVaultKey {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "qt-otp-vault-key-wrapper"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** The Keystore key is gone or no longer usable; the password is the only way in. */
    class KeyInvalidatedException(cause: Throwable? = null) :
        Exception("biometric key is no longer valid", cause)

    /**
     * A cipher that BiometricPrompt will unlock, plus what to do once it has.
     *
     * BiometricPrompt takes the cipher, authenticates the user, and hands the
     * same (now authorized) cipher back, at which point the wrap or unwrap can
     * actually run.
     */
    fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Enrollment changed since the key was made; start over with a new one.
            deleteKey()
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        return cipher
    }

    fun decryptCipher(ivBase64: String): Cipher {
        val key = existingKey() ?: throw KeyInvalidatedException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = decode(ivBase64)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyInvalidatedException(e)
        }
        return cipher
    }

    /** Seal [vaultKey] with an authenticated [cipher] from [encryptCipher]. */
    fun wrap(cipher: Cipher, vaultKey: ByteArray): WrappedKey {
        val sealed = cipher.doFinal(vaultKey)
        return WrappedKey(encode(sealed), encode(cipher.iv))
    }

    /** Recover the vault key with an authenticated [cipher] from [decryptCipher]. */
    fun unwrap(cipher: Cipher, wrapped: String): ByteArray = cipher.doFinal(decode(wrapped))

    data class WrappedKey(val ciphertext: String, val iv: String)

    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Every use needs a fresh authentication; no validity window.
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encode(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
