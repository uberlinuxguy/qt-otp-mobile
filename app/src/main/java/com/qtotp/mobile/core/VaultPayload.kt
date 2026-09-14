package com.qtotp.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The decrypted vault contents.
 *
 * Plaintext layout, as written by the desktop app:
 *
 *     {"payload_version":1,"updated_at":1700000000.0,"entries":[{...}]}
 *
 * Field names are snake_case on the wire and stay that way; the entry objects
 * carry id, issuer, account, secret, digits, period, algorithm, notes and
 * created_at.
 */
object VaultPayload {

    const val PAYLOAD_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(plaintext: ByteArray): List<OtpEntry> {
        val payload = try {
            json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            throw VaultCrypto.VaultFormatException("vault contents are not valid JSON")
        } ?: throw VaultCrypto.VaultFormatException("vault contents have an unexpected shape")

        val version = (payload["payload_version"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.content
            ?.toIntOrNull()
        if (version != PAYLOAD_VERSION) {
            throw VaultCrypto.VaultFormatException("unsupported payload version: $version")
        }

        val rawEntries = payload["entries"]
        if (rawEntries != null && rawEntries !is JsonArray) {
            throw VaultCrypto.VaultFormatException("vault entries are not a list")
        }

        return (rawEntries as? JsonArray).orEmpty().map { element ->
            val obj = element as? JsonObject
                ?: throw VaultCrypto.VaultFormatException("vault entry is not an object")
            try {
                OtpEntry.create(
                    issuer = obj.text("issuer"),
                    account = obj.text("account"),
                    secret = obj.text("secret"),
                    digits = obj.number("digits")?.toInt() ?: Totp.DEFAULT_DIGITS,
                    period = obj.number("period")?.toInt() ?: Totp.DEFAULT_PERIOD,
                    algorithm = obj.text("algorithm").ifEmpty { Totp.DEFAULT_ALGORITHM },
                    notes = obj.text("notes"),
                    id = obj.text("id"),
                    createdAt = obj.number("created_at") ?: (System.currentTimeMillis() / 1000.0),
                )
            } catch (e: VaultCrypto.VaultFormatException) {
                throw e
            } catch (e: Exception) {
                throw VaultCrypto.VaultFormatException(
                    "vault entry is unusable: ${e.message ?: e::class.java.simpleName}",
                )
            }
        }
    }

    fun encode(entries: List<OtpEntry>, updatedAt: Double = System.currentTimeMillis() / 1000.0): ByteArray {
        val payload = buildJsonObject {
            put("payload_version", PAYLOAD_VERSION)
            put("updated_at", numeric(updatedAt))
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("id", entry.id)
                                put("issuer", entry.issuer)
                                put("account", entry.account)
                                put("secret", entry.secret)
                                put("digits", entry.digits)
                                put("period", entry.period)
                                put("algorithm", entry.algorithm)
                                put("notes", entry.notes)
                                put("created_at", numeric(entry.createdAt))
                            },
                        )
                    }
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), payload).toByteArray(Charsets.UTF_8)
    }

    /**
     * Write whole-second timestamps as integers.
     *
     * Kotlin renders 1.7e9 as "1.7E9", which is legal JSON and which Python
     * reads back correctly, but a plain integer keeps the file readable and
     * matches what the desktop app produces for freshly added entries.
     */
    private fun numeric(value: Double): JsonPrimitive =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 9.0e15) {
            JsonPrimitive(value.toLong())
        } else {
            JsonPrimitive(value)
        }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()
}
