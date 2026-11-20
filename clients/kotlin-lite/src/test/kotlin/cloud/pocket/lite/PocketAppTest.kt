package cloud.pocket.lite

import cloud.pocket.lite.TestServer.newApp
import cloud.pocket.lite.TestServer.open
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library surface against a server. Uses no `/_fake/` controls, so the same suite can run against the
 * real server in step 2.
 */
class PocketAppTest {
    @get:Rule val tmp = TemporaryFolder()

    @Serializable data class Prices(val day: String, val close: List<Double>)

    @Test fun enrollsOnceAndReusesTheKey() = runBlocking<Unit> {
        val app = newApp()
        val store = MemoryCredentialStore()
        val a = open(app, store)
        val key = store.loadKey(app)
        assertNotNull(key)
        assertTrue(key.accessKey.isNotEmpty())
        assertEquals(TestServer.url, key.endpoint)
        assertEquals("dev-test-0001", key.deviceId)
        assertTrue("files" in a.features())
        val b = open(app, store)
        assertEquals(key, store.loadKey(app))
        b.put("x.txt", "hi".toByteArray())
        assertContentEquals("hi".toByteArray(), a.get("x.txt"))
    }

    @Test fun fileStoreKeepsKeysOnDisk() = runBlocking<Unit> {
        val home = tmp.newFolder("home")
        val app = newApp()
        Pocket.connect(app = app, endpoint = TestServer.url, enrollSecret = TestServer.SECRET, home = home, deviceName = "Disk")
        assertTrue(File(home, "apps/$app.json").isFile)
        val id = File(home, "device_id").readText().trim()
        assertTrue(id.startsWith("dev-"))
        val again = Pocket.connect(app = app, endpoint = TestServer.url, enrollSecret = TestServer.SECRET, home = home)
        assertEquals(id, again.deviceId)
    }

    @Test fun notConfigured() = runBlocking<Unit> {
        val home = tmp.newFolder("empty")
        if (System.getenv("POCKET_ENDPOINT") != null) return@runBlocking
        val e = assertFailsWith<PocketException> { Pocket.connect(app = newApp(), home = home) }
        assertEquals("NotConfigured", e.code)
    }

    @Test fun wrongEnrollSecretIsAccessDenied() = runBlocking<Unit> {
        val e = assertFailsWith<AccessDeniedException> {
            Pocket.connect(app = newApp(), endpoint = TestServer.url, enrollSecret = "nope", store = MemoryCredentialStore())
        }
        assertEquals(403, e.status)
    }

    @Test fun putGetStatDelete() = runBlocking<Unit> {
        val p = open(newApp())
        val e = p.put("notes/hello world.txt", "hello".toByteArray(), contentType = "text/plain")
        assertEquals("\"5d41402abc4b2a76b9719d911017c592\"", e.etag)
        assertEquals(5, e.size)
        assertEquals("shared", e.bucket)
        assertContentEquals("hello".toByteArray(), p.get("notes/hello world.txt"))
        val s = p.stat("notes/hello world.txt")!!
        assertEquals(e.etag, s.etag)
        assertEquals(5, s.size)
        assertNull(p.stat("notes/missing.txt"))
        assertFailsWith<NotFoundException> { p.get("notes/missing.txt") }.also { assertEquals("NoSuchKey", it.code) }
        p.delete("notes/hello world.txt")
        assertNull(p.stat("notes/hello world.txt"))
        assertFailsWith<NotFoundException> { p.delete("notes/hello world.txt") }
    }

    @Test fun unicodeAndOddKeys() = runBlocking<Unit> {
        val p = open(newApp())
        val keys = listOf("été/naïve file.txt", "a+b/c&d=e?.txt", "100%/x~y.txt", "日本/語.bin")
        for (k in keys) p.put(k, k.toByteArray())
        for (k in keys) assertContentEquals(k.toByteArray(), p.get(k))
        assertEquals(keys.sortedWith { a, b -> FakePocketServer.byteCompare(a, b) }, p.list().toList().map { it.key })
    }

