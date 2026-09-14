package com.qtotp.mobile.core

import java.io.ByteArrayOutputStream

/**
 * otpauth:// URI parsing and rendering.
 *
 * Hand-rolled rather than built on android.net.Uri so the same code runs in
 * JVM unit tests, and so the label/issuer precedence matches the desktop app's
 * urllib-based implementation exactly.
 */
object OtpAuthUri {

    private const val SCHEME = "otpauth://"

    /** Parse an otpauth://totp/... URI into a validated entry. */
    fun parse(uri: String?): OtpEntry {
        val text = (uri ?: "").trim()
        if (text.isEmpty()) throw IllegalArgumentException("empty URI")
        if (!text.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
            throw IllegalArgumentException("not an otpauth:// URI")
        }

        val rest = text.substring(SCHEME.length)
        val queryStart = rest.indexOf('?')
        val pathPart = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val queryPart = if (queryStart >= 0) rest.substring(queryStart + 1) else ""

        val slash = pathPart.indexOf('/')
        val kind = if (slash >= 0) pathPart.substring(0, slash) else pathPart
        val rawLabel = if (slash >= 0) pathPart.substring(slash + 1) else ""

        if (kind.isNotEmpty() && !kind.equals("totp", ignoreCase = true)) {
            throw IllegalArgumentException(
                "unsupported OTP type '${kind.lowercase()}' (only totp is supported)",
            )
        }

        val params = parseQuery(queryPart)
        val secret = params["secret"]
            ?: throw IllegalArgumentException("URI has no secret parameter")

        // The whole label is percent-decoded before splitting, so an issuer
        // containing an encoded colon behaves the way urllib makes it behave.
        val label = percentDecode(rawLabel, plusAsSpace = false).trimStart('/')
        var issuer = ""
        var account = label
        val colon = label.indexOf(':')
        if (colon >= 0) {
            issuer = label.substring(0, colon)
            account = label.substring(colon + 1)
        }
        // An explicit issuer parameter wins over the label prefix.
        issuer = (params["issuer"] ?: issuer).trim()

        val digits = params["digits"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("digits and period must be integers")
        } ?: Totp.DEFAULT_DIGITS
        val period = params["period"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("digits and period must be integers")
        } ?: Totp.DEFAULT_PERIOD

        if (digits !in Totp.ALLOWED_DIGITS.toList()) {
            throw IllegalArgumentException("unsupported digits value: $digits")
        }
        if (period <= 0) throw IllegalArgumentException("period must be positive")

        return OtpEntry.create(
            issuer = issuer,
            account = account.trim(),
            secret = secret,
            digits = digits,
            period = period,
            algorithm = Totp.normalizeAlgorithm(params["algorithm"]),
        )
    }

    /** True when [text] looks like an otpauth URI, for deciding what a QR code held. */
    fun looksLikeOtpAuth(text: String?): Boolean =
        (text ?: "").trim().regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)

    fun build(
        secret: String,
        issuer: String = "",
        account: String = "",
        digits: Int = Totp.DEFAULT_DIGITS,
        period: Int = Totp.DEFAULT_PERIOD,
        algorithm: String = Totp.DEFAULT_ALGORITHM,
    ): String {
        val label = if (issuer.isNotEmpty()) "$issuer:$account" else account
        val query = StringBuilder()
        query.append("secret=").append(formEncode(Totp.normalizeSecret(secret)))
        query.append("&algorithm=").append(formEncode(Totp.normalizeAlgorithm(algorithm)))
        query.append("&digits=").append(digits)
        query.append("&period=").append(period)
        if (issuer.isNotEmpty()) {
            query.append("&issuer=").append(formEncode(issuer))
        }
        return SCHEME + "totp/" + pathEncode(label) + "?" + query
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            out[percentDecode(key, plusAsSpace = true).lowercase()] =
                percentDecode(value, plusAsSpace = true)
        }
        return out
    }

    private fun percentDecode(text: String, plusAsSpace: Boolean): String {
        if ('%' !in text && !(plusAsSpace && '+' in text)) return text
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '%' && i + 2 < text.length -> {
                    val hex = text.substring(i + 1, i + 3)
                    val value = hex.toIntOrNull(16)
                    if (value == null) {
                        bytes.write(ch.code)
                        i++
                    } else {
                        bytes.write(value)
                        i += 3
                    }
                }
                plusAsSpace && ch == '+' -> {
                    bytes.write(' '.code)
                    i++
                }
                else -> {
                    // Re-encode any non-ASCII character as its UTF-8 bytes so
                    // the buffer stays a valid UTF-8 stream.
                    val chunk = text.substring(i, i + 1).toByteArray(Charsets.UTF_8)
                    bytes.write(chunk, 0, chunk.size)
                    i++
                }
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    /** Percent-encode everything unreserved, as urllib's quote(safe='') does. */
    private fun pathEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (ch in UNRESERVED) append(ch) else append(hex(byte))
        }
    }

    /** Percent-encode for a query value, with spaces as "+", as urlencode does. */
    private fun formEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            when {
                ch in UNRESERVED -> append(ch)
                ch == ' ' -> append('+')
                else -> append(hex(byte))
            }
        }
    }

    private fun hex(byte: Byte): String {
        val value = byte.toInt() and 0xFF
        return "%" + value.toString(16).uppercase().padStart(2, '0')
    }
}
