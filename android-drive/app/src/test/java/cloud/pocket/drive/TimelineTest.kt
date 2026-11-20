package cloud.pocket.drive

import cloud.pocket.drive.data.DONE
import cloud.pocket.drive.data.DOWN
import cloud.pocket.drive.data.FAILED
import cloud.pocket.drive.data.SyncLog
import cloud.pocket.drive.data.TransferEntity
import cloud.pocket.drive.data.UP
import cloud.pocket.drive.ui.components.FileKind
import cloud.pocket.drive.ui.components.FileTypes
import cloud.pocket.drive.ui.screens.Timeline
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimelineTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun transfer(id: Long, name: String, state: String, finished: Long, pair: Long = 0, kind: String = UP) =
        TransferEntity(id = id, kind = kind, bucket = "shared", key = name, localUri = "", name = name, size = 1, state = state, finishedAt = finished, pairId = pair)

    @Test fun mergesSyncLogAndHandTransfersNewestFirst() {
        val log = listOf(SyncLog(1, 7, at(20, 19, 12), "info", "212 photos uploaded"), SyncLog(2, 8, at(20, 21, 4), "conflict", "report.xlsx changed"))
        val transfers = listOf(
            transfer(11, "lease.pdf", DONE, at(20, 18, 20)),
            transfer(12, "IMG_1.jpg", DONE, at(20, 19, 11), pair = 7),          // made by sync: summed up in the log already
            transfer(13, "bank.zip", FAILED, at(20, 20, 0)),                     // not finished
            transfer(14, "taxes.pdf", DONE, at(20, 22, 0), kind = DOWN),
        )
        val lines = Timeline.merge(log, mapOf(7L to "Camera backup", 8L to "Documents"), transfers)
        assertEquals(listOf("Downloaded taxes.pdf", "report.xlsx changed", "212 photos uploaded", "Uploaded lease.pdf"), lines.map { it.text })
        assertEquals(listOf("You", "Documents", "Camera backup", "You"), lines.map { it.who })
        // transfer lines know their transfer, so each can be removed on its own; sync lines don't
        assertEquals(listOf(14L, 0L, 0L, 11L), lines.map { it.transferId })
    }

    @Test fun conflictsSinceDismissal() {
        val log = listOf(SyncLog(1, 1, 100, "conflict", "a"), SyncLog(2, 1, 300, "conflict", "b"), SyncLog(3, 1, 400, "info", "c"))
        assertEquals(listOf("b"), Timeline.conflicts(log, since = 200).map { it.text })
    }

    @Test fun shortTimes() {
        val now = at(20, 23, 0)
        assertEquals("21:04", Timeline.shortWhen(at(20, 21, 4), now, zone))
        assertEquals("Yesterday", Timeline.shortWhen(at(19, 9, 0), now, zone))
        assertEquals("17 Nov", Timeline.shortWhen(at(17, 9, 0), now, zone))
        assertEquals("Today 21:04", Timeline.fileWhen(at(20, 21, 4), now, zone))
        assertEquals("—", Timeline.shortWhen(0, now, zone))
    }

    @Test fun fileTypeTiles() {
        assertEquals(FileKind.PDF, FileTypes.kind("lease-2026.PDF"))
        assertEquals(FileKind.SHEET, FileTypes.kind("budget.xlsx"))
        assertEquals(FileKind.OTHER, FileTypes.kind("README"))
        assertEquals("XLSX", FileTypes.label("budget.xlsx"))
        assertEquals("JPEG", FileTypes.label("a.jpeg"))
        assertEquals("FILE", FileTypes.label("Makefile"))
        assertEquals("GZ", FileTypes.label("logs.tar.gz"))
    }
}
