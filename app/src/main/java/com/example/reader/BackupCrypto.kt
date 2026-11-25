package com.example.reader

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption for backups. The whole .zip is encrypted, so file names are hidden too.
 *
 * Layout (all numbers big-endian):
 *   header  "MLBK" | version 1 (1 byte) | kdf 1 = PBKDF2-HMAC-SHA256 (1) | iterations (4) |
 *           salt (16) | chunk size (4) | base nonce (12)
 *   body    chunks of AES-256-GCM, each holding up to `chunk size` bytes plus a 16-byte tag.
 *           nonce = base nonce XOR chunk index (in the last 8 bytes);
 *           associated data = header | chunk index (8) | 1 if this is the last chunk, else 0.
 *
 * The last-chunk flag means a file cut short is detected rather than silently accepted. Every
 * primitive is also in the browser's Web Crypto API, so a page could read these files too.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val KDF_PBKDF2: Byte = 1
    private const val ITERATIONS = 600_000
    private const val CHUNK = 1 shl 20
    private const val TAG_BYTES = 16
    const val HEADER_BYTES = 4 + 1 + 1 + 4 + 16 + 4 + 12

    class WrongPasswordException : IOException("Incorrect password (or the backup is damaged).")
    class DamagedException(message: String) : IOException(message)

    /** True when [first] (the first bytes of a file) starts an encrypted backup. */
    fun isEncrypted(first: ByteArray): Boolean = first.size >= 4 && first.copyOf(4).contentEquals(MAGIC)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(base: ByteArray, index: Long): ByteArray {
        val n = base.copyOf()
        for (k in 0 until 8) n[11 - k] = (n[11 - k].toInt() xor ((index ushr (8 * k)) and 0xFF).toInt()).toByte()
        return n
    }

    private fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 9).put(header).putLong(index).put((if (last) 1 else 0).toByte()).array()

    /** Encrypts everything written to it; [close] writes the final chunk. */
    class EncryptingOutputStream(out: OutputStream, password: CharArray) : FilterOutputStream(out) {
        private val header: ByteArray
        private val key: SecretKeySpec
        private val baseNonce = ByteArray(12)
        private val buf = ByteArray(CHUNK)
        private var pos = 0
        private var index = 0L
        private var closed = false

        init {
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            random.nextBytes(baseNonce)
            header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION).put(KDF_PBKDF2).putInt(ITERATIONS)
                .put(salt).putInt(CHUNK).put(baseNonce).array()
            key = key(password, salt, ITERATIONS)
            out.write(header)
        }

        private fun seal(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(baseNonce, index)))
            c.updateAAD(aad(header, index, last))
            out.write(c.doFinal(buf, 0, pos))
            pos = 0
            index++
        }

        override fun write(b: Int) {
            if (pos == CHUNK) seal(false)
            buf[pos++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var left = len
            while (left > 0) {
                if (pos == CHUNK) seal(false)
                val n = minOf(left, CHUNK - pos)
                System.arraycopy(b, o, buf, pos, n)
                pos += n; o += n; left -= n
            }
        }

        override fun flush() { out.flush() }

        override fun close() {
            if (closed) return
            closed = true
            try { seal(true); out.flush() } finally { out.close() }
        }
    }

    /** Decrypts an encrypted backup. Throws [WrongPasswordException] or [DamagedException]. */
    class DecryptingInputStream(private val input: InputStream, password: CharArray) : InputStream() {
        private val header = ByteArray(HEADER_BYTES)
        private val key: SecretKeySpec
        private val baseNonce: ByteArray
        private val chunk: Int
        private var plain = ByteArray(0)
        private var plainPos = 0
        private var index = 0L
        private var finished = false
        private var peeked = -1

        init {
            if (readFully(input, header, 0, HEADER_BYTES) != HEADER_BYTES || !isEncrypted(header)) throw DamagedException("This isn't an encrypted backup.")
            val bb = ByteBuffer.wrap(header, 4, HEADER_BYTES - 4)
            val version = bb.get(); val kdf = bb.get(); val iterations = bb.getInt()
            if (version != VERSION || kdf != KDF_PBKDF2) throw DamagedException("This backup was encrypted by a newer version of the app.")
            if (iterations !in 10_000..10_000_000) throw DamagedException("The backup's header is damaged.")
            val salt = ByteArray(16).also { bb.get(it) }
            chunk = bb.getInt()
            if (chunk !in 1024..(16 shl 20)) throw DamagedException("The backup's header is damaged.")
            baseNonce = ByteArray(12).also { bb.get(it) }
            key = key(password, salt, iterations)
        }

        private fun next(): Boolean {
            if (finished) return false
            val block = ByteArray(chunk + TAG_BYTES)
            var got = 0
            if (peeked >= 0) { block[0] = peeked.toByte(); got = 1; peeked = -1 }
            got += readFully(input, block, got, block.size - got)
            val last = if (got < block.size) true else input.read().let { if (it < 0) true else { peeked = it; false } }
            if (got < TAG_BYTES) throw DamagedException("The backup is incomplete (it may have been cut off).")
            fun open(asLast: Boolean): ByteArray? = try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(baseNonce, index)))
                c.updateAAD(aad(header, index, asLast))
                c.doFinal(block, 0, got)
            } catch (_: AEADBadTagException) { null }
            plain = open(last) ?: run {
                // The right password but the wrong last-chunk flag means the file was cut short.
                if (last && open(false) != null) throw DamagedException("The backup is incomplete (it may have been cut off).")
                if (index == 0L) throw WrongPasswordException()
                throw DamagedException("The backup is damaged.")
            }
            plainPos = 0
            index++
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            while (plainPos >= plain.size) if (!next()) return -1
            return plain[plainPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (plainPos >= plain.size) if (!next()) return -1
            val n = minOf(len, plain.size - plainPos)
            System.arraycopy(plain, plainPos, b, off, n)
            plainPos += n
            return n
        }

        override fun close() = input.close()
    }

    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(b, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Small helper for tests: encrypt and decrypt a byte array. */
    internal fun roundTrip(data: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        EncryptingOutputStream(out, password.toCharArray()).use { it.write(data) }
        return DecryptingInputStream(out.toByteArray().inputStream(), password.toCharArray()).use { it.readBytes() }
    }
}
