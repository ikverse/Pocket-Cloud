package cloud.pocket.drive

import cloud.pocket.drive.data.Batch
import cloud.pocket.drive.data.CANCELED
import cloud.pocket.drive.data.DONE
import cloud.pocket.drive.data.DOWN
import cloud.pocket.drive.data.FAILED
import cloud.pocket.drive.data.PAUSED
import cloud.pocket.drive.data.QUEUED
import cloud.pocket.drive.data.Resume
import cloud.pocket.drive.data.TransferEntity
import cloud.pocket.drive.data.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferQueueTest {
    @Test
    fun finishedPartsSurviveARestart() {
        val parts = mapOf(2 to "\"bb\"", 1 to "\"aa\"", 3 to "\"cc\"")
        val stored = Resume.encode(parts)
        assertEquals("1:\"aa\",2:\"bb\",3:\"cc\"", stored)        // always in part order
        assertEquals(parts, Resume.decode(stored))
        assertEquals(emptyMap<Int, String>(), Resume.decode(""))
    }

    @Test
    fun retryWaitsDoubleUpToFifteenMinutes() {
        assertEquals(30_000L, Resume.backoffMs(0))
        assertEquals(60_000L, Resume.backoffMs(1))
        assertEquals(120_000L, Resume.backoffMs(2))
        assertEquals(15 * 60_000L, Resume.backoffMs(9))
        assertEquals(15 * 60_000L, Resume.backoffMs(500))
    }

    @Test
    fun anUploadIsCutIntoWholePartsAndALastShortOne() {
        val mb = 1024L * 1024
        val parts = Resume.parts(20 * mb + 5, 8 * mb)
        assertEquals(3, parts.size)
        assertEquals(0L to (8 * mb).toInt(), parts[0])
        assertEquals(8 * mb to (8 * mb).toInt(), parts[1])
        assertEquals(16 * mb to (4 * mb + 5).toInt(), parts[2])
        assertEquals(20 * mb + 5, parts.sumOf { it.second.toLong() })
    }

    private fun transfer(state: String, kind: String = UP, name: String = "a.jpg", error: String? = null) =
        TransferEntity(kind = kind, bucket = "shared", key = name, localUri = "", name = name, size = 10, state = state, error = error)

    @Test
    fun aCleanRunIsSummedUpBriefly() {
        val b = Batch()
        repeat(3) { b.add(transfer(DONE)) }
        b.add(transfer(DONE, DOWN))
        assertFalse(b.hasProblems)
        assertEquals("Transfers finished", b.title())
        assertEquals("3 uploaded, 1 downloaded", b.text())
        val one = Batch().apply { add(transfer(DONE)) }
        assertEquals("1 file uploaded", one.title())
    }

    @Test
    fun aFailureIsNamedWithItsReason() {
        val b = Batch()
        b.add(transfer(DONE))
        b.add(transfer(FAILED, name = "big.mkv", error = "There is no room left in this drive."))
        b.add(transfer(FAILED, name = "b.mkv", error = "x"))
        assertTrue(b.hasProblems)
        assertEquals("2 transfers failed", b.title())
        assertEquals("big.mkv: There is no room left in this drive. (and 1 more) · 1 uploaded", b.text())
    }

    @Test
    fun aRetryIsReportedAndNotCountedAsDone() {
        val b = Batch()
        b.add(transfer(QUEUED, error = "Can't find the server. Is Tailscale connected?"))
        b.add(transfer(CANCELED))
        b.add(transfer(PAUSED))
        assertTrue(b.hasProblems)
        assertEquals("A transfer will be retried", b.title())
        assertEquals("Can't find the server. Is Tailscale connected?", b.text())
        assertTrue(Batch().isEmpty)
    }

    @Test
    fun exactMultiplesHaveNoEmptyLastPart() {
        val parts = Resume.parts(16L * 1024 * 1024, 8L * 1024 * 1024)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.second == 8 * 1024 * 1024 })
        assertEquals(emptyList<Pair<Long, Int>>(), Resume.parts(0, 8))
    }
}
