package com.qtotp.mobile.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The qt-otp vault envelope: scrypt + AES-256-GCM over a single-line JSON file.
 *
 * On-disk layout, byte-compatible with the desktop app:
 *
 *     {
 *       "magic": "qt-otp-vault",
 *       "version": 1,
 *       "cipher": "AES-256-GCM",
 *       "kdf": {"name":"scrypt","n":32768,"r":8,"p":1,"dklen":32,"salt":"<b64>"},
 *       "nonce": "<b64>",
 *       "ciphertext": "<b64>"
 *     }
 *
 * The header (everything but ciphertext) is the AES-GCM additional
 * authenticated data, so KDF parameters cannot be downgraded by editing the
 * file. Critically, the AAD is not the header's bytes as they appear on disk:
 * it is a re-serialization of the parsed header with sorted keys and no
 * whitespace, matching Python's json.dumps(sort_keys=True, separators=(",", ":")).
 * See [canonicalHeader].
 */
object VaultCrypto {

    const val MAGIC = "qt-otp-vault"
    const val VERSION = 1
    const val CIPHER = "AES-256-GCM"

    const val SCRYPT_N = 32768
    const val SCRYPT_R = 8
    const val SCRYPT_P = 1

    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    data class KdfParams(
        val salt: ByteArray,
        val n: Int = SCRYPT_N,
        val r: Int = SCRYPT_R,
        val p: Int = SCRYPT_P,
        val dkLen: Int = KEY_BYTES,
    ) {
        // A generated equals would compare the salt array by identity.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KdfParams) return false
            return salt.contentEquals(other.salt) &&
                n == other.n && r == other.r && p == other.p && dkLen == other.dkLen
        }

