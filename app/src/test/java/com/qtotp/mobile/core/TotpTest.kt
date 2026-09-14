package com.qtotp.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOTP against the RFC 6238 published vectors, so correctness is anchored to
 * the spec and not only to agreement with the desktop app.
 */
class TotpTest {

    // Base32 of the RFC 6238 seeds: 20, 32 and 64 ASCII bytes of "1234567890...".
    private val sha1Seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val sha256Seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
    private val sha512Seed =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA"

    @Test
    fun `rfc 6238 test vectors`() {
        val cases = listOf(
            Triple(59.0, "SHA1", "94287082"),
            Triple(59.0, "SHA256", "46119246"),
            Triple(59.0, "SHA512", "90693936"),
            Triple(1111111109.0, "SHA1", "07081804"),
            Triple(1111111109.0, "SHA256", "68084774"),
            Triple(1111111109.0, "SHA512", "25091201"),
            Triple(1111111111.0, "SHA1", "14050471"),
            Triple(1111111111.0, "SHA256", "67062674"),
            Triple(1111111111.0, "SHA512", "99943326"),
            Triple(1234567890.0, "SHA1", "89005924"),
            Triple(1234567890.0, "SHA256", "91819424"),
            Triple(1234567890.0, "SHA512", "93441116"),
            Triple(2000000000.0, "SHA1", "69279037"),
            Triple(2000000000.0, "SHA256", "90698825"),
            Triple(2000000000.0, "SHA512", "38618901"),
            Triple(20000000000.0, "SHA1", "65353130"),
            Triple(20000000000.0, "SHA256", "77737706"),
            Triple(20000000000.0, "SHA512", "47863826"),
        )
        cases.forEach { (at, algorithm, want) ->
            val secret = when (algorithm) {
                "SHA1" -> sha1Seed
                "SHA256" -> sha256Seed
                else -> sha512Seed
            }
            assertEquals(
                "$algorithm at $at",
                want,
                Totp.totp(secret, at, period = 30, digits = 8, algorithm = algorithm),
            )
        }
    }

    @Test
    fun `secret normalization accepts the shapes people paste`() {
        assertEquals("JBSWY3DPEHPK3PXP", Totp.normalizeSecret("jbswy3dp ehpk3pxp"))
        assertEquals("JBSWY3DPEHPK3PXP", Totp.normalizeSecret("JBSW-Y3DP-EHPK-3PXP"))
        assertEquals("JBSWY3DPEHPK3PXP", Totp.normalizeSecret("JBSWY3DPEHPK3PXP===="))
        assertEquals("JBSWY3DPEHPK3PXP", Totp.normalizeSecret("  JBSWY3DPEHPK3PXP  "))
    }

    @Test
    fun `unusable secrets are rejected`() {
        assertThrows(InvalidSecretException::class.java) { Totp.normalizeSecret("") }
        assertThrows(InvalidSecretException::class.java) { Totp.normalizeSecret("   ") }
        assertThrows(InvalidSecretException::class.java) { Totp.normalizeSecret("not!base32") }
        // '1' and '8' are outside the base32 alphabet.
        assertThrows(InvalidSecretException::class.java) { Totp.normalizeSecret("JBSW18") }
        // A single character cannot form a whole byte.
        assertThrows(InvalidSecretException::class.java) { Totp.normalizeSecret("A") }
    }

    @Test
    fun `base32 round trips`() {
        for (size in 1..40) {
            val raw = ByteArray(size) { (it * 7 + 3).toByte() }
            val encoded = Base32.encode(raw)
            assertEquals("size $size", raw.toList(), Base32.decode(encoded).toList())
        }
    }

    @Test
    fun `known base32 decodes to the expected bytes`() {
        assertEquals(
            "Hello!".toByteArray(Charsets.UTF_8).toList(),
            Base32.decode("JBSWY3DPEE").toList(),
        )
    }

    @Test
    fun `algorithm normalization matches the desktop app`() {
        assertEquals("SHA1", Totp.normalizeAlgorithm(null))
        assertEquals("SHA1", Totp.normalizeAlgorithm(""))
        assertEquals("SHA256", Totp.normalizeAlgorithm("sha-256"))
        assertEquals("SHA512", Totp.normalizeAlgorithm(" SHA512 "))
        assertThrows(IllegalArgumentException::class.java) { Totp.normalizeAlgorithm("MD5") }
    }

    @Test
    fun `remaining seconds counts down within the period`() {
        assertEquals(30.0, Totp.remainingSeconds(30, 0.0), 1e-9)
        assertEquals(1.0, Totp.remainingSeconds(30, 29.0), 1e-9)
        assertEquals(30.0, Totp.remainingSeconds(30, 30.0), 1e-9)
        assertEquals(35.5, Totp.remainingSeconds(60, 1044.5), 1e-9)
    }

