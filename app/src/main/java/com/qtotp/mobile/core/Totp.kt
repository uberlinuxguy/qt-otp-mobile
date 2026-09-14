package com.qtotp.mobile.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Raised when a shared secret is not usable base32. */
class InvalidSecretException(message: String) : Exception(message)

/**
 * RFC 4226 / RFC 6238 HOTP and TOTP.
 *
 * Deliberately dependency-free (Mac + a hand-rolled base32), mirroring the
 * desktop app so the two agree digit for digit.
 */
object Totp {

    const val DEFAULT_ALGORITHM = "SHA1"
    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD = 30
    val ALLOWED_DIGITS = intArrayOf(6, 7, 8)

    private val ALGORITHMS = mapOf(
        "SHA1" to "HmacSHA1",
        "SHA256" to "HmacSHA256",
        "SHA512" to "HmacSHA512",
    )

    val SUPPORTED_ALGORITHMS: List<String> = ALGORITHMS.keys.toList()

    fun normalizeAlgorithm(name: String?): String {
        if (name.isNullOrBlank()) return DEFAULT_ALGORITHM
        val key = name.trim().uppercase().replace("-", "")
        if (key !in ALGORITHMS) throw IllegalArgumentException("unsupported algorithm: $name")
        return key
    }

    /**
     * Canonical (uppercase, unpadded, ungrouped) base32 secret.
     *
     * Authenticator secrets get passed around with spaces, dashes and mixed
     * case; accept all of that, then prove it actually decodes.
     */
    fun normalizeSecret(secret: String?): String {
        val cleaned = clean(secret)
        if (cleaned.isEmpty()) throw InvalidSecretException("secret is empty")
        decodeSecret(cleaned)
        return cleaned
    }

    /** Decode a base32 shared secret into raw key bytes. */
    fun decodeSecret(secret: String?): ByteArray {
        val cleaned = clean(secret)
        if (cleaned.isEmpty()) throw InvalidSecretException("secret is empty")
        val key = Base32.decode(cleaned)
        if (key.isEmpty()) throw InvalidSecretException("secret decodes to zero bytes")
        return key
    }

    private fun clean(secret: String?): String =
        (secret ?: "")
            .filterNot { it.isWhitespace() }
            .replace("-", "")
            .replace("_", "")
            .uppercase()
            .trimEnd('=')

    /** RFC 4226 HOTP value as a zero-padded decimal string. */
    fun hotp(
        key: ByteArray,
        counter: Long,
        digits: Int = DEFAULT_DIGITS,
        algorithm: String = DEFAULT_ALGORITHM,
    ): String {
        require(digits in ALLOWED_DIGITS.toList()) { "digits must be one of 6, 7, 8" }
        val macName = ALGORITHMS[normalizeAlgorithm(algorithm)]!!
        val mac = Mac.getInstance(macName)
        mac.init(SecretKeySpec(key, macName))

        val message = ByteArray(8)
        for (i in 7 downTo 0) {
            message[i] = (counter ushr ((7 - i) * 8)).toByte()
        }
        val digest = mac.doFinal(message)

        val offset = (digest[digest.size - 1].toInt() and 0x0F)
        val truncated = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (truncated % modulus).toString().padStart(digits, '0')
    }

    /**
     * RFC 6238 TOTP value for [secret] at unix time [atSeconds].
     *
     * Uses floor division so the counter matches Python's integer division for
     * timestamps before the epoch as well as after.
     */
    fun totp(
        secret: String,
        atSeconds: Double,
        period: Int = DEFAULT_PERIOD,
        digits: Int = DEFAULT_DIGITS,
        algorithm: String = DEFAULT_ALGORITHM,
    ): String {
        require(period > 0) { "period must be positive" }
        val counter = Math.floorDiv(Math.floor(atSeconds).toLong(), period.toLong())
        return hotp(decodeSecret(secret), counter, digits, algorithm)
    }

    /** Seconds left in the current time step. */
    fun remainingSeconds(period: Int = DEFAULT_PERIOD, atSeconds: Double): Double {
        require(period > 0) { "period must be positive" }
        val within = atSeconds - Math.floor(atSeconds / period) * period
        return period - within
    }

    /** Group digits for readability: 123456 becomes "123 456". */
    fun formatCode(code: String): String = when (code.length) {
        8 -> code.substring(0, 4) + " " + code.substring(4)
        6, 7 -> code.substring(0, 3) + " " + code.substring(3)
        else -> code
    }
}

/** Base32 (RFC 4648) decoding with Python's padding strictness. */
internal object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // Padding count to the number of bytes that group contributes. Anything
    // else is "Incorrect padding", exactly as Python's b32decode reports.
    private val PAD_TO_BYTES = mapOf(0 to 5, 1 to 4, 3 to 3, 4 to 2, 6 to 1)

    fun encode(raw: ByteArray): String {
        val out = StringBuilder((raw.size + 4) / 5 * 8)
        var index = 0
        while (index < raw.size) {
            val chunk = ByteArray(5)
            val take = minOf(5, raw.size - index)
            System.arraycopy(raw, index, chunk, 0, take)
            var buffer = 0L
            for (b in chunk) buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            val chars = CharArray(8)
            for (i in 7 downTo 0) {
                chars[i] = ALPHABET[(buffer and 0x1F).toInt()]
                buffer = buffer shr 5
            }
            val significant = when (take) {
                1 -> 2; 2 -> 4; 3 -> 5; 4 -> 7; else -> 8
            }
            out.append(chars, 0, significant)
            index += 5
        }
        return out.toString()
    }

    fun decode(secret: String): ByteArray {
        val padding = (8 - secret.length % 8) % 8
        val padded = secret + "=".repeat(padding)

        val out = java.io.ByteArrayOutputStream(padded.length * 5 / 8)
        var position = 0
        while (position < padded.length) {
            val group = padded.substring(position, position + 8)
            var pad = 0
            while (pad < 8 && group[7 - pad] == '=') pad++
            val bytes = PAD_TO_BYTES[pad]
                ?: throw InvalidSecretException("secret is not valid base32")

            var buffer = 0L
            for (i in 0 until 8) {
                val ch = group[i]
                val value = if (ch == '=') {
                    0
                } else {
                    val v = ALPHABET.indexOf(ch)
                    if (v < 0) throw InvalidSecretException("secret is not valid base32")
                    v
                }
                buffer = (buffer shl 5) or value.toLong()
            }
            for (i in 0 until bytes) {
                out.write(((buffer shr ((4 - i) * 8)) and 0xFF).toInt())
            }
            position += 8
        }
        return out.toByteArray()
    }
}
