package cloud.pocket.drive.data.vault

import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The data could not be decrypted: wrong password, or not a file made by rclone's crypt. */
class CryptException(message: String) : IOException(message)

/**
 * rclone's "crypt" format, so a Vault can also be opened on a computer with plain rclone:
 *  - file contents: a header (magic and a random 24-byte nonce), then 64 KiB blocks sealed with XSalsa20-Poly1305;
 *  - names: each path segment is padded, enciphered with AES in EME mode, and written in lowercase base32;
 *  - keys: scrypt of the password (and salt) into 80 bytes: data key, name key, name tweak.
 * The password is the plain one (rclone's config stores it "obscured", which is only a disguise).
 */
class Crypt(password: String, salt: String = "") {
    private val dataKey = ByteArray(32)
    private val nameKey = ByteArray(32)
    private val nameTweak = ByteArray(16)
    private val random = SecureRandom()

    init {
        val material = if (password.isEmpty()) ByteArray(80)
        else SCrypt.generate(password.toByteArray(Charsets.UTF_8), if (salt.isEmpty()) DEFAULT_SALT else salt.toByteArray(Charsets.UTF_8), 16384, 8, 1, 80)
        material.copyInto(dataKey, 0, 0, 32)
        material.copyInto(nameKey, 0, 32, 64)
        material.copyInto(nameTweak, 0, 64, 80)
    }

    // ---------------------------------------------------------------- names

    /** "a/b.txt" -> the same path with every segment enciphered. */
    fun encryptPath(path: String): String = path.split('/').joinToString("/") { encryptSegment(it) }

    /** The plain path of an enciphered one, or null when any segment can't be decrypted. */
    fun decryptPath(path: String): String? {
        val parts = path.split('/').map { decryptSegment(it) ?: return null }
        return parts.joinToString("/")
    }

    fun encryptSegment(segment: String): String {
        if (segment.isEmpty()) return ""
        val padded = pad(segment.toByteArray(Charsets.UTF_8))
        return Base32.encode(eme(padded, encrypt = true))
    }

    fun decryptSegment(segment: String): String? {
        if (segment.isEmpty()) return ""
        val raw = Base32.decode(segment) ?: return null
        if (raw.isEmpty() || raw.size % 16 != 0 || raw.size > 16 * 128) return null
        val plain = unpad(eme(raw, encrypt = false)) ?: return null
        return String(plain, Charsets.UTF_8)
    }

    private fun pad(data: ByteArray): ByteArray {
        val n = 16 - data.size % 16
        return data + ByteArray(n) { n.toByte() }
    }

    private fun unpad(data: ByteArray): ByteArray? {
        if (data.isEmpty() || data.size % 16 != 0) return null
        val n = data.last().toInt() and 0xff
        if (n == 0 || n > 16 || n > data.size) return null
        for (i in data.size - n until data.size) if ((data[i].toInt() and 0xff) != n) return null
        return data.copyOf(data.size - n)
    }

    // EME-32 (ECB-Mix-ECB) as in github.com/rfjakob/eme, which rclone uses for names
    private fun eme(input: ByteArray, encrypt: Boolean): ByteArray {
        val m = input.size / 16
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        val key = SecretKeySpec(nameKey, "AES")
        fun ecb(data: ByteArray, enc: Boolean): ByteArray {
            aes.init(if (enc) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key)
            return aes.doFinal(data)
        }
        // L table: 2*E(0), 4*E(0), ...
        val lTable = Array(m) { ByteArray(16) }
        var li = ecb(ByteArray(16), true)
        for (i in 0 until m) { li = multByTwo(li); lTable[i] = li }

        val c = ByteArray(input.size)
        val block = ByteArray(16)
        for (j in 0 until m) {
            for (k in 0 until 16) block[k] = (input[j * 16 + k].toInt() xor lTable[j][k].toInt()).toByte()
            ecb(block, encrypt).copyInto(c, j * 16)
        }
        val mp = ByteArray(16)
        for (k in 0 until 16) mp[k] = (c[k].toInt() xor nameTweak[k].toInt()).toByte()
        for (j in 1 until m) for (k in 0 until 16) mp[k] = (mp[k].toInt() xor c[j * 16 + k].toInt()).toByte()
        val mc = ecb(mp, encrypt)
        var mm = ByteArray(16) { (mp[it].toInt() xor mc[it].toInt()).toByte() }
        for (j in 1 until m) {
            mm = multByTwo(mm)
            for (k in 0 until 16) c[j * 16 + k] = (c[j * 16 + k].toInt() xor mm[k].toInt()).toByte()
        }
        val ccc1 = ByteArray(16) { (mc[it].toInt() xor nameTweak[it].toInt()).toByte() }
        for (j in 1 until m) for (k in 0 until 16) ccc1[k] = (ccc1[k].toInt() xor c[j * 16 + k].toInt()).toByte()
        ccc1.copyInto(c, 0)
        for (j in 0 until m) {
            val e = ecb(c.copyOfRange(j * 16, j * 16 + 16), encrypt)
            for (k in 0 until 16) c[j * 16 + k] = (e[k].toInt() xor lTable[j][k].toInt()).toByte()
        }
        return c
    }

