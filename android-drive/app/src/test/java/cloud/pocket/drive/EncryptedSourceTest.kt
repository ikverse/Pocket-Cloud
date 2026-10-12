package cloud.pocket.drive

import cloud.pocket.drive.data.vault.Crypt
import cloud.pocket.drive.data.vault.EncryptedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class EncryptedSourceTest {
    private val crypt = Crypt("pw")

    @Test
    fun anyRangeMatchesTheWholeEncryptedFile() {
        val data = Random(7).nextBytes(300_123)
        val nonce = crypt.newNonce()
        val whole = ByteArrayOutputStream().also { crypt.encrypt(ByteArrayInputStream(data), it, nonce) }.toByteArray()
        val source = EncryptedSource(crypt, nonce, data.size.toLong()) { off, len -> data.copyOfRange(off.toInt(), off.toInt() + len) }
        assertEquals(whole.size.toLong(), source.size)
        val rnd = Random(3)
        repeat(60) {
            val off = rnd.nextInt(whole.size)
            val len = rnd.nextInt(1, minOf(200_000, whole.size - off) + 1)
            assertArrayEquals("range $off+$len", whole.copyOfRange(off, off + len), source.read(off.toLong(), len))
        }
    }

    @Test
    fun partsOfEightMegabytesJoinUpToTheWholeFile() {
        val data = Random(9).nextBytes(9 * 1024 * 1024 + 77)
        val nonce = crypt.newNonce()
        val source = EncryptedSource(crypt, nonce, data.size.toLong()) { off, len -> data.copyOfRange(off.toInt(), off.toInt() + len) }
        val part = 8 * 1024 * 1024L
        val joined = ByteArrayOutputStream()
        var off = 0L
        while (off < source.size) {
            val len = minOf(part, source.size - off).toInt()
            joined.write(source.read(off, len))
            off += len
        }
        val back = ByteArrayOutputStream()
        crypt.decrypt(ByteArrayInputStream(joined.toByteArray()), back)
        assertArrayEquals(data, back.toByteArray())
    }

    @Test
    fun anEmptyFileIsJustTheHeader() {
        val source = EncryptedSource(crypt, crypt.newNonce(), 0) { _, _ -> ByteArray(0) }
        assertEquals(32L, source.size)
        assertEquals(32, source.read(0, 32).size)
    }
}