    @Test fun conditionalWrites() = runBlocking<Unit> {
        val p = open(newApp())
        val first = p.put("c.txt", "one".toByteArray())
        val second = p.put("c.txt", "two".toByteArray(), ifMatch = first.etag)
        assertNotEquals(first.etag, second.etag)
        assertFailsWith<PreconditionFailedException> { p.put("c.txt", "three".toByteArray(), ifMatch = first.etag) }
        assertFailsWith<PreconditionFailedException> { p.delete("c.txt", ifMatch = first.etag) }
        p.delete("c.txt", ifMatch = second.etag)
    }

    @Test fun deviceBucketIsPerDevice() = runBlocking<Unit> {
        val app = newApp()
        val a = open(app, deviceId = "dev-aaaa-0001")
        val b = open(app, deviceId = "dev-bbbb-0002")
        a.put("mine.txt", "a".toByteArray(), bucket = "device")
        assertNull(b.stat("mine.txt", bucket = "device"))
        assertContentEquals("a".toByteArray(), a.get("mine.txt", bucket = "device"))
        assertFailsWith<NotFoundException> { a.get("x", bucket = "nope") }.also { assertEquals("NoSuchBucket", it.code) }
    }

    @Test fun listAllPages() = runBlocking<Unit> {
        val p = open(newApp())
        p.pageLimit = 3
        for (i in 1..8) p.put("l/f$i.txt", "$i".toByteArray())
        p.put("other.txt", "x".toByteArray())
        val keys = p.list("l/").toList().map { it.key }
        assertEquals((1..8).map { "l/f$it.txt" }, keys)
        assertEquals(9, p.list().toList().size)
        assertTrue(p.list("l/").toList().all { it.bucket == "shared" && it.etag.startsWith("\"") })
    }

    @Test fun moveFiles() = runBlocking<Unit> {
        val p = open(newApp())
        val e = p.put("inbox/a.txt", "a".toByteArray())
        p.put("done/b.txt", "b".toByteArray())
        val m = p.move("inbox/a.txt", "done/a.txt")
        assertEquals(e.etag, m.etag)
        assertEquals("done/a.txt", m.key)
        assertNull(p.stat("inbox/a.txt"))
        assertFailsWith<PreconditionFailedException> { p.move("done/a.txt", "done/b.txt") }.also { assertEquals("KeyExists", it.code) }
        p.move("done/a.txt", "done/b.txt", overwrite = true)
        assertContentEquals("a".toByteArray(), p.get("done/b.txt"))
        p.move("done/b.txt", "b.txt", toBucket = "device")
        assertContentEquals("a".toByteArray(), p.get("b.txt", bucket = "device"))
    }

    @Test fun jsonRoundTrip() = runBlocking<Unit> {
        val p = open(newApp())
        p.putJson("prices/today.json", Prices("2026-11-20", listOf(1.5, 2.25)))
        assertEquals(Prices("2026-11-20", listOf(1.5, 2.25)), p.getJson<Prices>("prices/today.json"))
        val d = Prices("none", emptyList())
        assertEquals(d, p.getJson("prices/missing.json", default = d))
        assertFailsWith<NotFoundException> { p.getJson<Prices>("prices/missing.json") }
    }

    @Test fun streamFilesAndKeepTimes() = runBlocking<Unit> {
        val p = open(newApp())
        val src = tmp.newFile("big.bin")
        val data = ByteArray(3 * 1024 * 1024 + 17) { (it * 31).toByte() }
        src.writeBytes(data)
        src.setLastModified(1_700_000_000_000)
        val e = p.putFile("big/big.bin", src)
        assertEquals(data.size.toLong(), e.size)
        assertEquals(1_700_000_000.0, e.modified, 0.01)
        val dest = File(tmp.root, "out/sub/big.bin")
        val got = p.getFile("big/big.bin", dest)
        assertContentEquals(data, dest.readBytes())
        assertEquals(1_700_000_000_000, dest.lastModified() / 1000 * 1000)
        assertEquals(e.etag, got.etag)
        assertTrue(dest.parentFile.listFiles()!!.none { it.name.startsWith(".pocket-tmp") })
    }

