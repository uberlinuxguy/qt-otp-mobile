package com.qtotp.mobile.data

import com.qtotp.mobile.core.OtpEntry
import com.qtotp.mobile.core.VaultCrypto
import com.qtotp.mobile.core.VaultPayload

/**
 * An unlocked vault held in memory, backed by a single file.
 *
 * Mirrors the desktop app's Vault class: while locked, no entries and no key
 * material are held, and every mutation is written straight through to disk so
 * a lock can never lose data.
 *
 * Every method here does real cryptography or file IO, so callers must stay off
 * the main thread.
 */
class VaultSession(private val store: VaultStore) {

    private var key: ByteArray? = null
    private var params: VaultCrypto.KdfParams? = null
    private var loaded: List<OtpEntry> = emptyList()

    val locked: Boolean
        get() = key == null

    val entries: List<OtpEntry>
        get() = loaded

    class LockedException : IllegalStateException("vault is locked")

    /** Create a brand new empty vault. Fails if one already exists. */
    fun create(password: String) {
        if (password.isEmpty()) throw IllegalArgumentException("password must not be empty")
        if (store.exists) throw IllegalStateException("a vault already exists")
        val newParams = VaultCrypto.newParams()
        key = VaultCrypto.deriveKey(password, newParams)
        params = newParams
        loaded = emptyList()
        save()
    }

    fun unlock(password: String) {
        val opened = VaultCrypto.decrypt(store.read(), password)
        adopt(opened)
    }

    /** Unlock with a key recovered from the Keystore instead of a password. */
    fun unlockWithKey(vaultKey: ByteArray) {
        val opened = VaultCrypto.decryptWithKey(store.read(), vaultKey)
        adopt(opened)
    }

    private fun adopt(opened: VaultCrypto.Opened) {
        val entries = try {
            VaultPayload.decode(opened.plaintext)
        } catch (e: Exception) {
            VaultCrypto.zeroize(opened.key)
            throw e
        } finally {
            VaultCrypto.zeroize(opened.plaintext)
        }
        key = opened.key
        params = opened.params
        loaded = entries
    }

    /** Forget the key and every plaintext secret. */
    fun lock() {
        VaultCrypto.zeroize(key)
        key = null
        params = null
        loaded = emptyList()
    }

    /** Check a password against the vault on disk without changing state. */
    fun verifyPassword(password: String): Boolean = try {
        val opened = VaultCrypto.decrypt(store.read(), password)
        VaultCrypto.zeroize(opened.key)
        VaultCrypto.zeroize(opened.plaintext)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * A copy of the vault key, for sealing into the Keystore.
     *
     * The caller owns the copy and must zeroize it; the session keeps its own.
     */
    fun keyCopy(): ByteArray = requireUnlocked().copyOf()

    fun add(entry: OtpEntry) {
        requireUnlocked()
        loaded = loaded + entry
        save()
    }

    fun update(entry: OtpEntry) {
        requireUnlocked()
        val index = loaded.indexOfFirst { it.id == entry.id }
        if (index < 0) throw NoSuchElementException("no entry with id ${entry.id}")
        loaded = loaded.toMutableList().also { it[index] = entry }
        save()
    }

    fun remove(entryId: String) {
        requireUnlocked()
        val remaining = loaded.filterNot { it.id == entryId }
        if (remaining.size == loaded.size) throw NoSuchElementException("no entry with id $entryId")
        loaded = remaining
        save()
    }

    /** Reorder to match [entryIds]; ids not mentioned keep their relative order at the end. */
    fun reorder(entryIds: List<String>) {
        requireUnlocked()
        val byId = loaded.associateBy { it.id }
        val ordered = entryIds.mapNotNull { byId[it] }
        val remainder = loaded.filterNot { entry -> entryIds.contains(entry.id) }
        loaded = ordered + remainder
        save()
    }

    /** Re-encrypt under a new password, with a fresh salt. */
    fun changePassword(newPassword: String) {
        requireUnlocked()
        if (newPassword.isEmpty()) throw IllegalArgumentException("password must not be empty")
        val oldKey = key
        val oldParams = params!!
        val newParams = VaultCrypto.rotateSalt(oldParams)
        val newKey = VaultCrypto.deriveKey(newPassword, newParams)
        key = newKey
        params = newParams
        try {
            save()
        } catch (e: Exception) {
            key = oldKey
            params = oldParams
            VaultCrypto.zeroize(newKey)
            throw e
        }
        VaultCrypto.zeroize(oldKey)
    }

    fun save() {
        val currentKey = requireUnlocked()
        val currentParams = params ?: throw LockedException()
        val plaintext = VaultPayload.encode(loaded)
        try {
            store.writeAtomic(VaultCrypto.encrypt(plaintext, currentKey, currentParams))
        } finally {
            VaultCrypto.zeroize(plaintext)
        }
    }

    private fun requireUnlocked(): ByteArray = key ?: throw LockedException()
}
