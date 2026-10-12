package cloud.pocket

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.decodeToString
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The Kotlin library against the real Pocket Cloud server (started by server/tests/serve_for_tests.py). */
class PocketTest {
    companion object {
        private lateinit var server: Process
        private lateinit var out: BufferedReader
        private lateinit var input: Writer
        lateinit var endpoint: String
        lateinit var secret: String

        @BeforeClass @JvmStatic
        fun start() {
            val root = System.getProperty("pocket.root")
            server = ProcessBuilder("python", "$root/server/tests/serve_for_tests.py")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            out = server.inputStream.bufferedReader()
            input = server.outputStream.writer()
            val info = Json.parse(out.readLine()) as Map<*, *>
            endpoint = info["endpoint"] as String
            secret = info["secret"] as String
        }

        @AfterClass @JvmStatic
        fun stop() {
            input.write("quit\n"); input.flush()
            server.waitFor(60, TimeUnit.SECONDS)
        }

        fun command(c: String): Map<*, *> {
            input.write(c + "\n"); input.flush()
            return Json.parse(out.readLine()) as Map<*, *>
        }
    }

    private fun home(): File = Files.createTempDirectory("pocket-kt-").toFile().also {
        File(it, "config").writeText("[pocket]\nendpoint = $endpoint\nenroll_secret = $secret\n")
    }

    private suspend fun S3Client.text(bucket: String, key: String): String =
        getObject(GetObjectRequest { this.bucket = bucket; this.key = key }) { it.body!!.decodeToString() }

    private suspend fun S3Client.put(bucket: String, key: String, text: String) {
        putObject { this.bucket = bucket; this.key = key; body = ByteStream.fromString(text) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun devices(app: String) = command("devices $app")["devices"] as List<Map<String, Any?>>

    @Test
    fun firstConnectEnrollsAndBothBucketsWork() = runBlocking {
        val home = home()
        Pocket.connect("kt-first", home = home, deviceName = "Kotlin test").use { s3 ->
            s3.put("shared", "hello.txt", "hello")
            s3.put("device", "mine.txt", "private")
            assertEquals("hello", s3.text("shared", "hello.txt"))
            assertEquals("private", s3.text("device", "mine.txt"))
        }
        val id = File(home, "device_id").readText().trim()
        assertTrue(id.startsWith("dev-"))
        assertEquals(listOf(id to "Kotlin test"), devices("kt-first").map { it["uid"] to it["name"] })
        home.deleteRecursively()
        Unit
    }

    @Test
    fun laterConnectsReuseTheSavedKey() = runBlocking {
        val home = home()
        repeat(3) { Pocket.connect("kt-reuse", home = home).use { it.listObjectsV2 { bucket = "shared" } } }
        assertEquals(1L, command("enrolls kt-reuse")["count"])
        home.deleteRecursively()
        Unit
    }

    @Test
    fun recoversWhenTheDeviceAddressChanges() = runBlocking {
        val home = home()
        Pocket.connect("kt-moved", home = home).use { s3 ->
            s3.put("shared", "a", "1")
            command("move kt-moved")
            assertEquals("1", s3.text("shared", "a"))
        }
        assertEquals("127.0.0.1", devices("kt-moved")[0]["ip"])
        assertEquals(2L, command("enrolls kt-moved")["count"])
        home.deleteRecursively()
        Unit
    }

    @Test
    fun recoversWhenTheKeyIsReplaced() = runBlocking {
        val home = home()
        Pocket.connect("kt-rotated", home = home).use { s3 ->
            s3.put("shared", "a", "1")
            val newKey = (command("rotate kt-rotated")["keys"] as List<*>)[0]
            s3.put("shared", "b", "2")
            val saved = Json.parse(File(home, "apps/kt-rotated.json").readText()) as Map<*, *>
            assertEquals(newKey, saved["access_key"])
            assertEquals("2", s3.text("shared", "b"))
        }
        home.deleteRecursively()
        Unit
    }

    @Test
    fun temporaryLink() = runBlocking {
        val home = home()
        Pocket.connect("kt-link", home = home).use { s3 ->
            s3.put("shared", "report.txt", "by link")
            val req = s3.presignGetObject(GetObjectRequest { bucket = "shared"; key = "report.txt" }, 60.seconds)
            assertEquals("by link", URI.create(req.url.toString()).toURL().readText())
        }
        home.deleteRecursively()
        Unit
    }

    @Test
    fun androidStyleSettingsInCode() = runBlocking {
        // No machine setup file: an Android app passes everything in code and keeps its files privately.
        val private = Files.createTempDirectory("pocket-android-").toFile()
        Pocket.connect("kt-android", endpoint = endpoint, enrollSecret = secret, home = File(private, "pocket"),
            deviceName = "Galaxy Note9").use { s3 ->
            s3.put("device", "state.json", "{}")
            assertEquals("{}", s3.text("device", "state.json"))
        }
        assertEquals("Galaxy Note9", devices("kt-android")[0]["name"])
        assertTrue(File(private, "pocket/apps/kt-android.json").isFile)
        private.deleteRecursively()
        Unit
    }

    @Test
    fun clearErrors() {
        val empty = Files.createTempDirectory("pocket-kt-empty-").toFile()
        val home = home()
        try {
            val notSetUp = assertFailsWith<PocketException> { runBlocking { Pocket.connect("kt-x", home = empty) } }
            assertTrue("not set up" in notSetUp.message!!)
            val refused = assertFailsWith<PocketException> { runBlocking { Pocket.connect("kt-x", home = home, enrollSecret = "wrong") } }
            assertTrue("refused" in refused.message!!)
            val down = assertFailsWith<PocketException> {
                runBlocking { Pocket.connect("kt-x", home = home, endpoint = "http://127.0.0.1:9") }
            }
            assertTrue("can't be reached" in down.message!!)
        } finally {
            empty.deleteRecursively()
            home.deleteRecursively()
        }
    }
}
