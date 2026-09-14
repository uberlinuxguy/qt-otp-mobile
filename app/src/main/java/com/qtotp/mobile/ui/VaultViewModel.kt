package com.qtotp.mobile.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qtotp.mobile.core.OtpEntry
import com.qtotp.mobile.core.VaultCrypto
import com.qtotp.mobile.data.AppSettings
import com.qtotp.mobile.data.AutoLockDelay
import com.qtotp.mobile.data.BiometricVaultKey
import com.qtotp.mobile.data.Settings
import com.qtotp.mobile.data.VaultSession
import com.qtotp.mobile.data.VaultStore
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which top-level screen the app is on. */
enum class Phase { LOADING, SETUP, LOCKED, UNLOCKED }

data class VaultUiState(
    val phase: Phase = Phase.LOADING,
    val entries: List<OtpEntry> = emptyList(),
    val settings: Settings = Settings(),
    val busy: Boolean = false,
    val unlockError: String? = null,
    val message: String? = null,
    val revealedEntryId: String? = null,
)

class VaultViewModel(
    application: Application,
    private val store: VaultStore,
    private val settingsRepository: AppSettings,
) : AndroidViewModel(application) {

    private val session = VaultSession(store)

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    /**
     * Wall-clock seconds, ticking while something is watching.
     *
     * Codes and their countdown rings are derived from this rather than kept in
     * state, so the entry list is not rewritten several times a second.
     */
    val now: StateFlow<Double> = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000.0)
            delay(TICK_MILLIS)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(1_000),
        initialValue = System.currentTimeMillis() / 1000.0,
    )

    private var backgroundedAt: Long? = null

    /**
     * True while a system file picker owns the screen.
     *
     * Opening the picker backgrounds the activity, which would otherwise trip
     * auto-lock and dump the user on the unlock screen in the middle of an
     * import or export they just asked for.
     */
    private var pickerPending = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        refreshPhase()
    }

    private fun refreshPhase() {
        _state.update {
            it.copy(phase = if (store.exists) Phase.LOCKED else Phase.SETUP)
        }
    }

    // --------------------------------------------------------------- unlocking

    fun unlock(password: String) {
        if (password.isEmpty()) {
            _state.update { it.copy(unlockError = "Enter your password") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, unlockError = null) }
            val result = withContext(Dispatchers.Default) {
                runCatching { session.unlock(password) }
            }
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            phase = Phase.UNLOCKED,
                            entries = session.entries,
                            busy = false,
                            unlockError = null,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, unlockError = unlockMessage(error)) }
                },
            )
        }
    }

    /** Unlock using a Keystore cipher that BiometricPrompt has already authorized. */
    fun unlockWithBiometricCipher(cipher: Cipher) {
        val wrapped = _state.value.settings.wrappedKey ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, unlockError = null) }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val vaultKey = BiometricVaultKey.unwrap(cipher, wrapped)
                    try {
                        session.unlockWithKey(vaultKey)
                    } catch (e: Exception) {
                        VaultCrypto.zeroize(vaultKey)
                        throw e
                    }
                }
            }
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(phase = Phase.UNLOCKED, entries = session.entries, busy = false)
                    }
                },
                onFailure = { error ->
                    // The wrapper no longer matches the vault on disk (a changed
                    // password, or a re-imported vault). Drop it and fall back.
                    settingsRepository.clearWrappedKey()
                    _state.update {
                        it.copy(
                            busy = false,
                            unlockError = "Biometric unlock failed; use your password. (${unlockMessage(error)})",
                        )
                    }
                },
            )
        }
    }

    fun onBiometricUnavailable(reason: String) {
        _state.update { it.copy(unlockError = reason) }
    }

    /**
     * The sealed key can no longer be opened, so throw it away.
     *
     * The Keystore key is invalidated when fingerprints or face data change,
     * which leaves a wrapper that will never unwrap again. Keeping it would
     * offer the user a biometric button that always fails; dropping it falls
     * cleanly back to the password until they turn biometrics on again.
     */
    fun discardBiometricKey(reason: String) {
        viewModelScope.launch {
            settingsRepository.clearWrappedKey()
            BiometricVaultKey.deleteKey()
            _state.update { it.copy(unlockError = reason) }
        }
    }

    fun lock() {
        session.lock()
        _state.update {
            it.copy(
                phase = if (store.exists) Phase.LOCKED else Phase.SETUP,
                entries = emptyList(),
                revealedEntryId = null,
                unlockError = null,
            )
        }
    }

    private fun unlockMessage(error: Throwable): String = when (error) {
        is VaultCrypto.BadPasswordException -> "Wrong password"
        is VaultCrypto.VaultFormatException -> "This file is not a usable qt-otp vault: ${error.message}"
        else -> error.message ?: "Could not open the vault"
    }

    // ------------------------------------------------------------- auto-locking

    /** Called just before a system file picker is launched. */
    fun onExternalPickerLaunched() {
        pickerPending = true
    }

    fun onBackgrounded() {
        if (_state.value.phase != Phase.UNLOCKED) return
        backgroundedAt = System.currentTimeMillis()
        // A picker is an errand the user started from inside the app, not them
        // walking away from it, so hold the lock until they come back.
        if (pickerPending) return
        if (_state.value.settings.autoLock == AutoLockDelay.IMMEDIATELY) lock()
    }

    fun onForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (_state.value.phase != Phase.UNLOCKED) {
            pickerPending = false
            return
        }
        if (pickerPending) {
            pickerPending = false
            // Bound the exposure anyway: a picker left open for minutes is
            // indistinguishable from having put the phone down.
            if ((System.currentTimeMillis() - since) / 1000 >= PICKER_GRACE_SECONDS) lock()
            return
        }
        val delay = _state.value.settings.autoLock
        if (delay == AutoLockDelay.NEVER) return
        val elapsed = (System.currentTimeMillis() - since) / 1000
        if (elapsed >= delay.seconds) lock()
    }

    // -------------------------------------------------------------- vault setup

    fun createVault(password: String, confirmation: String) {
        if (password.length < MIN_PASSWORD) {
            _state.update { it.copy(message = "Use a password of at least $MIN_PASSWORD characters") }
            return
        }
        if (password != confirmation) {
            _state.update { it.copy(message = "The two passwords do not match") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.Default) { runCatching { session.create(password) } }
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(phase = Phase.UNLOCKED, entries = session.entries, busy = false)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(busy = false, message = error.message ?: "Could not create the vault")
                    }
                },
            )
        }
    }

    /**
     * Copy a picked vault file in, replacing whatever is there.
     *
     * The file is validated before it lands, and any biometric wrapper is
     * dropped because it belongs to the vault being replaced.
     */
    fun importVault(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { runCatching { store.importFrom(uri) } }
            result.fold(
                onSuccess = { info ->
                    session.lock()
                    settingsRepository.clearWrappedKey()
                    _state.update {
                        it.copy(
                            phase = Phase.LOCKED,
                            entries = emptyList(),
                            busy = false,
                            unlockError = null,
                            message = "Imported a vault (scrypt cost ${info.kdfCost}). " +
                                "Unlock it with its password.",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = when (error) {
                                is VaultCrypto.VaultFormatException ->
                                    "That file is not a qt-otp vault: ${error.message}"
                                else -> error.message ?: "Could not import that file"
                            },
                        )
                    }
                },
            )
        }
    }

    fun exportVault(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { runCatching { store.exportTo(uri) } }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) {
                        "Wrote an encrypted copy of the vault"
                    } else {
                        result.exceptionOrNull()?.message ?: "Could not write the copy"
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ entries

    fun addEntry(entry: OtpEntry) = mutate("Added ${entry.label}") { session.add(entry) }

    fun updateEntry(entry: OtpEntry) = mutate("Saved ${entry.label}") { session.update(entry) }

    fun deleteEntry(entry: OtpEntry) = mutate("Deleted ${entry.label}") { session.remove(entry.id) }

    fun moveEntry(fromIndex: Int, toIndex: Int) {
        val current = _state.value.entries
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val reordered = current.toMutableList().also { it.add(toIndex, it.removeAt(fromIndex)) }
        // Show the new order at once; persist it behind the scenes.
        _state.update { it.copy(entries = reordered) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { session.reorder(reordered.map { entry -> entry.id }) }
            }
            if (result.isFailure) {
                _state.update {
                    it.copy(entries = session.entries, message = "Could not save the new order")
                }
            }
        }
    }

    private fun mutate(successMessage: String, block: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.Default) { runCatching(block) }
            _state.update {
                it.copy(
                    entries = session.entries,
                    busy = false,
                    message = if (result.isSuccess) {
                        successMessage
                    } else {
                        result.exceptionOrNull()?.message ?: "Could not save the vault"
                    },
                )
            }
        }
    }

    fun revealEntry(entryId: String?) {
        _state.update { it.copy(revealedEntryId = entryId) }
    }

    /** Copy a code, marked sensitive so it stays out of clipboard previews. */
    fun copyCode(entry: OtpEntry, code: String) {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("${entry.label} code", code).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                // Pre-33 name for the same idea, honoured by some OEM keyboards.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        _state.update {
            // Android 13+ shows its own copy confirmation; don't double up.
            it.copy(message = if (Build.VERSION.SDK_INT >= 33) null else "Code copied")
        }
    }

    // --------------------------------------------------------------- biometrics

    /** A cipher to hand to BiometricPrompt in order to seal the vault key. */
    fun biometricEnrollCipher(): Result<Cipher> = runCatching { BiometricVaultKey.encryptCipher() }

    /** A cipher to hand to BiometricPrompt in order to recover the vault key. */
    fun biometricUnlockCipher(): Result<Cipher> = runCatching {
        val iv = _state.value.settings.wrappedKeyIv
            ?: throw BiometricVaultKey.KeyInvalidatedException()
        BiometricVaultKey.decryptCipher(iv)
    }

    fun completeBiometricEnrollment(cipher: Cipher) {
        viewModelScope.launch {
            val vaultKey = withContext(Dispatchers.Default) { runCatching { session.keyCopy() } }
                .getOrElse {
                    _state.update { s -> s.copy(message = "Unlock the vault first") }
                    return@launch
                }
            val result = withContext(Dispatchers.Default) {
                runCatching { BiometricVaultKey.wrap(cipher, vaultKey) }
            }
            VaultCrypto.zeroize(vaultKey)
            result.fold(
                onSuccess = { wrapped ->
                    settingsRepository.setWrappedKey(wrapped.ciphertext, wrapped.iv)
                    _state.update { it.copy(message = "Biometric unlock is on") }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(message = error.message ?: "Could not enable biometric unlock")
                    }
                },
            )
        }
    }

    fun disableBiometrics() {
        viewModelScope.launch {
            settingsRepository.clearWrappedKey()
            BiometricVaultKey.deleteKey()
            _state.update { it.copy(message = "Biometric unlock is off") }
        }
    }

    // ----------------------------------------------------------------- settings

    fun setAutoLock(delay: AutoLockDelay) = viewModelScope.launch {
        settingsRepository.setAutoLock(delay)
    }

    fun setHideCodes(hide: Boolean) = viewModelScope.launch {
        settingsRepository.setHideCodes(hide)
        if (hide) _state.update { it.copy(revealedEntryId = null) }
    }

    fun setBlockScreenshots(block: Boolean) = viewModelScope.launch {
        settingsRepository.setBlockScreenshots(block)
    }

    fun changePassword(current: String, newPassword: String, confirmation: String) {
        if (newPassword.length < MIN_PASSWORD) {
            _state.update { it.copy(message = "Use a password of at least $MIN_PASSWORD characters") }
            return
        }
        if (newPassword != confirmation) {
            _state.update { it.copy(message = "The two new passwords do not match") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    if (!session.verifyPassword(current)) {
                        throw VaultCrypto.BadPasswordException("current password is wrong")
                    }
                    session.changePassword(newPassword)
                }
            }
            result.fold(
                onSuccess = {
                    // The sealed key was made from the old key material.
                    settingsRepository.clearWrappedKey()
                    BiometricVaultKey.deleteKey()
                    _state.update {
                        it.copy(
                            busy = false,
                            message = "Password changed. Re-enable biometric unlock if you want it.",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(busy = false, message = error.message ?: "Could not change the password")
                    }
                },
            )
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        session.lock()
        super.onCleared()
    }

    companion object {
        private const val TICK_MILLIS = 200L

        /** How long a file picker may hold the screen before auto-lock applies anyway. */
        private const val PICKER_GRACE_SECONDS = 120
        const val MIN_PASSWORD = 8

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    VaultViewModel(
                        application,
                        VaultStore(application),
                        AppSettings(application),
                    ) as T
            }
    }
}
