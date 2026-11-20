package cloud.pocket.lite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** docs/app-api.md section 12: every library's signer must give exactly these. */
class SignerTest {
    private val key = "AKTEST0000000001"
    private val secret = "secret-for-tests-only"
    private val date = 1795000000L

    private fun sign(method: String, path: String, query: String, body: ByteArray) =
        Signer.sign(method, path, query, Signer.sha256(body), key, secret, date)["x-pocket-sign"]

    @Test fun quotaVector() = assertEquals("d872ac21f923208dc0ca0344d8a3fe488e4b717b656d7328149e89799ac06b71",
        sign("GET", "/_pocket/quota", "", ByteArray(0)))

    @Test fun putVector() {
        assertEquals("015bd2b75bedd7fbc3a9ce49abd670c4ff7a1ef6d8f96fd795cfbe5d8995c04f",
            sign("PUT", "/_pocket/files/shared/notes/hello%20world.txt", "", "hello".toByteArray()))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", Signer.sha256("hello".toByteArray()))
        assertEquals("\"5d41402abc4b2a76b9719d911017c592\"", FakePocketServer.md5("hello".toByteArray()))
    }

    @Test fun listVector() = assertEquals("0884bcb1271e92c9f16e501b09205689aacc6e65c212461a42ffeb102f6f14d6",
        sign("GET", "/_pocket/list", "bucket=shared&prefix=notes%2F&limit=2", ByteArray(0)))

    @Test fun headersAndEmptyHash() {
        val h = Signer.sign("get", "/_pocket/quota", null, Signer.EMPTY_SHA256, key, secret, date)
        assertEquals(key, h["x-pocket-key"])
        assertEquals("1795000000", h["x-pocket-date"])
        assertEquals("d872ac21f923208dc0ca0344d8a3fe488e4b717b656d7328149e89799ac06b71", h["x-pocket-sign"])
        assertEquals(Signer.EMPTY_SHA256, Signer.sha256(ByteArray(0)))
    }

    @Test fun keyEncoding() {
        assertEquals("notes/hello%20world.txt", Encode.key("notes/hello world.txt"))
        assertEquals("a/%C3%A9t%C3%A9/x~y_z-1.txt", Encode.key("a/été/x~y_z-1.txt"))
        assertEquals("100%25%2B%3F%26", Encode.component("100%+?&"))
        assertEquals("bucket=shared&prefix=notes%2F&limit=2",
            Encode.query(listOf("bucket" to "shared", "prefix" to "notes/", "cursor" to null, "limit" to "2")))
    }

    @Test fun badKeys() {
        for (k in listOf("", "/a", "a/", "a//b", "a/./b", "a/../b", "..", "a\u0000b", "x".repeat(1025))) {
            assertFailsWith<PocketException>(k) { Encode.key(k) }.also { assertEquals("BadKey", it.code) }
        }
        Encode.key("x".repeat(1024))
    }
}
