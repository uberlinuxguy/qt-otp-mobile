package com.qtotp.mobile.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emits vault files written by this code so the desktop app's own Python can be
 * pointed at them.
 *
 * Reading a desktop vault is only half of compatibility; the interesting half is
 * whether a vault saved on the phone still opens on the desktop. This test
 * writes the artifacts and tools/verify_interop.py does the opening.
 */
class WriteBackFixtureTest {

    private val outputDir = File("build/interop").apply { mkdirs() }

    @Test
    fun `emit a re-encrypted copy of the desktop vault`() {
        val original = javaClass.classLoader!!.getResourceAsStream("vault.otpv")!!.readBytes()
        val opened = VaultCrypto.decrypt(original, PASSWORD)
        val entries = VaultPayload.decode(opened.plaintext)

        val rewritten = VaultCrypto.encrypt(
            VaultPayload.encode(entries, updatedAt = 1700009999.0),
            opened.key,
            opened.params,
        )
        File(outputDir, "rewritten.otpv").writeBytes(rewritten)
        assertTrue(File(outputDir, "rewritten.otpv").length() > 0)
    }

    @Test
    fun `emit a vault created from scratch on this side`() {
        // A brand new vault under a fresh password and salt, holding entries
        // added the way the phone would add them.
        val params = VaultCrypto.newParams()
        val key = VaultCrypto.deriveKey(NEW_PASSWORD, params)
        val entries = listOf(
            OtpEntry.create(
                issuer = "Made On Android",
                account = "someone@example.com",
                secret = "JBSWY3DPEHPK3PXP",
                id = "aaaa0000000000000000000000000001",
                createdAt = 1700100000.0,
            ),
            OtpEntry.create(
                issuer = "Ålesund Kraft ⚡",
                account = "meter-42",
                secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                digits = 8,
                period = 60,
                algorithm = "SHA512",
                notes = "scanned from a QR code\nsecond line",
                id = "aaaa0000000000000000000000000002",
                createdAt = 1700100001.5,
            ),
        )
        val raw = VaultCrypto.encrypt(VaultPayload.encode(entries, updatedAt = 1700100002.0), key, params)
        File(outputDir, "created.otpv").writeBytes(raw)
        assertTrue(File(outputDir, "created.otpv").length() > 0)
    }

    companion object {
        const val PASSWORD = "correct horse battery staple"
        const val NEW_PASSWORD = "a phone-side password"
    }
}