    @Test fun changesFlowFollowsTheFeed() = runBlocking<Unit> {
        val app = newApp()
        val a = open(app, deviceId = "dev-aaaa-0001")
        val b = open(app, deviceId = "dev-bbbb-0002")
        a.pollWait = 2
        val got = async { withTimeout(15_000) { a.changes().take(4).toList() } }
        delay(300)
        b.put("f/1.txt", "1".toByteArray())
        b.put("secret.txt", "s".toByteArray(), bucket = "device")   // not visible to a
        b.move("f/1.txt", "f/2.txt")
        a.put("f/3.txt", "3".toByteArray())
        a.delete("f/3.txt")
        val list = got.await()
        assertEquals(listOf("put", "move", "put", "delete"), list.map { it.kind })
        assertEquals("f/2.txt", list[1].key)
        assertEquals(Location("shared", "f/1.txt"), list[1].from)
        assertEquals("dev-bbbb-0002", list[0].device)
        assertEquals("dev-aaaa-0001", list[2].device)
        assertNull(list[3].etag)
        assertTrue(list.zipWithNext().all { (x, y) -> y.seq > x.seq })
        assertTrue(list.all { it.source == "app" && it.bucket == "shared" })
    }

    @Test fun changesFromACursor() = runBlocking<Unit> {
        val p = open(newApp())
        val start = p.changesPage(null)
        assertTrue(start.changes.isEmpty())
        p.put("a", "a".toByteArray())
        p.put("b", "b".toByteArray())
        val page = p.changesPage(start.cursor, limit = 1)
        assertEquals(listOf("a"), page.changes.map { it.key })
        assertTrue(page.more)
        val rest = p.changesPage(page.cursor)
        assertEquals(listOf("b"), rest.changes.map { it.key })
        assertTrue(!rest.more)
        assertTrue(p.changesPage(rest.cursor).changes.isEmpty())
    }

    @Test fun backupKeepsTheNewest() = runBlocking<Unit> {
        val p = open(newApp())
        val db = tmp.newFile("app.db")
        var copies = 0
        val safe = SafeCopy { s, d -> copies++; s.copyTo(d, overwrite = true) }
        val base = System.currentTimeMillis() / 1000.0
        val made = mutableListOf<Entry>()
        for (i in 0 until 4) {
            p.clock = { base + i }
            db.writeText("version $i")
            made += p.backup(db, keep = 2, copy = safe)
        }
        p.clock = { System.currentTimeMillis() / 1000.0 }
        assertEquals(4, copies)
        assertTrue(made[0].key.matches(Regex("backups/app\\.db/\\d{8}-\\d{6}-app\\.db")), made[0].key)
        val left = p.list("backups/app.db/").toList().map { it.key }
        assertEquals(made.takeLast(2).map { it.key }, left)
        val dest = File(tmp.root, "restored.db")
        val r = p.restoreLatest("backups/app.db/", dest)
        assertEquals(made.last().key, r!!.key)
        assertEquals("version 3", dest.readText())
        assertNull(p.restoreLatest("backups/none/", dest))
    }

    @Test fun publishAndDownloadThroughTheSignedRoute() = runBlocking<Unit> {
        val p = open(newApp())
        assertNull(p.releases().latest())
        val apk = tmp.newFile("app-1.2.0-arm64.apk").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        val exe = tmp.newFile("app-1.2.0-x64.exe").apply { writeBytes(ByteArray(1000) { 7 }) }
        p.publish(apk, "1.2.0", notes = "Faster.", platform = "android-arm64")
        p.publish(exe, "1.2.0", notes = "Faster.", platform = "windows-x64")
        val latest = p.releases().latest()!!
        assertEquals("1.2.0", latest.version)
        assertEquals("Faster.", latest.notes)
        assertEquals(p.app, latest.app)
        assertEquals(2, latest.files.size)
        val android = p.releases().latest(platform = "android-arm64")!!
        assertEquals(listOf("app-1.2.0-arm64.apk"), android.files.map { it.name })
        assertNull(p.releases().latest(platform = "mac"))
        val dir = tmp.newFolder("dl")
        val f = android.download(dir)
        assertContentEquals(apk.readBytes(), f.readBytes())
        assertEquals("app-1.2.0-arm64.apk", f.name)

        p.publish(tmp.newFile("app-1.3.0-arm64.apk").apply { writeText("new") }, "1.3.0", platform = "android-arm64")
        assertEquals(listOf("app-1.3.0-arm64.apk"), p.releases().latest()!!.files.map { it.name })
        assertNotNull(p.stat("releases/1.2.0/app-1.2.0-arm64.apk"))
        assertFailsWith<PocketException> { p.publish(apk, "bad version") }
    }

