package com.qtotp.mobile.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256, spelled out rather than taken from the JCE.
 *
 * The JCE's own PBKDF2WithHmacSHA256 takes a char[] and encodes it to bytes
 * with a charset that is not contractually UTF-8 on every platform. The vault
 * key depends on the password's exact bytes, so we hand it the UTF-8 bytes
 * ourselves and keep the derivation identical to Python's hashlib.scrypt.
 */
internal object Pbkdf2 {

    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations >= 1) { "iterations must be positive" }
        require(dkLen > 0) { "dkLen must be positive" }
        // SecretKeySpec rejects a zero-length key, and an empty password can
        // never open a vault anyway (the desktop app refuses to create one).
        require(password.isNotEmpty()) { "password must not be empty" }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        val blocks = (dkLen + hLen - 1) / hLen

        var written = 0
        for (index in 1..blocks) {
            mac.update(salt)
            mac.update((index ushr 24).toByte())
            mac.update((index ushr 16).toByte())
            mac.update((index ushr 8).toByte())
            mac.update(index.toByte())
            var u = mac.doFinal()
            val t = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val take = minOf(hLen, dkLen - written)
            System.arraycopy(t, 0, out, written, take)
            written += take
        }
        return out
    }
}
