package cloud.pocket.drive

import cloud.pocket.drive.data.ServerApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Moving one file: the server's own route when it has one, otherwise copy and delete (fakes stand in for the server). */
class MoveTest {
    private val calls = mutableListOf<String>()

    private fun move(hasFeature: Boolean, server: suspend () -> Unit = {}) = runBlocking {
        ServerApi.moveOrCopy(hasFeature, viaServer = { calls += "route"; server() }, copyAndDelete = { calls += "copy+delete" })
    }

    @Test
    fun usesTheRouteWhenTheServerHasMove() {
        move(true)
        assertEquals(listOf("route"), calls)
    }

    @Test
    fun copiesAndDeletesWithoutTheFeature() {
        move(false)
        assertEquals(listOf("copy+delete"), calls)
    }

    @Test
    fun fallsBackWhenTheRouteItselfIsMissing() {
        move(true) { throw ServerApi.Failure(404, "NotFound", "no such route") }
        assertEquals(listOf("route", "copy+delete"), calls)
        calls.clear()
        move(true) { throw ServerApi.Failure(404, "HTTP404", "not json") }
        assertEquals(listOf("route", "copy+delete"), calls)
    }

    @Test
    fun aMissingFileIsPassedOnNotRetriedAsCopy() {
        for (code in listOf("NoSuchKey", "NoSuchBucket")) {
            calls.clear()
            try {
                move(true) { throw ServerApi.Failure(404, code, "gone") }
                fail("expected $code")
            } catch (e: ServerApi.Failure) {
                assertEquals(code, e.code)
            }
            assertEquals(listOf("route"), calls)
        }
    }

    @Test
    fun otherRefusalsArePassedOn() {
        try {
            move(true) { throw ServerApi.Failure(403, "ReadOnly", "read only") }
            fail("expected ReadOnly")
        } catch (e: ServerApi.Failure) {
            assertEquals(403, e.status)
        }
        assertEquals(listOf("route"), calls)
    }

    @Test
    fun theBodyOverwritesLikeCopyDid() {
        val j = ServerApi.moveBody("shared", "a/x.txt", "b/x.txt", overwrite = true)
        assertEquals("shared", j.getJSONObject("from").getString("bucket"))
        assertEquals("a/x.txt", j.getJSONObject("from").getString("key"))
        assertEquals("shared", j.getJSONObject("to").getString("bucket"))
        assertEquals("b/x.txt", j.getJSONObject("to").getString("key"))
        assertTrue(j.getBoolean("overwrite"))
    }
}
