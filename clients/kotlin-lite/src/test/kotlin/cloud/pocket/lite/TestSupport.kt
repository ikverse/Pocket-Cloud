package cloud.pocket.lite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

/** One fake server for the whole test run; each test uses its own app name, so tests do not see each other. */
object TestServer {
    val fake: FakePocketServer by lazy { FakePocketServer() }
    val url: String get() = fake.url
    const val SECRET = "fake-enroll-secret"
    private val http = OkHttpClient()
    private val n = AtomicInteger()

    fun newApp(): String = "t${ProcessHandle.current().pid() % 1000}-${n.incrementAndGet()}"

    /** A `/_fake/` control (fake-only tests). */
    fun control(path: String, body: String = "{}"): JsonObject {
        val req = Request.Builder().url(url + path).post(body.toRequestBody("application/json".toMediaType())).build()
        return http.newCall(req).execute().use { JSON.parseToJsonElement(it.body.string()).jsonObject }
    }

    fun fakeGet(path: String): JsonObject =
        http.newCall(Request.Builder().url(url + path).build()).execute().use { JSON.parseToJsonElement(it.body.string()).jsonObject }

    suspend fun open(app: String, store: CredentialStore = MemoryCredentialStore(), deviceId: String = "dev-test-0001",
                     deviceName: String = "Test box"): PocketApp =
        Pocket.connect(app = app, endpoint = url, enrollSecret = SECRET, deviceId = deviceId, deviceName = deviceName,
            store = store).also { it.session.backoffMs = longArrayOf(20, 40, 80) }
}
