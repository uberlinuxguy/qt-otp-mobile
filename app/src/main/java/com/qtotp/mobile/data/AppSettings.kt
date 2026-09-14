package com.qtotp.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** How long the app may sit in the background before it locks itself. */
enum class AutoLockDelay(val seconds: Int, val label: String) {
    IMMEDIATELY(0, "Immediately"),
    THIRTY_SECONDS(30, "After 30 seconds"),
    ONE_MINUTE(60, "After 1 minute"),
    FIVE_MINUTES(300, "After 5 minutes"),
    NEVER(-1, "Only when I lock it");

    companion object {
        fun fromSeconds(seconds: Int): AutoLockDelay =
            entries.firstOrNull { it.seconds == seconds } ?: IMMEDIATELY
    }
}

data class Settings(
    val autoLock: AutoLockDelay = AutoLockDelay.IMMEDIATELY,
    val hideCodes: Boolean = false,
    val blockScreenshots: Boolean = true,
    /** The vault key sealed by a Keystore key that requires biometric auth. */
    val wrappedKey: String? = null,
    val wrappedKeyIv: String? = null,
) {
    val biometricEnabled: Boolean get() = wrappedKey != null && wrappedKeyIv != null
}

/**
 * Preferences, none of which are secret on their own.
 *
 * The wrapped vault key is stored here too: it is ciphertext under a key that
 * lives in the Android Keystore and cannot be used without a fresh biometric
 * match, so on its own this value is inert.
 */
class AppSettings(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            autoLock = AutoLockDelay.fromSeconds(prefs[AUTO_LOCK] ?: 0),
            hideCodes = prefs[HIDE_CODES] ?: false,
            blockScreenshots = prefs[BLOCK_SCREENSHOTS] ?: true,
            wrappedKey = prefs[WRAPPED_KEY],
            wrappedKeyIv = prefs[WRAPPED_KEY_IV],
        )
    }

    suspend fun setAutoLock(delay: AutoLockDelay) {
        context.dataStore.edit { it[AUTO_LOCK] = delay.seconds }
    }

    suspend fun setHideCodes(hide: Boolean) {
        context.dataStore.edit { it[HIDE_CODES] = hide }
    }

    suspend fun setBlockScreenshots(block: Boolean) {
        context.dataStore.edit { it[BLOCK_SCREENSHOTS] = block }
    }

    suspend fun setWrappedKey(wrapped: String, iv: String) {
        context.dataStore.edit {
            it[WRAPPED_KEY] = wrapped
            it[WRAPPED_KEY_IV] = iv
        }
    }

    /**
     * Forget the biometric-wrapped key.
     *
     * Called when biometrics are switched off, when the password changes, and
     * when a different vault is imported, since in each case the stored
     * ciphertext no longer corresponds to the vault on disk.
     */
    suspend fun clearWrappedKey() {
        context.dataStore.edit {
            it.remove(WRAPPED_KEY)
            it.remove(WRAPPED_KEY_IV)
        }
    }

    private companion object {
        val AUTO_LOCK = intPreferencesKey("auto_lock_seconds")
        val HIDE_CODES = booleanPreferencesKey("hide_codes")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val WRAPPED_KEY = stringPreferencesKey("wrapped_vault_key")
        val WRAPPED_KEY_IV = stringPreferencesKey("wrapped_vault_key_iv")
    }
}
