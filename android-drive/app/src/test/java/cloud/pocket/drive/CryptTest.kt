package cloud.pocket.drive

import cloud.pocket.drive.data.vault.Base32
import cloud.pocket.drive.data.vault.Crypt
import cloud.pocket.drive.data.vault.CryptException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Checks against files and names made by the real rclone (password "correct horse battery"). */
class CryptTest {
    private val crypt = Crypt("correct horse battery")

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("vault/$path")!!.use { it.readBytes() }

    private fun vectors(): List<Pair<String, String>> =
        String(resource("vectors.txt"), Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { it.split('\t').let { p -> p[0] to p[1] } }

    /** The plain contents that were given to rclone. */
    private fun plain(path: String): ByteArray = when (path) {
        "empty.txt" -> ByteArray(0)
        "hello.txt" -> "hello vault\n".toByteArray()
        "sub/Файл имя.txt" -> "unicode name ✓".toByteArray()
        "block64k.bin" -> ByteArray(65536) { (it % 251).toByte() }
        "block64k1.bin" -> ByteArray(65537) { (it % 241).toByte() }
        "sub/big.bin" -> ByteArray(200000) { ((it * 7) % 256).toByte() }
        else -> error(path)
    }

    @Test
    fun namesMatchWhatRcloneWrote() {
        for ((enc, plain) in vectors()) {
            assertEquals("encrypting $plain", enc, crypt.encryptPath(plain))
            assertEquals("decrypting $enc", plain, crypt.decryptPath(enc))
        }
    }

    @Test
    fun filesMadeByRcloneAreDecrypted() {
        for ((enc, plainName) in vectors()) {
            val out = ByteArrayOutputStream()
            val n = crypt.decrypt(ByteArrayInputStream(resource(enc)), out)
            assertArrayEquals("contents of $plainName", plain(plainName), out.toByteArray())
            assertEquals(plain(plainName).size.toLong(), n)
        }
    }

    @Test
    fun sizesMatchBothWays() {
        for ((enc, plainName) in vectors()) {
            val size = plain(plainName).size.toLong()
            assertEquals(resource(enc).size.toLong(), Crypt.encryptedSize(size))
            assertEquals(size, Crypt.decryptedSize(resource(enc).size.toLong()))
        }
        assertNull(Crypt.decryptedSize(10))
        assertNull(Crypt.decryptedSize(32 + 5))        // a partial block can't be shorter than its tag
    }

    @Test
    fun whatWeEncryptWeDecrypt() {
        for (size in listOf(0, 1, 15, 16, 65535, 65536, 65537, 131072, 200001)) {
            val data = ByteArray(size) { (it * 31 + size).toByte() }
            val enc = ByteArrayOutputStream().also { crypt.encrypt(ByteArrayInputStream(data), it) }.toByteArray()
            assertEquals(Crypt.encryptedSize(size.toLong()), enc.size.toLong())
            val dec = ByteArrayOutputStream().also { crypt.decrypt(ByteArrayInputStream(enc), it) }.toByteArray()
            assertArrayEquals("size $size", data, dec)
        }
    }

    @Test
    fun aBlockCanBeSealedOnItsOwnAndMatchesTheWholeFile() {
        val data = ByteArray(150000) { (it % 199).toByte() }
        val nonce = crypt.newNonce()
        val whole = ByteArrayOutputStream().also { crypt.encrypt(ByteArrayInputStream(data), it, nonce) }.toByteArray()
        val second = crypt.sealBlock(nonce, 1, data.copyOfRange(65536, 131072))
        assertArrayEquals(whole.copyOfRange(32 + 65552, 32 + 2 * 65552), second)
    }

    @Test
    fun aWrongPasswordIsNoticed() {
        val (enc, _) = vectors().first { it.second == "hello.txt" }
        try {
            Crypt("not the password").decrypt(ByteArrayInputStream(resource(enc)), ByteArrayOutputStream())
            fail("should not decrypt")
        } catch (e: CryptException) {
            assertTrue(e.message!!.isNotBlank())
        }
        assertNull(Crypt("not the password").decryptPath(enc))
    }

    @Test
    fun aDamagedFileIsNoticed() {
        val (enc, _) = vectors().first { it.second == "block64k.bin" }
        val bytes = resource(enc).copyOf().also { it[100] = (it[100] + 1).toByte() }
        try {
            crypt.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream())
            fail("should not decrypt")
        } catch (e: CryptException) {
            // expected
        }
    }

    @Test
    fun notEncryptedNamesAreRefused() {
        assertNull(crypt.decryptSegment("hello.txt"))
        assertNull(crypt.decryptSegment("short"))
        assertNull(crypt.decryptPath("a/b"))
    }

    @Test
    fun base32RoundTripsAndUsesTheExtendedHexAlphabet() {
        val data = ByteArray(40) { it.toByte() }
        assertArrayEquals(data, Base32.decode(Base32.encode(data)))
        assertEquals("", Base32.encode(ByteArray(0)))
        assertTrue(Base32.encode(data).all { it in "0123456789abcdefghijklmnopqrstuv" })
    }

    /** Writes files for the real rclone to read back; only runs when POCKET_INTEROP_DIR is set. */
    @Test
    fun filesWeWriteCanBeReadByRclone() {
        val dir = System.getenv("POCKET_INTEROP_DIR") ?: return
        val samples = mapOf("ours.txt" to "written by Pocket Drive".toByteArray(), "deep/ünï/data.bin" to ByteArray(150000) { (it % 233).toByte() }, "zero.dat" to ByteArray(0))
        for ((name, data) in samples) {
            val target = java.io.File(dir, crypt.encryptPath(name))
            target.parentFile.mkdirs()
            target.outputStream().use { crypt.encrypt(ByteArrayInputStream(data), it) }
        }
    }
}
