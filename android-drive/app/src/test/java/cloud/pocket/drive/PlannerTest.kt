package cloud.pocket.drive

import cloud.pocket.drive.data.DOWNLOAD_ONLY
import cloud.pocket.drive.data.TWO_WAY
import cloud.pocket.drive.data.UPLOAD_ONLY
import cloud.pocket.drive.data.sync.Action
import cloud.pocket.drive.data.sync.Base
import cloud.pocket.drive.data.sync.LFile
import cloud.pocket.drive.data.sync.PlanConfig
import cloud.pocket.drive.data.sync.Planner
import cloud.pocket.drive.data.sync.RFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val t0 = 1_700_000_000_000L
    private val cfg = PlanConfig(device = "Pixel", today = "2026-10-12")

    private fun l(size: Long, mtime: Long = t0) = LFile(size, mtime)
    private fun r(size: Long, mtime: Long = t0, etag: String? = "e$size") = RFile(size, mtime, etag)
    private fun b(size: Long, mtime: Long = t0, etag: String = "e$size") = Base(size, mtime, size, mtime, etag)

    private fun plan(local: Map<String, LFile> = emptyMap(), remote: Map<String, RFile> = emptyMap(), base: Map<String, Base> = emptyMap(),
                     config: PlanConfig = cfg, md5: (String) -> String? = { null }) = Planner.plan(local, remote, base, config, md5)

    @Test
    fun aNewFileOnEitherSideIsCopiedToTheOther() {
        val p = plan(local = mapOf("a.txt" to l(10)), remote = mapOf("b.txt" to r(20)))
        assertEquals(listOf(Action.Upload("a.txt"), Action.Download("b.txt")), p.actions)
    }

    @Test
    fun nothingChangedMeansNothingToDo() {
        val p = plan(mapOf("a" to l(5)), mapOf("a" to r(5)), mapOf("a" to b(5)))
        assertTrue(p.actions.isEmpty())
        assertNull(p.guard)
    }

    @Test
    fun anEditOnOneSideTravelsToTheOther() {
        val base = mapOf("a" to b(5), "b" to b(7))
        val p = plan(local = mapOf("a" to l(6, t0 + 60_000), "b" to l(7)), remote = mapOf("a" to r(5), "b" to r(8, t0 + 90_000, "new")), base = base)
        assertEquals(setOf(Action.Upload("a"), Action.Download("b")), p.actions.toSet())
    }

    @Test
    fun theSameEditOnBothSidesIsNotAConflict() {
        val p = plan(mapOf("a" to l(6, t0 + 5_000)), mapOf("a" to r(6, t0 + 5_000, "e6")), mapOf("a" to b(5)))
        assertEquals(listOf<Action>(Action.Adopt("a")), p.actions)
    }

    @Test
    fun differentEditsOnBothSidesKeepBothFiles() {
        val p = plan(mapOf("docs/plan.txt" to l(6, t0 + 5_000)), mapOf("docs/plan.txt" to r(7, t0 + 9_000, "x")), mapOf("docs/plan.txt" to b(5)))
        assertEquals(listOf<Action>(Action.Conflict("docs/plan.txt", "docs/plan (conflict, Pixel, 2026-10-12).txt")), p.actions)
    }

    @Test
    fun newestWinsPicksTheLaterEdit() {
        val c = cfg.copy(newestWins = true)
        val newerHere = plan(mapOf("a" to l(6, t0 + 9_000)), mapOf("a" to r(7, t0 + 1_000, "x")), mapOf("a" to b(5)), c)
        assertEquals(listOf<Action>(Action.Upload("a")), newerHere.actions)
        val newerThere = plan(mapOf("a" to l(6, t0 + 1_000)), mapOf("a" to r(7, t0 + 9_000, "x")), mapOf("a" to b(5)), c)
        assertEquals(listOf<Action>(Action.Download("a")), newerThere.actions)
    }

    @Test
    fun aFileOnBothSidesWithoutHistoryIsAdoptedWhenAlike() {
        assertEquals(listOf<Action>(Action.Adopt("a")), plan(mapOf("a" to l(5, t0 + 1_000)), mapOf("a" to r(5, t0))).actions)
        // different time, but the checksum settles it
        val byHash = plan(mapOf("a" to l(5, t0 + 99_000)), mapOf("a" to r(5, t0, "abc")), md5 = { "abc" })
        assertEquals(listOf<Action>(Action.Adopt("a")), byHash.actions)
        val differ = plan(mapOf("a" to l(5, t0 + 99_000)), mapOf("a" to r(5, t0, "abc")), md5 = { "zzz" })
        assertTrue(differ.actions.single() is Action.Conflict)
    }

    @Test
    fun timesWithinTwoSecondsAreTheSame() {
        val within = plan(mapOf("a" to l(5, t0 + 1_900)), mapOf("a" to r(5, t0)), mapOf("a" to b(5)))
        assertTrue(within.actions.isEmpty())
        val outside = plan(mapOf("a" to l(5, t0 + 2_500)), mapOf("a" to r(5, t0)), mapOf("a" to b(5)))
        assertEquals(listOf<Action>(Action.Upload("a")), outside.actions)
    }

    @Test
    fun deletesTravelBothWays() {
        val base = mapOf("gone-here" to b(5), "gone-there" to b(6))
        val p = plan(local = mapOf("gone-there" to l(6)), remote = mapOf("gone-here" to r(5)), base = base)
        assertEquals(setOf(Action.DeleteRemote("gone-here"), Action.DeleteLocal("gone-there")), p.actions.toSet())
    }

    @Test
    fun anEditBeatsADeleteOnTheOtherSide() {
        val base = mapOf("a" to b(5), "b" to b(6))
        val p = plan(local = mapOf("b" to l(9, t0 + 7_000)), remote = mapOf("a" to r(8, t0 + 7_000, "new")), base = base)
        assertEquals(setOf(Action.Download("a"), Action.Upload("b")), p.actions.toSet())
    }

    @Test
    fun aFileGoneFromBothSidesIsForgotten() {
        assertEquals(listOf<Action>(Action.Forget("a")), plan(base = mapOf("a" to b(5))).actions)
    }

    @Test
    fun uploadOnlyNeverDeletesOrDownloads() {
        val c = cfg.copy(mode = UPLOAD_ONLY)
        val p = plan(local = mapOf("new" to l(1)), remote = mapOf("theirs" to r(2), "kept" to r(3)), base = mapOf("deleted-here" to b(4), "kept" to b(3)), config = c,
            md5 = { null })
        assertEquals(listOf<Action>(Action.Upload("new")), p.actions.filter { it !is Action.Forget })
        assertTrue(p.actions.none { it is Action.DeleteRemote || it is Action.Download })
    }

    @Test
    fun aBackupDoesNotUploadAgainWhatTheServerLost() {
        val c = cfg.copy(mode = UPLOAD_ONLY)
        val p = plan(local = mapOf("a" to l(5)), base = mapOf("a" to b(5)), config = c)
        assertTrue(p.actions.isEmpty())
    }

    @Test
    fun downloadOnlyIgnoresLocalFilesAndMirrorsDeletes() {
        val c = cfg.copy(mode = DOWNLOAD_ONLY)
        val p = plan(local = mapOf("mine" to l(1), "old" to l(2)), remote = mapOf("new" to r(3)), base = mapOf("old" to b(2)), config = c)
        assertEquals(setOf(Action.Download("new"), Action.DeleteLocal("old")), p.actions.toSet())
    }

    @Test
    fun aMovedFileIsMovedNotCopiedAgain() {
        val base = mapOf("old/name.jpg" to b(500, etag = "h1"))
        val up = plan(local = mapOf("new/name.jpg" to l(500)), remote = mapOf("old/name.jpg" to r(500, etag = "h1")), base = base, md5 = { if (it == "new/name.jpg") "h1" else null })
        assertEquals(listOf<Action>(Action.MoveRemote("old/name.jpg", "new/name.jpg")), up.actions)

        val down = plan(local = mapOf("old/name.jpg" to l(500)), remote = mapOf("new/name.jpg" to r(500, etag = "h1")), base = base)
        assertEquals(listOf<Action>(Action.MoveLocal("old/name.jpg", "new/name.jpg")), down.actions)
    }

    @Test
    fun aNewFileOfTheSameSizeButOtherContentIsNotARename() {
        val base = mapOf("old" to b(500, etag = "h1"))
        val p = plan(local = mapOf("new" to l(500)), remote = mapOf("old" to r(500, etag = "h1")), base = base, md5 = { "other" })
        assertEquals(setOf(Action.DeleteRemote("old"), Action.Upload("new")), p.actions.toSet())
    }

    @Test
    fun namesThatDifferOnlyByCaseAreLeftAlone() {
        val p = plan(local = mapOf("Readme.txt" to l(1)), remote = mapOf("README.txt" to r(2), "readme.txt" to r(3), "ok.txt" to r(4)))
        assertEquals(listOf<Action>(Action.Download("ok.txt")), p.actions)
        assertEquals(listOf("README.txt", "Readme.txt", "readme.txt"), p.skipped.sorted())
    }

    @Test
    fun deletingTooManyFilesAsksFirst() {
        val base = (1..100).associate { "f$it" to b(it.toLong()) }
        val remote = (1..100).associate { "f$it" to r(it.toLong()) }
        val p = plan(local = emptyMap(), remote = remote, base = base)
        assertNotNull(p.guard)
        assertEquals(100, p.guard!!.deletes)
        assertEquals("local", p.guard!!.from)
        assertTrue(p.actions.none { it is Action.DeleteRemote })
    }

    @Test
    fun aFifthOfTheFilesIsAlsoTooMany() {
        val base = (1..20).associate { "f$it" to b(it.toLong()) }
        val remote = (1..20).associate { "f$it" to r(it.toLong()) }
        val local = (6..20).associate { "f$it" to l(it.toLong()) }       // 5 of 20 deleted here
        assertNotNull(plan(local = local, remote = remote, base = base).guard)
        val few = (5..20).associate { "f$it" to l(it.toLong()) }         // 4 of 20: fine
        val ok = plan(local = few, remote = remote, base = base)
        assertNull(ok.guard)
        assertEquals(4, ok.actions.count { it is Action.DeleteRemote })
    }

    @Test
    fun smallCleanupsAreNotHeldBack() {
        val p = plan(local = emptyMap(), remote = mapOf("a" to r(1)), base = mapOf("a" to b(1)))
        assertNull(p.guard)
        assertEquals(listOf<Action>(Action.DeleteRemote("a")), p.actions)
    }

    @Test
    fun theUsersAnswerToAPausedRun() {
        val base = (1..100).associate { "f$it" to b(it.toLong()) }
        val remote = (1..100).associate { "f$it" to r(it.toLong()) }
        val delete = plan(local = emptyMap(), remote = remote, base = base, config = cfg.copy(decision = "delete"))
        assertNull(delete.guard)
        assertEquals(100, delete.actions.count { it is Action.DeleteRemote })
        val restore = plan(local = emptyMap(), remote = remote, base = base, config = cfg.copy(decision = "restore"))
        assertNull(restore.guard)
        assertEquals(100, restore.actions.count { it is Action.Download })
    }

    @Test
    fun conflictNamesKeepTheExtensionAndFolder() {
        assertEquals("a (conflict, Pixel, 2026-10-12).txt", Planner.conflictName("a.txt", "Pixel", "2026-10-12"))
        assertEquals("d/e/archive (conflict, Pixel, 2026-10-12).tar.gz", Planner.conflictName("d/e/archive.tar.gz", "Pixel", "2026-10-12"))
        assertEquals("noext (conflict, Pixel, 2026-10-12)", Planner.conflictName("noext", "Pixel", "2026-10-12"))
    }
}