    @Test
    fun `codes are grouped for reading`() {
        assertEquals("123 456", Totp.formatCode("123456"))
        assertEquals("1234 5678", Totp.formatCode("12345678"))
        assertEquals("123 4567", Totp.formatCode("1234567"))
    }

    @Test
    fun `digit counts outside 6 to 8 are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            Totp.totp(sha1Seed, 0.0, digits = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Totp.totp(sha1Seed, 0.0, period = 0)
        }
    }

    // ------------------------------------------------------------------- uris

    @Test
    fun `parses a standard otpauth uri`() {
        val entry = OtpAuthUri.parse(
            "otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=6&period=30",
        )
        assertEquals("GitHub", entry.issuer)
        assertEquals("octocat", entry.account)
        assertEquals("JBSWY3DPEHPK3PXP", entry.secret)
        assertEquals(6, entry.digits)
        assertEquals(30, entry.period)
        assertEquals("SHA1", entry.algorithm)
    }

    @Test
    fun `issuer parameter wins over the label prefix`() {
        val entry = OtpAuthUri.parse(
            "otpauth://totp/Ignored:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Real",
        )
        assertEquals("Real", entry.issuer)
        assertEquals("user@example.com", entry.account)
    }

    @Test
    fun `label without an issuer becomes the account`() {
        val entry = OtpAuthUri.parse("otpauth://totp/justme?secret=JBSWY3DPEHPK3PXP")
        assertEquals("", entry.issuer)
        assertEquals("justme", entry.account)
    }

    @Test
    fun `percent encoded label and unicode issuer decode`() {
        val entry = OtpAuthUri.parse(
            "otpauth://totp/Z%C3%BCrich%20Bahn%20%E2%98%95%3Apendler" +
                "?secret=JBSWY3DPEHPK3PXP&issuer=Z%C3%BCrich+Bahn+%E2%98%95",
        )
        assertEquals("Zürich Bahn ☕", entry.issuer)
        assertEquals("pendler", entry.account)
    }

    @Test
    fun `uri round trips through build and parse`() {
        val original = OtpEntry.create(
            issuer = "Zürich Bahn ☕",
            account = "pendler",
            secret = "JBSWY3DPEHPK3PXP",
            digits = 8,
            period = 60,
            algorithm = "SHA256",
        )
        val parsed = OtpAuthUri.parse(original.toUri())
        assertEquals(original.issuer, parsed.issuer)
        assertEquals(original.account, parsed.account)
        assertEquals(original.secret, parsed.secret)
        assertEquals(original.digits, parsed.digits)
        assertEquals(original.period, parsed.period)
        assertEquals(original.algorithm, parsed.algorithm)
    }

    @Test
    fun `bad uris are refused`() {
        assertThrows(IllegalArgumentException::class.java) { OtpAuthUri.parse("") }
        assertThrows(IllegalArgumentException::class.java) { OtpAuthUri.parse("https://example.com") }
        // hotp is not supported, matching the desktop app.
        assertThrows(IllegalArgumentException::class.java) {
            OtpAuthUri.parse("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OtpAuthUri.parse("otpauth://totp/x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OtpAuthUri.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=9")
        }
    }

    @Test
    fun `recognizes otpauth text from a qr code`() {
        assertTrue(OtpAuthUri.looksLikeOtpAuth("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP"))
        assertTrue(OtpAuthUri.looksLikeOtpAuth("  OTPAUTH://TOTP/a?secret=JBSWY3DPEHPK3PXP"))
        assertFalse(OtpAuthUri.looksLikeOtpAuth("https://example.com"))
        assertFalse(OtpAuthUri.looksLikeOtpAuth(null))
    }

    @Test
    fun `an entry needs an issuer or an account`() {
        assertThrows(IllegalArgumentException::class.java) {
            OtpEntry.create(secret = "JBSWY3DPEHPK3PXP")
        }
    }

    @Test
    fun `label matches the desktop rendering`() {
        assertEquals(
            "GitHub — octocat",
            OtpEntry.create(issuer = "GitHub", account = "octocat", secret = "JBSWY3DPEHPK3PXP").label,
        )
        assertEquals(
            "GitHub",
            OtpEntry.create(issuer = "GitHub", secret = "JBSWY3DPEHPK3PXP").label,
        )
        assertEquals(
            "octocat",
            OtpEntry.create(account = "octocat", secret = "JBSWY3DPEHPK3PXP").label,
        )
    }
}
