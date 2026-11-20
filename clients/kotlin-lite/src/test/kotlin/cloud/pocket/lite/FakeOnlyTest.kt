package cloud.pocket.lite

import cloud.pocket.lite.TestServer.control
import cloud.pocket.lite.TestServer.fakeGet
import cloud.pocket.lite.TestServer.newApp
import cloud.pocket.lite.TestServer.open
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests that need the fake's `/_fake/` controls (failures, clocks, switches). Fake only. */
class FakeOnlyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before @After fun reset() { control("/_fake/reset") }

    private fun requests(): List<JsonObject> =
        (fakeGet("/_fake/requests")["requests"] as JsonArray).map { it as JsonObject }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun readsAreRetriedOn503() = runBlocking<Unit> {
        val p = open(newApp())
        p.put("r.txt", "r".toByteArray())
        control("/_fake/fail", """{"route": "GET /_pocket/files", "status": 503, "code": "ServiceUnavailable", "times": 2}""")
        assertContentEquals("r".toByteArray(), p.get("r.txt"))
        val gets = requests().filter { it.s("method") == "GET" && it.s("path")!!.startsWith("/_pocket/files") }
        assertEquals(listOf("503", "503", "200"), gets.map { it.s("status") })
    }

    @Test fun retriesGiveUpAsUnreachable() = runBlocking<Unit> {
        val p = open(newApp())
        control("/_fake/fail", """{"route": "GET /_pocket/quota", "status": 503, "code": "ServiceUnavailable", "times": 9}""")
        val e = assertFailsWith<UnreachableException> { p.quota() }
        assertEquals(503, e.status)
        assertEquals(4, requests().count { it.s("path") == "/_pocket/quota" })
    }

    @Test fun unconditionalWritesAreNotRetried() = runBlocking<Unit> {
        val p = open(newApp())
        control("/_fake/fail", """{"route": "PUT /_pocket/files", "status": 503, "code": "ServiceUnavailable", "times": 1}""")
        assertFailsWith<UnreachableException> { p.put("w.txt", "w".toByteArray()) }
        assertEquals(1, requests().count { it.s("method") == "PUT" })
        // a conditional write is
        control("/_fake/fail", """{"route": "PUT /_pocket/files", "status": 503, "code": "ServiceUnavailable", "times": 1}""")
        p.putBody("w.txt", Body.Bytes("w".toByteArray(), null), "shared", null, ifNoneMatch = true)
        assertEquals(3, requests().count { it.s("method") == "PUT" })
    }

    @Test fun reenrollsWhenTheDeviceMoved() = runBlocking<Unit> {
        val p = open(newApp())
        control("/_fake/moved", """{"times": 1}""")
        p.put("m.txt", "m".toByteArray())
        val log = requests().map { "${it.s("method")} ${it.s("path")} ${it.s("status")}" }
        assertEquals(listOf("PUT /_pocket/files/shared/m.txt 403", "POST /_pocket/enroll 200", "PUT /_pocket/files/shared/m.txt 200"),
            log.takeLast(3))
        // only once: two moves in a row give up
        control("/_fake/moved", """{"times": 2}""")
        assertFailsWith<AccessDeniedException> { p.get("m.txt") }
    }

    @Test fun reenrollsOnAnUnknownKey() = runBlocking<Unit> {
        val app = newApp()
        val store = MemoryCredentialStore()
        open(app, store)
        val good = store.loadKey(app)!!
        store.saveKey(app, good.copy(accessKey = "AKFAKE0000000000", secretKey = "wrong"))
        val p = open(app, store)
        p.put("k.txt", "k".toByteArray())
        assertEquals(good, store.loadKey(app))
        assertTrue(requests().any { it.s("code") == "InvalidAccessKeyId" })
    }

    @Test fun signatureAndDigestAreChecked() = runBlocking<Unit> {
        val app = newApp()
        val store = MemoryCredentialStore()
        val p = open(app, store)
        val key = store.loadKey(app)!!
        val http = OkHttpClient()
        val path = "/_pocket/files/shared/d.txt"
        val claimed = Signer.sha256("hello".toByteArray())
        val h = Signer.sign("PUT", path, "", claimed, key.accessKey, key.secretKey, System.currentTimeMillis() / 1000)
        val rb = Request.Builder().url(TestServer.url + path).put("other".toByteArray().toRequestBody())
            .header("x-pocket-content-sha256", claimed)
        h.forEach { (k, v) -> rb.header(k, v) }
        http.newCall(rb.build()).execute().use {
            assertEquals(400, it.code)
            assertTrue("BadDigest" in it.body.string())
        }
        val bad = Request.Builder().url(TestServer.url + "/_pocket/quota").header("x-pocket-key", key.accessKey)
            .header("x-pocket-date", (System.currentTimeMillis() / 1000).toString()).header("x-pocket-sign", "00").build()
        http.newCall(bad).execute().use { assertTrue("SignatureDoesNotMatch" in it.body.string()) }
        assertTrue(p.stat("d.txt") == null)
    }

    @Test fun clockSkewIsRefused() = runBlocking<Unit> {
        val p = open(newApp())
        control("/_fake/clock", """{"now": 1795000000}""")
        assertFailsWith<AccessDeniedException> { p.quota() }.also { assertEquals("RequestTimeTooSkewed", it.code) }
        p.clock = { 1795000000.0 }
        p.quota()
    }

    @Test fun missingFeatures() = runBlocking<Unit> {
        control("/_fake/features", """{"features": ["quota", "share", "files"]}""")
        val p = open(newApp())
        assertFailsWith<FeatureOffException> { p.notify("hi") }.also { assertEquals("FeatureMissing", it.code) }
        assertFailsWith<FeatureOffException> { p.move("a", "b") }.also { assertEquals("FeatureMissing", it.code) }
        assertFailsWith<FeatureOffException> { p.changes().first() }
        // sync works without the change feed (full listing every time)
        val dir = tmp.newFolder("nf")
        File(dir, "a.txt").writeText("a")
        assertEquals(listOf("a.txt"), p.sync(dir).uploaded)
        p.put("b.txt", "b".toByteArray())
        assertEquals(listOf("b.txt"), p.sync(dir).downloaded)
    }

    @Test fun readOnlyAndQuota() = runBlocking<Unit> {
        val app = newApp()
        val p = open(app)
        control("/_fake/app?app=$app", """{"read_only": true}""")
        assertFailsWith<ReadOnlyException> { p.put("x", "x".toByteArray()) }
        control("/_fake/app?app=$app", """{"read_only": false, "allocation": 10}""")
        p.put("x", "12345".toByteArray())
        assertFailsWith<QuotaExceededException> { p.put("y", "123456".toByteArray()) }
        assertEquals(10, p.quota().allocation)
    }

    @Test fun notifyRecordsAndRateLimits() = runBlocking<Unit> {
        val app = newApp()
        val p = open(app)
        assertFailsWith<FeatureOffException> { p.notify("off") }.also { assertEquals("FeatureOff", it.code) }
        control("/_fake/app?app=$app", """{"notify": true}""")
        val id = p.notify("6 new calls", title = "Evening calls", tags = listOf("chart_with_upwards_trend"), priority = 4, click = "egx://calls")
        assertTrue(id.isNotEmpty())
        val sent = (fakeGet("/_fake/notifications")["sent"] as JsonArray).map { it as JsonObject }
        assertEquals("6 new calls", sent[0].s("message"))
        assertEquals("Evening calls", sent[0].s("title"))
        assertEquals("4", sent[0].s("priority"))
        repeat(9) { p.notify("n$it") }
        val e = assertFailsWith<RateLimitedException> { p.notify("too many") }
        assertNotNull(e.retryAfter)
        assertTrue(e.retryAfter in 1..60)
        assertEquals(1, requests().count { it.s("code") == "RateLimited" })   // not retried
        assertFailsWith<PocketException> { p.notify("") }.also { assertEquals("BadRequest", it.code) }
    }

    @Test fun cursorExpired() = runBlocking<Unit> {
        val p = open(newApp())
        val start = p.changesPage(null).cursor
        p.put("a", "a".toByteArray())
        val mid = p.changesPage(start).cursor
        p.put("b", "b".toByteArray())
        control("/_fake/expire-changes", """{"before": "$mid"}""")
        val e = assertFailsWith<CursorExpiredException> { p.changesPage(start) }
        assertNotNull(e.cursor)
        assertEquals(listOf("b"), p.changesPage(mid).changes.map { it.key })
        assertFailsWith<CursorExpiredException> { p.changes(start).first() }
    }

    @Test fun syncResyncsOnCursorExpired() = runBlocking<Unit> {
        val p = open(newApp())
        val dir = tmp.newFolder("ce")
        File(dir, "a.txt").writeText("a")
        p.sync(dir)
        p.put("b.txt", "b".toByteArray())
        val now = p.changesPage(null).cursor
        control("/_fake/expire-changes", """{"before": "$now"}""")
        assertEquals(listOf("b.txt"), p.sync(dir).downloaded)
        assertEquals("b", File(dir, "b.txt").readText())
    }

    @Test fun publishFolderIsKeyless() = runBlocking<Unit> {
        val app = newApp()
        val p = open(app)
        control("/_fake/app?app=$app", """{"publish": true}""")
        val apk = tmp.newFile("x-2.0.apk").apply { writeText("apk bytes") }
        p.publish(apk, "2.0", platform = "android-arm64")
        val latest = p.releases().latest()!!
        val f = latest.files.single()
        assertEquals("${TestServer.url}/_pocket/pub/$app/2.0/x-2.0.apk", f.url)
        val out = latest.download(File(tmp.root, "got.apk"))
        assertEquals("apk bytes", out.readText())
        val pubReads = requests().filter { it.s("path")!!.startsWith("/_pocket/pub/") }
        assertEquals(listOf("/_pocket/pub/$app/latest.json", "/_pocket/pub/$app/2.0/x-2.0.apk"), pubReads.map { it.s("path") })
        // switched off: the same 404 as an unknown app
        control("/_fake/app?app=$app", """{"publish": false}""")
        OkHttpClient().newCall(Request.Builder().url(f.url!!).build()).execute().use { assertEquals(404, it.code) }
    }

    @Test fun serverUnreachableAtConnectStillOpensWithASavedKey() = runBlocking<Unit> {
        val app = newApp()
        val store = MemoryCredentialStore()
        open(app, store)
        val dead = FakePocketServer().also { it.close() }
        store.saveKey(app, store.loadKey(app)!!.copy(endpoint = dead.url))
        val p = Pocket.connect(app = app, endpoint = dead.url, enrollSecret = TestServer.SECRET, deviceId = "dev-test-0001", store = store)
        p.session.backoffMs = longArrayOf(1, 1, 1)
        assertFailsWith<UnreachableException> { p.get("x") }.also { assertEquals("Unreachable", it.code) }
    }

    @Test fun releasesFallBackToTheSignedRoute() = runBlocking<Unit> {
        val app = newApp()
        val p = open(app)
        control("/_fake/app?app=$app", """{"publish": true}""")
        val f = tmp.newFile("a b.apk").apply { writeText("bytes") }
        p.publish(f, "1.0+7", platform = "android-arm64")
        val keyless = p.releases().latest()!!.files.single()
        assertEquals("${TestServer.url}/_pocket/pub/$app/1.0%2B7/a%20b.apk", keyless.url)
        assertEquals("bytes", keyless.download(File(tmp.root, "k.apk")).readText())
        // the key-less folder refuses (as on the LAN address): latest.json and the file come through the signed route
        control("/_fake/fail", """{"route": "GET /_pocket/pub/", "status": 404, "code": "NotFound", "times": 2}""")
        val rel = p.releases().latest()!!
        assertEquals("bytes", rel.download(File(tmp.root, "s.apk")).readText())
        val signed = requests().filter { it.s("path")!!.startsWith("/_pocket/files/shared/releases/") && it.s("method") == "GET" }
        assertEquals(listOf("/_pocket/files/shared/releases/latest.json", "/_pocket/files/shared/releases/1.0%2B7/a%20b.apk"),
            signed.map { it.s("path") }.takeLast(2))
    }
}