    private fun multByTwo(inp: ByteArray): ByteArray {
        val out = ByteArray(16)
        out[0] = (2 * (inp[0].toInt() and 0xff)).toByte()
        if ((inp[15].toInt() and 0xff) >= 128) out[0] = (out[0].toInt() xor 135).toByte()
        for (j in 1 until 16) {
            out[j] = (2 * (inp[j].toInt() and 0xff)).toByte()
            if ((inp[j - 1].toInt() and 0xff) >= 128) out[j] = (out[j] + 1).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------- contents

    fun newNonce(): ByteArray = ByteArray(NONCE).also { random.nextBytes(it) }

    fun header(nonce: ByteArray): ByteArray = MAGIC + nonce

    /** Seal one block of plain data (up to 64 KiB) as block number [index] of a file with this [nonce]. */
    fun sealBlock(nonce: ByteArray, index: Long, plain: ByteArray): ByteArray {
        val n = blockNonce(nonce, index)
        val engine = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(dataKey), n)) }
        val input = ByteArray(32 + plain.size)
        plain.copyInto(input, 32)
        val stream = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, stream, 0)
        val ciphertext = stream.copyOfRange(32, stream.size)
        val tag = poly1305(stream.copyOfRange(0, 32), ciphertext)
        return tag + ciphertext
    }

    /** Open a sealed block, or fail with [CryptException]. */
    fun openBlock(nonce: ByteArray, index: Long, sealed: ByteArray): ByteArray {
        if (sealed.size < 16) throw CryptException("A block is too short.")
        val n = blockNonce(nonce, index)
        val engine = XSalsa20Engine().apply { init(false, ParametersWithIV(KeyParameter(dataKey), n)) }
        val ciphertext = sealed.copyOfRange(16, sealed.size)
        val input = ByteArray(32 + ciphertext.size)
        ciphertext.copyInto(input, 32)
        val stream = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, stream, 0)
        val expected = poly1305(stream.copyOfRange(0, 32), ciphertext)
        if (!MessageDigest.isEqual(expected, sealed.copyOfRange(0, 16))) throw CryptException("Wrong password, or the file is damaged.")
        return stream.copyOfRange(32, stream.size)
    }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val k = key.copyOf()
        for (i in intArrayOf(3, 7, 11, 15)) k[i] = (k[i].toInt() and 15).toByte()
        for (i in intArrayOf(4, 8, 12)) k[i] = (k[i].toInt() and 252).toByte()
        val mac = Poly1305().apply { init(KeyParameter(k)) }
        mac.update(data, 0, data.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }

    /** The nonce of block [index]: the file's nonce plus the index (little endian, with carry). */
    private fun blockNonce(nonce: ByteArray, index: Long): ByteArray {
        val n = nonce.copyOf()
        var carry = index
        var i = 0
        while (carry != 0L && i < n.size) {
            val sum = (n[i].toLong() and 0xff) + (carry and 0xff)
            n[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            i++
        }
        return n
    }

    /** Encrypt a whole stream: header, then every block. */
    fun encrypt(input: InputStream, output: OutputStream, nonce: ByteArray = newNonce()) {
        output.write(header(nonce))
        var index = 0L
        val buf = ByteArray(BLOCK)
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            output.write(sealBlock(nonce, index++, buf.copyOf(n)))
            if (n < BLOCK) break
        }
    }

    /** Decrypt a whole stream. Returns the number of plain bytes written. */
    fun decrypt(input: InputStream, output: OutputStream): Long {
        val head = ByteArray(HEADER)
        if (readFully(input, head) != HEADER) throw CryptException("The file is too short to be encrypted.")
        if (!head.copyOfRange(0, 8).contentEquals(MAGIC)) throw CryptException("This is not an encrypted file.")
        val nonce = head.copyOfRange(8, HEADER)
        var index = 0L
        var total = 0L
        val buf = ByteArray(BLOCK + 16)
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            val plain = openBlock(nonce, index++, buf.copyOf(n))
            output.write(plain)
            total += plain.size
            if (n < buf.size) break
        }
        return total
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    companion object {
        const val HEADER = 32
        const val NONCE = 24
        const val BLOCK = 65536
        val MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'L'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 'E'.code.toByte(), 0, 0)
        val DEFAULT_SALT = byteArrayOf(0xA8.toByte(), 0x0D, 0xF4.toByte(), 0x3A, 0x8F.toByte(), 0xBD.toByte(), 0x03, 0x08, 0xA7.toByte(), 0xCA.toByte(),
            0xB8.toByte(), 0x3E, 0x58, 0x1F, 0x86.toByte(), 0xB1.toByte())

        /** Size of the encrypted file for [plain] bytes of content. */
        fun encryptedSize(plain: Long): Long = HEADER + plain + 16 * ((plain + BLOCK - 1) / BLOCK)

        /** Plain size of an encrypted file, or null when that size can't be right. */
        fun decryptedSize(encrypted: Long): Long? {
            var size = encrypted - HEADER
            if (size < 0) return null
            val blocks = size / (BLOCK + 16)
            var residue = size % (BLOCK + 16)
            var plain = blocks * BLOCK
            if (residue != 0L) {
                residue -= 16
                if (residue <= 0) return null
                plain += residue
            }
            return plain
        }
    }
}

/** Lowercase base32 with the "extended hex" alphabet and no padding: how rclone writes encrypted names. */
object Base32 {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuv"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text.lowercase()) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray()
    }
}
