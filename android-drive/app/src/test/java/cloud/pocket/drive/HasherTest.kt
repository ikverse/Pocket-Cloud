package cloud.pocket.drive

import cloud.pocket.drive.data.sync.Md5
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class HasherTest {
    @Test
    fun md5MatchesTheKnownValues() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(ByteArrayInputStream(ByteArray(0))))
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", Md5.hex(ByteArrayInputStream("hello world".toByteArray())))
    }

    @Test
    fun largeInputsAreReadInChunks() {
        val data = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val whole = java.security.MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(whole, Md5.hex(ByteArrayInputStream(data)))
    }
}
