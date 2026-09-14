package com.qtotp.mobile.core

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interop with the desktop app, checked against a vault that qt-otp's own
 * Python code wrote (app/src/test/resources/vault.otpv) plus the values it
 * computed for that vault (expected.json).
 *
 * These are the tests that matter: if the canonical-JSON AAD, the scrypt
 * parameters or the payload shape drift, a real vault stops opening, and the
 * failure would otherwise only show up on a user's phone.
 */
class VaultInteropTest {

    private val expected: JsonObject by lazy {
        val text = readResourceText("expected.json")
        Json.parseToJsonElement(text).jsonObject
    }

    private val vaultBytes: ByteArray by lazy { readResourceBytes("vault.otpv") }

    private val password: String
        get() = expected.getValue("password").jsonPrimitive.content

    private fun readResourceBytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name).use { stream ->
            requireNotNull(stream) { "missing test resource: $name" }.readBytes()
        }

    private fun readResourceText(name: String): String =
        readResourceBytes(name).toString(Charsets.UTF_8)

    // ------------------------------------------------------------------ header

    @Test
    fun `canonical header matches the bytes python feeds to AES-GCM`() {
        val envelope = Json.parseToJsonElement(vaultBytes.toString(Charsets.UTF_8)).jsonObject
        val kdf = envelope.getValue("kdf").jsonObject
        val params = VaultCrypto.KdfParams(
            salt = Base64.getDecoder().decode(kdf.getValue("salt").jsonPrimitive.content),
            n = kdf.getValue("n").jsonPrimitive.content.toInt(),
            r = kdf.getValue("r").jsonPrimitive.content.toInt(),
            p = kdf.getValue("p").jsonPrimitive.content.toInt(),
            dkLen = kdf.getValue("dklen").jsonPrimitive.content.toInt(),
        )
        val nonce = Base64.getDecoder().decode(envelope.getValue("nonce").jsonPrimitive.content)

        val actual = VaultCrypto.canonicalHeader(params, nonce).toString(Charsets.UTF_8)
        assertEquals(expected.getValue("canonical_aad").jsonPrimitive.content, actual)
    }

    @Test
    fun `scrypt derives the same key as the desktop app`() {
        val envelope = Json.parseToJsonElement(vaultBytes.toString(Charsets.UTF_8)).jsonObject
        val kdf = envelope.getValue("kdf").jsonObject
        val params = VaultCrypto.KdfParams(
            salt = Base64.getDecoder().decode(kdf.getValue("salt").jsonPrimitive.content),
            n = kdf.getValue("n").jsonPrimitive.content.toInt(),
            r = kdf.getValue("r").jsonPrimitive.content.toInt(),
            p = kdf.getValue("p").jsonPrimitive.content.toInt(),
            dkLen = kdf.getValue("dklen").jsonPrimitive.content.toInt(),
        )

        val key = VaultCrypto.deriveKey(password, params)
        val want = Base64.getDecoder().decode(expected.getValue("derived_key_b64").jsonPrimitive.content)
        assertArrayEquals(want, key)
    }

    // ------------------------------------------------------------------ unlock

    @Test
    fun `unlocks a vault written by the desktop app`() {
        val opened = VaultCrypto.decrypt(vaultBytes, password)
        val entries = VaultPayload.decode(opened.plaintext)

        val wantEntries = expected.getValue("plaintext").jsonObject
            .getValue("entries").jsonArray
        assertEquals(wantEntries.size, entries.size)

        wantEntries.forEachIndexed { index, element ->
            val want = element.jsonObject
            val got = entries[index]
            assertEquals(want.getValue("id").jsonPrimitive.content, got.id)
            assertEquals(want.getValue("issuer").jsonPrimitive.content, got.issuer)
            assertEquals(want.getValue("account").jsonPrimitive.content, got.account)
            assertEquals(want.getValue("secret").jsonPrimitive.content, got.secret)
            assertEquals(want.getValue("digits").jsonPrimitive.content.toInt(), got.digits)
            assertEquals(want.getValue("period").jsonPrimitive.content.toInt(), got.period)
            assertEquals(want.getValue("algorithm").jsonPrimitive.content, got.algorithm)
            assertEquals(want.getValue("notes").jsonPrimitive.content, got.notes)
            assertEquals(
                want.getValue("created_at").jsonPrimitive.content.toDouble(),
                got.createdAt,
                0.0001,
            )
        }
    }

    @Test
    fun `non-ascii issuer and multiline notes survive the round trip`() {
        val entries = VaultPayload.decode(VaultCrypto.decrypt(vaultBytes, password).plaintext)
        val unicode = entries.single { it.account == "pendler" }
        assertEquals("Zürich Bahn ☕", unicode.issuer)
        assertEquals("ümlaut", unicode.notes)

        val multiline = entries.single { it.issuer == "Bank" }
        assertEquals("line1\nline2", multiline.notes)
    }

    @Test
    fun `wrong password is rejected as a bad password, not a format error`() {
        assertThrows(VaultCrypto.BadPasswordException::class.java) {
            VaultCrypto.decrypt(vaultBytes, "not the password")
        }
    }

    @Test
    fun `empty password is rejected without crashing`() {
        assertThrows(VaultCrypto.BadPasswordException::class.java) {
            VaultCrypto.decrypt(vaultBytes, "")
        }
    }

    @Test
    fun `inspect reports the header without the password`() {
        val info = VaultCrypto.inspect(vaultBytes)
        assertEquals(1, info.version)
        assertEquals("AES-256-GCM", info.cipher)
        assertEquals("scrypt", info.kdf)
        assertEquals(32768, info.kdfCost)
        assertTrue(info.ciphertextBytes > 0)
    }

    // ------------------------------------------------------- tamper resistance

    @Test
    fun `downgrading the scrypt cost in the file breaks authentication`() {
        // The KDF header is authenticated, so weakening N must not yield a
        // vault that opens with a cheaply derived key.
        val tampered = vaultBytes.toString(Charsets.UTF_8).replace("\"n\":32768", "\"n\":4096")
        assertNotEquals(vaultBytes.toString(Charsets.UTF_8), tampered)
        assertThrows(VaultCrypto.BadPasswordException::class.java) {
            VaultCrypto.decrypt(tampered.toByteArray(Charsets.UTF_8), password)
        }
    }

    @Test
    fun `a flipped ciphertext byte is caught by the GCM tag`() {
        val envelope = Json.parseToJsonElement(vaultBytes.toString(Charsets.UTF_8)).jsonObject
        val ciphertext = Base64.getDecoder()
            .decode(envelope.getValue("ciphertext").jsonPrimitive.content)
        ciphertext[5] = (ciphertext[5].toInt() xor 0x01).toByte()
        val patched = vaultBytes.toString(Charsets.UTF_8).replace(
            envelope.getValue("ciphertext").jsonPrimitive.content,
            Base64.getEncoder().encodeToString(ciphertext),
        )
        assertThrows(VaultCrypto.BadPasswordException::class.java) {
            VaultCrypto.decrypt(patched.toByteArray(Charsets.UTF_8), password)
        }
    }

    @Test
    fun `a non-vault file is refused with a format error`() {
        assertThrows(VaultCrypto.VaultFormatException::class.java) {
            VaultCrypto.inspect("just some text".toByteArray())
        }
        assertThrows(VaultCrypto.VaultFormatException::class.java) {
            VaultCrypto.inspect("""{"magic":"something-else"}""".toByteArray())
        }
    }

    // ------------------------------------------------------------------- codes

    @Test
    fun `every code matches what the desktop app computes`() {
        val entries = VaultPayload.decode(VaultCrypto.decrypt(vaultBytes, password).plaintext)
            .associateBy { it.id }

        val cases = expected.getValue("codes") as JsonArray
        assertTrue(cases.isNotEmpty())
        cases.forEach { element ->
            val case = element.jsonObject
            val entry = entries.getValue(case.getValue("id").jsonPrimitive.content)
            val at = case.getValue("at").jsonPrimitive.content.toDouble()
            assertEquals(
                "code for ${entry.label} at $at",
                case.getValue("code").jsonPrimitive.content,
                entry.code(at),
            )
        }
    }

    @Test
    fun `entry uris match the desktop app`() {
        val entries = VaultPayload.decode(VaultCrypto.decrypt(vaultBytes, password).plaintext)
            .associateBy { it.id }
        val seen = mutableSetOf<String>()
        (expected.getValue("codes") as JsonArray).forEach { element ->
            val case = element.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            if (seen.add(id)) {
                assertEquals(
                    case.getValue("uri").jsonPrimitive.content,
                    entries.getValue(id).toUri(),
                )
            }
        }
    }

    // -------------------------------------------------------------- write back

    @Test
    fun `re-encrypting produces a file this code can reopen`() {
        val opened = VaultCrypto.decrypt(vaultBytes, password)
        val entries = VaultPayload.decode(opened.plaintext)

        val rewritten = VaultCrypto.encrypt(
            VaultPayload.encode(entries, updatedAt = 1700001234.0),
            opened.key,
            opened.params,
        )
        // A fresh nonce every save means the bytes differ even for identical
        // contents; what has to hold is that it still opens.
        assertNotEquals(
            vaultBytes.toString(Charsets.UTF_8),
            rewritten.toString(Charsets.UTF_8),
        )

        val reopened = VaultPayload.decode(VaultCrypto.decrypt(rewritten, password).plaintext)
        assertEquals(entries, reopened)
    }

    @Test
    fun `a vault written here has the envelope shape the desktop app requires`() {
        val opened = VaultCrypto.decrypt(vaultBytes, password)
        val rewritten = VaultCrypto.encrypt(
            VaultPayload.encode(VaultPayload.decode(opened.plaintext)),
            opened.key,
            opened.params,
        )
        val envelope = Json.parseToJsonElement(rewritten.toString(Charsets.UTF_8)).jsonObject

        assertEquals(
            listOf("cipher", "ciphertext", "kdf", "magic", "nonce", "version"),
            envelope.keys.sorted(),
        )
        assertEquals("qt-otp-vault", envelope.getValue("magic").jsonPrimitive.content)
        assertEquals("1", envelope.getValue("version").jsonPrimitive.content)
        assertEquals("AES-256-GCM", envelope.getValue("cipher").jsonPrimitive.content)

        val kdf = envelope.getValue("kdf").jsonObject
        assertEquals(
            listOf("dklen", "n", "name", "p", "r", "salt"),
            kdf.keys.sorted(),
        )
        assertEquals("scrypt", kdf.getValue("name").jsonPrimitive.content)
        assertEquals("32768", kdf.getValue("n").jsonPrimitive.content)
        assertEquals("32", kdf.getValue("dklen").jsonPrimitive.content)
    }

    @Test
    fun `payload written here keeps the snake_case field names`() {
        val entry = OtpEntry.create(
            issuer = "Example",
            account = "user",
            secret = "JBSWY3DPEHPK3PXP",
            id = "abc123",
            createdAt = 1700000000.0,
        )
        val text = VaultPayload.encode(listOf(entry), updatedAt = 1700000000.0)
            .toString(Charsets.UTF_8)
        val payload = Json.parseToJsonElement(text).jsonObject

        assertEquals("1", payload.getValue("payload_version").jsonPrimitive.content)
        assertEquals("1700000000", payload.getValue("updated_at").jsonPrimitive.content)
        val first = payload.getValue("entries").jsonArray.single().jsonObject
        assertEquals(
            listOf("account", "algorithm", "created_at", "digits", "id", "issuer", "notes", "period", "secret"),
            first.keys.sorted(),
        )
        assertEquals("1700000000", first.getValue("created_at").jsonPrimitive.content)
    }

    @Test
    fun `changing the password rotates the salt and still round trips`() {
        val opened = VaultCrypto.decrypt(vaultBytes, password)
        val entries = VaultPayload.decode(opened.plaintext)

        val newParams = VaultCrypto.rotateSalt(opened.params)
        assertNotEquals(
            Base64.getEncoder().encodeToString(opened.params.salt),
            Base64.getEncoder().encodeToString(newParams.salt),
        )
        val newKey = VaultCrypto.deriveKey("a different password", newParams)
        val rewritten = VaultCrypto.encrypt(VaultPayload.encode(entries), newKey, newParams)

        assertEquals(
            entries,
            VaultPayload.decode(VaultCrypto.decrypt(rewritten, "a different password").plaintext),
        )
        assertThrows(VaultCrypto.BadPasswordException::class.java) {
            VaultCrypto.decrypt(rewritten, password)
        }
    }
}
