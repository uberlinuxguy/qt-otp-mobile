package com.qtotp.mobile.data

import android.content.Context
import android.net.Uri
import com.qtotp.mobile.core.VaultCrypto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where the vault file lives on the device, and how it gets on and off it.
 *
 * The vault sits in the app's private files directory, which no other app can
 * read, and which is excluded from cloud backup and device transfer (see
 * res/xml/data_extraction_rules.xml). Importing copies the bytes in and leaves
 * the source untouched, matching the desktop app's import: bringing a vault
 * over from a backup or a USB stick should not consume it.
 */
class VaultStore(private val context: Context) {

    val vaultFile: File
        get() = File(context.filesDir, VAULT_FILENAME)

    val exists: Boolean
        get() = vaultFile.isFile

    fun read(): ByteArray = vaultFile.readBytes()

    /**
     * Replace the vault file, via a temporary file so a failed or interrupted
     * write can never leave a half-written vault where the real one was.
     */
    fun writeAtomic(bytes: ByteArray) {
        val target = vaultFile
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (!temp.renameTo(target)) {
                // renameTo will not clobber on every filesystem; fall back to
                // an explicit delete, still from the fully written temp file.
                if (!target.delete() && target.exists()) {
                    throw IOException("could not replace the existing vault file")
                }
                if (!temp.renameTo(target)) {
                    throw IOException("could not move the new vault into place")
                }
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Read a document the user picked, without writing anything yet. */
    fun readExternal(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("could not open the selected file")

    /**
     * Validate a picked file and copy it in as the app's vault.
     *
     * The file is inspected first so a wrong pick is refused with a clear
     * reason instead of replacing a good vault with something unusable.
     */
    fun importFrom(uri: Uri): VaultCrypto.VaultInfo {
        val raw = readExternal(uri)
        val info = VaultCrypto.inspect(raw)
        writeAtomic(raw)
        return info
    }

    /** Write an encrypted copy of the current vault to a document the user picked. */
    fun exportTo(uri: Uri) {
        val raw = read()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(raw) }
            ?: throw IOException("could not write to the selected location")
    }

    /** Describe a picked file without importing it. */
    fun inspectExternal(uri: Uri): VaultCrypto.VaultInfo = VaultCrypto.inspect(readExternal(uri))

    fun delete(): Boolean = vaultFile.delete()

    companion object {
        const val VAULT_FILENAME = "vault.otpv"
    }
}
