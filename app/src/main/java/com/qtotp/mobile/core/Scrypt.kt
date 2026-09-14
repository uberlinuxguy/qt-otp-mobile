package com.qtotp.mobile.core

/**
 * RFC 7914 scrypt.
 *
 * Implemented here rather than pulled from BouncyCastle: the vault needs
 * exactly one KDF at one set of parameters, and a self-contained implementation
 * keeps the crypto surface auditable and the APK free of a large provider that
 * would also have to be kept from clashing with the platform's own copy.
 *
 * Verified against keys derived by the desktop app's hashlib.scrypt.
 */
internal object Scrypt {

    /**
     * Derive [dkLen] bytes from [password] (UTF-8 bytes) and [salt].
     *
     * At the vault's parameters (N=32768, r=8, p=1) this allocates ~32 MiB and
     * takes a few hundred milliseconds on a phone, so call it off the main
     * thread.
     */
    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && (n and (n - 1)) == 0) { "N must be a power of two greater than 1" }
        require(r >= 1) { "r must be positive" }
        require(p >= 1) { "p must be positive" }
        require(dkLen > 0) { "dkLen must be positive" }

        val blockWords = 32 * r          // 128 * r bytes, counted in 32-bit words
        val b = Pbkdf2.hmacSha256(password, salt, 1, p * 128 * r)
        val words = IntArray(b.size / 4)
        littleEndianToInt(b, words)
        b.fill(0)

        val v = IntArray(n * blockWords)
        val x = IntArray(blockWords)
        val y = IntArray(blockWords)
        for (i in 0 until p) {
            sMix(words, i * blockWords, n, r, v, x, y)
        }

        val mixed = ByteArray(words.size * 4)
        intToLittleEndian(words, mixed)
        words.fill(0)
        v.fill(0)
        x.fill(0)
        y.fill(0)

        val key = Pbkdf2.hmacSha256(password, mixed, 1, dkLen)
        mixed.fill(0)
        return key
    }

    private fun sMix(block: IntArray, offset: Int, n: Int, r: Int, v: IntArray, x: IntArray, y: IntArray) {
        val blockWords = 32 * r
        System.arraycopy(block, offset, x, 0, blockWords)

        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * blockWords, blockWords)
            blockMix(x, y, r)
        }
        for (i in 0 until n) {
            // Integerify: the last 64-byte block of X read little-endian, mod N.
            // N is a power of two, so masking the low word is the whole story.
            val j = (x[(2 * r - 1) * 16] and (n - 1)) * blockWords
            for (k in 0 until blockWords) x[k] = x[k] xor v[j + k]
            blockMix(x, y, r)
        }
        System.arraycopy(x, 0, block, offset, blockWords)
    }

    /** BlockMix (RFC 7914 section 4): Salsa20/8 over 2r 64-byte blocks, then de-interleave. */
    private fun blockMix(b: IntArray, y: IntArray, r: Int) {
        val t = IntArray(16)
        System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i * 16 + k]
            salsa20Core8(t)
            val dest = if (i and 1 == 0) i / 2 else r + i / 2
            System.arraycopy(t, 0, y, dest * 16, 16)
        }
        System.arraycopy(y, 0, b, 0, 32 * r)
        t.fill(0)
    }

    /** The Salsa20/8 core: four double rounds over 16 words, added back to the input. */
    private fun salsa20Core8(block: IntArray) {
        var x00 = block[0]; var x01 = block[1]; var x02 = block[2]; var x03 = block[3]
        var x04 = block[4]; var x05 = block[5]; var x06 = block[6]; var x07 = block[7]
        var x08 = block[8]; var x09 = block[9]; var x10 = block[10]; var x11 = block[11]
        var x12 = block[12]; var x13 = block[13]; var x14 = block[14]; var x15 = block[15]

        var round = 0
        while (round < 8) {
            // Column rounds
            x04 = x04 xor Integer.rotateLeft(x00 + x12, 7)
            x08 = x08 xor Integer.rotateLeft(x04 + x00, 9)
            x12 = x12 xor Integer.rotateLeft(x08 + x04, 13)
            x00 = x00 xor Integer.rotateLeft(x12 + x08, 18)

            x09 = x09 xor Integer.rotateLeft(x05 + x01, 7)
            x13 = x13 xor Integer.rotateLeft(x09 + x05, 9)
            x01 = x01 xor Integer.rotateLeft(x13 + x09, 13)
            x05 = x05 xor Integer.rotateLeft(x01 + x13, 18)

            x14 = x14 xor Integer.rotateLeft(x10 + x06, 7)
            x02 = x02 xor Integer.rotateLeft(x14 + x10, 9)
            x06 = x06 xor Integer.rotateLeft(x02 + x14, 13)
            x10 = x10 xor Integer.rotateLeft(x06 + x02, 18)

            x03 = x03 xor Integer.rotateLeft(x15 + x11, 7)
            x07 = x07 xor Integer.rotateLeft(x03 + x15, 9)
            x11 = x11 xor Integer.rotateLeft(x07 + x03, 13)
            x15 = x15 xor Integer.rotateLeft(x11 + x07, 18)

            // Row rounds
            x01 = x01 xor Integer.rotateLeft(x00 + x03, 7)
            x02 = x02 xor Integer.rotateLeft(x01 + x00, 9)
            x03 = x03 xor Integer.rotateLeft(x02 + x01, 13)
            x00 = x00 xor Integer.rotateLeft(x03 + x02, 18)

            x06 = x06 xor Integer.rotateLeft(x05 + x04, 7)
            x07 = x07 xor Integer.rotateLeft(x06 + x05, 9)
            x04 = x04 xor Integer.rotateLeft(x07 + x06, 13)
            x05 = x05 xor Integer.rotateLeft(x04 + x07, 18)

            x11 = x11 xor Integer.rotateLeft(x10 + x09, 7)
            x08 = x08 xor Integer.rotateLeft(x11 + x10, 9)
            x09 = x09 xor Integer.rotateLeft(x08 + x11, 13)
            x10 = x10 xor Integer.rotateLeft(x09 + x08, 18)

            x12 = x12 xor Integer.rotateLeft(x15 + x14, 7)
            x13 = x13 xor Integer.rotateLeft(x12 + x15, 9)
            x14 = x14 xor Integer.rotateLeft(x13 + x12, 13)
            x15 = x15 xor Integer.rotateLeft(x14 + x13, 18)

            round += 2
        }

        block[0] += x00; block[1] += x01; block[2] += x02; block[3] += x03
        block[4] += x04; block[5] += x05; block[6] += x06; block[7] += x07
        block[8] += x08; block[9] += x09; block[10] += x10; block[11] += x11
        block[12] += x12; block[13] += x13; block[14] += x14; block[15] += x15
    }

    private fun littleEndianToInt(source: ByteArray, out: IntArray) {
        for (i in out.indices) {
            val o = i * 4
            out[i] = (source[o].toInt() and 0xFF) or
                ((source[o + 1].toInt() and 0xFF) shl 8) or
                ((source[o + 2].toInt() and 0xFF) shl 16) or
                ((source[o + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun intToLittleEndian(source: IntArray, out: ByteArray) {
        for (i in source.indices) {
            val value = source[i]
            val o = i * 4
            out[o] = value.toByte()
            out[o + 1] = (value ushr 8).toByte()
            out[o + 2] = (value ushr 16).toByte()
            out[o + 3] = (value ushr 24).toByte()
        }
    }
}
