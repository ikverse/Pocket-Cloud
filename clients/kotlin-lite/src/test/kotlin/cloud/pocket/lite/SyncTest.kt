package cloud.pocket.lite

import cloud.pocket.lite.TestServer.newApp
import cloud.pocket.lite.TestServer.open
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sync rules of docs/app-api.md section 10. No `/_fake/` controls. */
class SyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.write(rel: String, text: String) = File(this, rel).also { it.parentFile.mkdirs(); it.writeText(text) }

    @Test fun firstRunCopiesBothWaysAndNeverDeletes() = runBlocking<Unit> {
        val p = open(newApp())
        val dir = tmp.newFolder("a")
        dir.write("local.txt", "L")
        dir.write("sub/deep.txt", "D")
        dir.write("same.txt", "S")
        dir.write(".pocket-private", "never")
        p.put("docs/remote.txt", "R".toByteArray())
        p.put("docs/same.txt", "S".toByteArray())
        p.put("docs/differs.txt", "remote".toByteArray())
        dir.write("differs.txt", "local")
        val r = p.sync(dir, prefix = "docs/")
        assertEquals(setOf("docs/local.txt", "docs/sub/deep.txt"), r.uploaded.filter { "conflict" !in it }.toSet())
        assertEquals(setOf("docs/remote.txt", "docs/differs.txt"), r.downloaded.toSet())
        assertTrue(r.deletedLocal.isEmpty() && r.deletedRemote.isEmpty())
        assertEquals(2, r.conflicts.size)
        assertEquals("docs/differs.txt", r.conflicts[0])
        val cKey = r.conflicts[1]
        assertTrue(cKey.matches(Regex("docs/differs \\(conflict Test box \\d{4}-\\d{2}-\\d{2} \\d{4}\\)\\.txt")), cKey)
        assertEquals("remote", File(dir, "differs.txt").readText())
        assertEquals("local", File(dir, cKey.removePrefix("docs/")).readText())
        assertContentEquals("local".toByteArray(), p.get(cKey))
        assertEquals("R", File(dir, "remote.txt").readText())
        assertNull(p.stat("docs/.pocket-private"))
        assertNull(p.stat("docs/.pocket-sync.json"))
        assertTrue(File(dir, ".pocket-sync.json").isFile)

        val again = p.sync(dir, prefix = "docs/")
        assertEquals(SyncResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), again)
    }

    @Test fun laterRunsFollowChangesBothWays() = runBlocking<Unit> {
        val app = newApp()
        val p = open(app, deviceId = "dev-aaaa-0001", deviceName = "Laptop")
        val other = open(app, deviceId = "dev-bbbb-0002", deviceName = "Phone")
        val dir = tmp.newFolder("b")
        dir.write("keep.txt", "k")
        dir.write("edit-local.txt", "1")
        dir.write("del-local.txt", "x")
        p.put("edit-remote.txt", "1".toByteArray())
        p.put("del-remote.txt", "x".toByteArray())
        p.put("both.txt", "base".toByteArray())
        p.sync(dir)
        assertEquals("base", File(dir, "both.txt").readText())

        // local edits and deletes
        File(dir, "edit-local.txt").apply { writeText("2"); setLastModified(lastModified() + 5000) }
        File(dir, "del-local.txt").delete()
        dir.write("new-local.txt", "n")
        // remote edits and deletes from another device
        val er = other.stat("edit-remote.txt")!!
        other.put("edit-remote.txt", "2".toByteArray(), ifMatch = er.etag)
        other.delete("del-remote.txt")
        other.put("new-remote.txt", "n".toByteArray())
        // both sides change the same file
        other.put("both.txt", "remote edit".toByteArray())
        File(dir, "both.txt").apply { writeText("local edit"); setLastModified(lastModified() + 5000) }

        val r = p.sync(dir)
        assertTrue("edit-local.txt" in r.uploaded && "new-local.txt" in r.uploaded, r.toString())
        assertEquals(setOf("edit-remote.txt", "new-remote.txt", "both.txt"), r.downloaded.toSet())
        assertEquals(listOf("del-remote.txt"), r.deletedLocal)
        assertEquals(listOf("del-local.txt"), r.deletedRemote)
        assertEquals("both.txt", r.conflicts[0])
        assertTrue(r.conflicts[1].startsWith("both (conflict Laptop "), r.conflicts[1])
        assertContentEquals("2".toByteArray(), p.get("edit-local.txt"))
        assertEquals("2", File(dir, "edit-remote.txt").readText())
        assertTrue(!File(dir, "del-remote.txt").exists())
        assertNull(p.stat("del-local.txt"))
        assertEquals("remote edit", File(dir, "both.txt").readText())
        assertContentEquals("remote edit".toByteArray(), p.get("both.txt"))
        assertContentEquals("local edit".toByteArray(), p.get(r.conflicts[1]))

        val again = p.sync(dir)
        assertEquals(SyncResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), again)
        assertEquals(p.list().toList().map { it.key }.toSet(),
            dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".pocket-") }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet())
    }

    @Test fun massDeleteGuard() = runBlocking<Unit> {
        val p = open(newApp())
        val dir = tmp.newFolder("m")
        for (i in 1..60) dir.write("f$i.txt", "$i")
        p.sync(dir, prefix = "m/")
        for (i in 1..60) File(dir, "f$i.txt").delete()
        dir.write("new.txt", "still uploaded")
        val e = assertFailsWith<PocketException> { p.sync(dir, prefix = "m/") }
        assertEquals("MassDelete", e.code)
        assertTrue("60" in e.message!!)
        assertEquals(61, p.list("m/").toList().size)          // nothing deleted, new file uploaded
        val r = p.sync(dir, prefix = "m/", allowMassDelete = true)
        assertEquals(60, r.deletedRemote.size)
        assertEquals(listOf("m/new.txt"), p.list("m/").toList().map { it.key })
    }

    @Test fun aNewPrefixIsAFirstRunAndDeletesNothing() = runBlocking<Unit> {
        val p = open(newApp())
        val dir = tmp.newFolder("np")
        dir.write("a.txt", "a")
        dir.write("b.txt", "b")
        p.sync(dir, prefix = "notes/")
        val r = p.sync(dir, prefix = "notes-v2/")
        assertTrue(r.deletedRemote.isEmpty() && r.deletedLocal.isEmpty(), r.toString())
        assertEquals(setOf("notes-v2/a.txt", "notes-v2/b.txt"), r.uploaded.toSet())
        assertEquals(listOf("notes/a.txt", "notes/b.txt"), p.list("notes/").toList().map { it.key })
    }

    @Test fun aNewBucketDownloadsItsFiles() = runBlocking<Unit> {
        val p = open(newApp())
        val dir = tmp.newFolder("nb")
        dir.write("a.txt", "a")
        p.sync(dir)
        p.put("mine.txt", "m".toByteArray(), bucket = "device")
        val r = p.sync(dir, bucket = "device")
        assertEquals(listOf("mine.txt"), r.downloaded)
        assertEquals("m", File(dir, "mine.txt").readText())
        assertContentEquals("a".toByteArray(), p.get("a.txt", bucket = "device"))
    }

    @Test fun heldBackLocalDeletesHappenOnTheAllowedRun() = runBlocking<Unit> {
        val app = newApp()
        val p = open(app, deviceId = "dev-aaaa-0001")
        val other = open(app, deviceId = "dev-bbbb-0002")
        val dir = tmp.newFolder("hl")
        for (i in 1..60) dir.write("f$i.txt", "$i")
        p.sync(dir)
        for (i in 1..60) other.delete("f$i.txt")
        assertEquals("MassDelete", assertFailsWith<PocketException> { p.sync(dir) }.code)
        assertEquals(60, dir.listFiles()!!.count { it.name.endsWith(".txt") })
        assertEquals("MassDelete", assertFailsWith<PocketException> { p.sync(dir) }.code)   // still pending
        val r = p.sync(dir, allowMassDelete = true)
        assertEquals(60, r.deletedLocal.size)
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".txt") })
        assertEquals(SyncResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), p.sync(dir))
    }

    @Test fun twoConflictsInOneMinuteKeepBothCopies() = runBlocking<Unit> {
        val app = newApp()
        val p = open(app, deviceId = "dev-aaaa-0001", deviceName = "Laptop")
        val other = open(app, deviceId = "dev-bbbb-0002")
        val t = System.currentTimeMillis() / 1000.0
        p.clock = { t }
        val dir = tmp.newFolder("cc")
        dir.write("n.txt", "base")
        p.sync(dir)
        val names = mutableListOf<String>()
        for (round in 1..2) {
            other.put("n.txt", "remote $round".toByteArray())
            File(dir, "n.txt").apply { writeText("local edit $round"); setLastModified(lastModified() + 5000L * round) }
            names += p.sync(dir).conflicts[1]
        }
        assertNotEquals(names[0], names[1])
        assertTrue(names[1].endsWith(") 2.txt"), names[1])
        assertContentEquals("local edit 1".toByteArray(), p.get(names[0]))
        assertContentEquals("local edit 2".toByteArray(), p.get(names[1]))
        assertEquals("remote 2", File(dir, "n.txt").readText())
    }
}