    @Test fun downloadRefusesAChecksumMismatch() = runBlocking<Unit> {
        val p = open(newApp())
        val apk = tmp.newFile("a.apk").apply { writeText("real") }
        p.publish(apk, "1.0")
        val text = p.get("releases/latest.json").decodeToString().replace(Signer.sha256(apk), "0".repeat(64))
        p.put("releases/latest.json", text.toByteArray())
        val e = assertFailsWith<PocketException> { p.releases().latest()!!.download(File(tmp.root, "x.apk")) }
        assertEquals("ChecksumMismatch", e.code)
        assertTrue(!File(tmp.root, "x.apk").exists())
    }

    @Test fun shareAndQuota() = runBlocking<Unit> {
        val p = open(newApp())
        p.put("s.txt", "share me".toByteArray())
        val s = p.share("s.txt", expires = 3600, maxDownloads = 2)
        assertEquals("s.txt", s.key)
        assertEquals(2, s.maxDownloads)
        assertEquals(3600.0, s.expires - s.created, 1.0)
        assertFailsWith<NotFoundException> { p.share("missing.txt") }
        val q = p.quota()
        assertEquals(p.app, q.app)
        assertEquals(8, q.usedApp)
        assertTrue(q.free > 0)
    }

    @Test fun appInfo() = runBlocking<Unit> {
        val p = open(newApp())
        val info = p.appInfo()
        assertEquals(p.app, info.app)
        assertEquals("dev-test-0001", info.deviceId)
        assertTrue("shared" in info.buckets && "device" in info.buckets)
        assertTrue(p.releasesUrl()!!.endsWith("/_pocket/pub/${p.app}/"))
    }

    @Test fun releasePathsAreRawAndPlatformAnyMatches() = runBlocking<Unit> {
        val p = open(newApp())
        val any = tmp.newFile("my tool.zip").apply { writeText("zip") }
        val win = tmp.newFile("my tool.exe").apply { writeText("exe") }
        p.publish(any, "5.9.1+42", platform = "any")
        p.publish(win, "5.9.1+42", platform = "windows-x64")
        val text = p.get("releases/latest.json").decodeToString()
        assertTrue("\"path\":\"5.9.1+42/my tool.zip\"" in text, text)
        val rel = p.releases().latest(platform = "android-arm64")!!
        assertEquals(listOf("my tool.zip"), rel.files.map { it.name })
        assertEquals(listOf("my tool.exe", "my tool.zip"), p.releases().latest("windows-x64")!!.files.map { it.name }.sorted())
        assertEquals("zip", rel.download(File(tmp.root, "got.zip")).readText())
    }

    @Test fun backupKeepsTheNewFileWhenTheClockIsBehind() = runBlocking<Unit> {
        val p = open(newApp())
        val db = tmp.newFile("c.db")
        val base = System.currentTimeMillis() / 1000.0
        for (i in 0 until 3) { p.clock = { base + i }; db.writeText("v$i"); p.backup(db, keep = 2) }
        p.clock = { base - 600 }                         // ten minutes behind (still inside the signing skew)
        db.writeText("behind")
        val e = p.backup(db, keep = 2)
        p.clock = { System.currentTimeMillis() / 1000.0 }
        val left = p.list("backups/c.db/").toList().map { it.key }
        assertEquals(2, left.size)
        assertTrue(e.key in left, left.toString())
        assertContentEquals("behind".toByteArray(), p.get(e.key))
    }

    @Test fun sharesOffIsFeatureOff() {
        assertTrue(errorFor("SharesOff", 403, "off") is FeatureOffException)
    }

    @Test fun privateFilesAreOwnerOnly() {
        val f = File(tmp.root, "sub/key.json")
        writePrivate(f, "secret")
        assertEquals("secret", f.readText())
        writePrivate(f, "again")
        assertEquals("again", f.readText())
        assertTrue(f.parentFile.listFiles()!!.none { it.name.startsWith(".tmp-") })
        val path = f.toPath()
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path)))
        }
    }
}