        override fun hashCode(): Int {
            var result = salt.contentHashCode()
            result = 31 * result + n
            result = 31 * result + r
            result = 31 * result + p
            result = 31 * result + dkLen
            return result
        }
    }

    /** What can be learned about a vault file without the password. */
    data class VaultInfo(
        val version: Int,
        val cipher: String,
        val kdf: String,
        val kdfCost: Int,
        val ciphertextBytes: Int,
    )

    class VaultFormatException(message: String) : Exception(message)

    class BadPasswordException(
        message: String = "wrong password or corrupted vault",
    ) : Exception(message)

    /** An opened vault: plaintext payload plus the key and params to save again. */
    class Opened(val plaintext: ByteArray, val key: ByteArray, val params: KdfParams)

    private class Parsed(
        val params: KdfParams,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val aad: ByteArray,
    )

    fun newParams(): KdfParams = KdfParams(salt = randomBytes(SALT_BYTES))

    fun rotateSalt(params: KdfParams): KdfParams = params.copy(salt = randomBytes(SALT_BYTES))

    fun deriveKey(password: String, params: KdfParams): ByteArray {
        if (password.isEmpty()) throw BadPasswordException("password must not be empty")
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        try {
            return Scrypt.derive(passwordBytes, params.salt, params.n, params.r, params.p, params.dkLen)
        } finally {
            passwordBytes.fill(0)
        }
    }

    /**
     * The exact bytes the desktop app feeds to AES-GCM as additional data.
     *
     * Built by hand rather than with a serializer because the key ordering, the
     * absence of whitespace, and the integer formatting are all part of the
     * contract. Keys are sorted at both levels: cipher, kdf, magic, nonce,
     * version; and inside kdf: dklen, n, name, p, r, salt.
     */
    internal fun canonicalHeader(params: KdfParams, nonce: ByteArray): ByteArray =
        buildString(224) {
            append("{")
            append(quoted("cipher")).append(":").append(quoted(CIPHER)).append(",")
            append(quoted("kdf")).append(":").append(kdfJson(params)).append(",")
            append(quoted("magic")).append(":").append(quoted(MAGIC)).append(",")
            append(quoted("nonce")).append(":").append(quoted(b64(nonce))).append(",")
            append(quoted("version")).append(":").append(VERSION)
            append("}")
        }.toByteArray(Charsets.UTF_8)

    private fun kdfJson(params: KdfParams): String = buildString(160) {
        append("{")
        append(quoted("dklen")).append(":").append(params.dkLen).append(",")
        append(quoted("n")).append(":").append(params.n).append(",")
        append(quoted("name")).append(":").append(quoted("scrypt")).append(",")
        append(quoted("p")).append(":").append(params.p).append(",")
        append(quoted("r")).append(":").append(params.r).append(",")
        append(quoted("salt")).append(":").append(quoted(b64(params.salt)))
        append("}")
    }

    /** Seal [plaintext] into vault file bytes under [key], reusing [params]. */
    fun encrypt(plaintext: ByteArray, key: ByteArray, params: KdfParams): ByteArray {
        require(key.size == KEY_BYTES) { "vault key must be 32 bytes" }
        val nonce = randomBytes(NONCE_BYTES)
        val aad = canonicalHeader(params, nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext)

        // Sorted keys and compact separators, matching how the desktop app
        // writes the file. Only the header ordering is load-bearing, but an
        // identical envelope keeps the two implementations diffable.
        return buildString(sealed.size * 2 + 256) {
            append("{")
            append(quoted("cipher")).append(":").append(quoted(CIPHER)).append(",")
            append(quoted("ciphertext")).append(":").append(quoted(b64(sealed))).append(",")
            append(quoted("kdf")).append(":").append(kdfJson(params)).append(",")
            append(quoted("magic")).append(":").append(quoted(MAGIC)).append(",")
            append(quoted("nonce")).append(":").append(quoted(b64(nonce))).append(",")
            append(quoted("version")).append(":").append(VERSION)
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    /** Describe a vault file without needing the password. */
    fun inspect(raw: ByteArray): VaultInfo {
        val parsed = parse(raw)
        return VaultInfo(
            version = VERSION,
            cipher = CIPHER,
            kdf = "scrypt",
            kdfCost = parsed.params.n,
            ciphertextBytes = parsed.ciphertext.size,
        )
    }

    /** Open vault file bytes with [password]. The key comes back so saves need no re-prompt. */
    fun decrypt(raw: ByteArray, password: String): Opened {
        val parsed = parse(raw)
        val key = deriveKey(password, parsed.params)
        val plaintext = try {
            open(parsed, key)
        } catch (e: BadPasswordException) {
            key.fill(0)
            throw e
        }
        return Opened(plaintext, key, parsed.params)
    }

    /** Open vault file bytes with an already-derived [key], as biometric unlock does. */
    fun decryptWithKey(raw: ByteArray, key: ByteArray): Opened {
        val parsed = parse(raw)
        return Opened(open(parsed, key), key, parsed.params)
    }

    private fun open(parsed: Parsed, key: ByteArray): ByteArray {
        if (key.size != KEY_BYTES) throw BadPasswordException("vault key must be 32 bytes")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, parsed.nonce),
            )
            cipher.updateAAD(parsed.aad)
            cipher.doFinal(parsed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw BadPasswordException()
        } catch (e: BadPaddingException) {
            // Some providers surface a bad tag as the more general exception.
            throw BadPasswordException()
        }
    }

    private fun parse(raw: ByteArray): Parsed {
        val envelope = try {
            json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            throw VaultFormatException("file is not valid JSON")
        } ?: throw VaultFormatException("vault has an unexpected shape")

        if (envelope.string("magic") != MAGIC) {
            throw VaultFormatException("file is not a qt-otp vault")
        }
        val version = envelope.wholeNumber("version")
        if (version != VERSION) {
            throw VaultFormatException("unsupported vault version: $version")
        }
        val cipher = envelope.string("cipher")
        if (cipher != CIPHER) {
            throw VaultFormatException("unsupported cipher: $cipher")
        }
        if (!envelope.containsKey("nonce") || !envelope.containsKey("ciphertext")) {
            throw VaultFormatException("vault is missing required fields")
        }

        val params = parseKdf(envelope["kdf"])
        val nonce = b64d(envelope.string("nonce"))
        if (nonce.size != NONCE_BYTES) throw VaultFormatException("bad nonce length")
        val ciphertext = b64d(envelope.string("ciphertext"))

        return Parsed(params, nonce, ciphertext, canonicalHeader(params, nonce))
    }

    private fun parseKdf(element: JsonElement?): KdfParams {
        val obj = element as? JsonObject ?: throw VaultFormatException("missing kdf section")

        val name = obj.string("name")
        if (name != "scrypt") throw VaultFormatException("unsupported kdf: $name")

        val salt = b64d(obj.string("salt"))
        val n = obj.wholeNumber("n") ?: throw VaultFormatException("malformed kdf parameters")
        val r = obj.wholeNumber("r") ?: throw VaultFormatException("malformed kdf parameters")
        val p = obj.wholeNumber("p") ?: throw VaultFormatException("malformed kdf parameters")
        val dkLen = obj.wholeNumber("dklen") ?: throw VaultFormatException("malformed kdf parameters")
        val params = KdfParams(salt = salt, n = n, r = r, p = p, dkLen = dkLen)

        // Mirror the desktop app's sanity checks, so a tampered file is
        // rejected here rather than quietly derived at a weakened cost.
        if (params.dkLen != KEY_BYTES) throw VaultFormatException("unsupported key length")
        if (params.n < 4096 || (params.n and (params.n - 1)) != 0) {
            throw VaultFormatException("implausible scrypt cost parameter")
        }
        if (params.r !in 1..64 || params.p !in 1..16) {
            throw VaultFormatException("implausible scrypt parameters")
        }
        if (params.salt.size < 8) throw VaultFormatException("salt too short")
        return params
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number that is an exact integer, rejecting strings and 1.5 alike. */
    private fun JsonObject.wholeNumber(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toIntOrNull()
    }

    private fun quoted(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun b64(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    private fun b64d(text: String?): ByteArray {
        if (text == null) throw VaultFormatException("malformed base64 field")
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw VaultFormatException("malformed base64 field")
        }
    }

    /** Best-effort wipe of a mutable secret buffer. */
    fun zeroize(buffer: ByteArray?) {
        buffer?.fill(0)
    }
}
