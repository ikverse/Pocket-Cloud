package cloud.pocket.drive

import cloud.pocket.drive.data.ServerApi
import org.junit.Assert.assertEquals
import org.junit.Test

/** The expected values come from the server's own signer (pocket.sigv4.sign_pocket). */
class ServerApiTest {
    private val secret = "secret-value-123"

    @Test
    fun anEmptyGetIsSignedLikeTheServerDoes() {
        assertEquals("a8ca1430bd23d70673466f9c3494f1993e6789c6e7049436a41567bdf2009c8e", ServerApi.sign(secret, "GET", "/_pocket/quota", "", 1700000000, ByteArray(0)))
    }

    @Test
    fun aBodyIsPartOfTheSignature() {
        val body = """{"bucket":"shared","key":"a b/c.txt","expires_s":3600}""".toByteArray()
        assertEquals("2efe4a72de9a452a98f94b361af01b7a4e00920f34733e11f1e5dd24a12f989c", ServerApi.sign(secret, "POST", "/_pocket/share", "", 1700000000, body))
    }

    @Test
    fun theQueryAndMethodAreSigned() {
        assertEquals("ab45a768caa8ae3b9fc992ef8e8ec6305c9faad546db5c2c819d7e4a13bbf0cb", ServerApi.sign(secret, "delete", "/_pocket/share", "token=abc123", 1700000000, ByteArray(0)))
    }
}
