package cloud.pocket.lite

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@PublishedApi internal val JSON =Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** A request body: none, bytes in memory, or a file streamed from disk (hashed first, then sent). */
internal sealed class Body {
    abstract val sha256: String
    abstract fun toRequestBody(): RequestBody?

    object None : Body() {
        override val sha256 = Signer.EMPTY_SHA256
        override fun toRequestBody(): RequestBody? = null
    }

    class Bytes(val data: ByteArray, val contentType: String?) : Body() {
        override val sha256 = Signer.sha256(data)
        override fun toRequestBody() = data.toRequestBody(contentType?.toMediaTypeOrNull())
    }

    class FromFile(val file: File, val contentType: String?) : Body() {
        val length = file.length()
        override val sha256 = Signer.sha256(file)
        override fun toRequestBody() = object : RequestBody() {
            override fun contentType(): MediaType? = this@FromFile.contentType?.toMediaTypeOrNull()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) { file.source().use { sink.writeAll(it) } }
        }
    }
}

/**
 * One app on one device: its key, and the signed request pipeline (re-enrollment and retries) every method goes
 * through.
 */
internal class Session(
    val app: String,
    val endpoint: String,
    private val enrollSecret: String,
    val deviceId: String,
    val deviceName: String,
    private val store: CredentialStore,
    val timeoutMs: Long,
) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs.coerceAtMost(15_000), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        // OkHttp itself repeats a request once on "503 + Retry-After: 0", which would send an unconditional
        // write twice. Move the header aside so only this library's retry rules apply.
        .addNetworkInterceptor { chain ->
            val r = chain.proceed(chain.request())
            val after = r.header("Retry-After")
            if (r.code == 503 && after != null) {
                r.newBuilder().removeHeader("Retry-After").header(RETRY_AFTER, after).build()
            } else r
        }
        .build()

    private val base: HttpUrl = endpoint.toHttpUrl()

    /** Unix seconds; tests move it. */
    @Volatile var clock: () -> Double = { System.currentTimeMillis() / 1000.0 }

    /** Backoff before retry 1, 2, 3 in ms; tests shorten it. */
    @Volatile var backoffMs = longArrayOf(500, 1000, 2000)

    @Volatile lateinit var key: DeviceKey

    fun url(path: String, query: String = ""): HttpUrl =
        base.newBuilder().encodedPath(path).encodedQuery(query.ifEmpty { null }).build()

    /** Use the saved key when it belongs to this endpoint and device, else enroll. */
    suspend fun start() {
        val saved = runCatching { store.loadKey(app) }.getOrNull()
        if (saved != null && saved.endpoint == endpoint && saved.deviceId == deviceId) key = saved else enroll()
    }

    private val enrollLock = kotlinx.coroutines.sync.Mutex()

    /** Enroll (again) and save the key. Requests failing together share one enrollment. */
    suspend fun enroll(staleKey: DeviceKey? = null): DeviceKey = enrollLock.lock().let {
        try {
            if (staleKey != null && this::key.isInitialized && key !== staleKey) return key
            val body = buildJsonObject {
                put("secret", enrollSecret); put("app", app); put("device_id", deviceId); put("device_name", deviceName)
            }.toString().toByteArray()
            val req = Request.Builder().url(url("/_pocket/enroll"))
                .post(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
            val m = client.newCall(req).await().use { resp ->
                val text = resp.body.string()
                val obj = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
                if (!resp.isSuccessful) {
                    val code = obj?.str("error") ?: "AccessDenied"
                    val msg = obj?.str("message") ?: "HTTP ${resp.code}"
                    throw errorFor(code, resp.code, "Pocket Cloud refused to enroll $app: $msg")
                }
                obj ?: throw PocketException("BadResponse", resp.code, "Enrollment answer was not JSON.")
            }
            val k = DeviceKey(endpoint, deviceId, m.str("access_key")!!, m.str("secret_key")!!,
                m.str("region") ?: "us-east-1")
            store.saveKey(app, k)
            key = k
            k
        } finally {
            enrollLock.unlock()
        }
    }

    /**
     * Send a signed request and return a successful (2xx or 304) response, which the caller closes.
     * [retry]: may be retried on no connection / 503 (reads and conditional writes).
     */
    suspend fun call(
        method: String, path: String, query: String = "", body: Body = Body.None,
        headers: Map<String, String> = emptyMap(), retry: Boolean = false, extraReadMs: Long = 0,
    ): Response {
        var attempt = 0
        var reenrolled = false
        val c = if (extraReadMs > 0) client.newBuilder().readTimeout(timeoutMs + extraReadMs, TimeUnit.MILLISECONDS).build()
                else client
        while (true) {
            val k = key
            val u = url(path, query)
            val signed = Signer.sign(method, u.encodedPath, u.encodedQuery ?: "", body.sha256, k.accessKey,
                k.secretKey, clock().toLong())
            val rb = Request.Builder().url(u)
            signed.forEach { (n, v) -> rb.header(n, v) }
            if (body !is Body.None) rb.header("x-pocket-content-sha256", body.sha256)
            headers.forEach { (n, v) -> rb.header(n, v) }
            val reqBody = body.toRequestBody()
            rb.method(method, reqBody ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody() else null)
            val resp = try {
                c.newCall(rb.build()).await()
            } catch (e: IOException) {
                if (retry && attempt < backoffMs.size) { delay(backoffMs[attempt++]); continue }
                throw UnreachableException("Unreachable", 0, "Pocket Cloud can't be reached at $endpoint: ${e.message}", e)
            }
            if (resp.isSuccessful || resp.code == 304) return resp
            val err = readError(resp, method)
            val moved = resp.code == 403 && resp.header("x-pocket-error") == "device-address-changed"
            if ((moved || err.code == "InvalidAccessKeyId") && !reenrolled) {
                reenrolled = true
                enroll(k)
                continue
            }
            if (err is UnreachableException && retry && attempt < backoffMs.size) {
                val wait = retryAfter(resp)?.toLongOrNull()?.times(1000) ?: 0
                delay(maxOf(backoffMs[attempt++], wait))
                continue
            }
            throw err
        }
    }

    /** A key-less GET (publish folder). Errors map the same way. */
    suspend fun plainGet(url: String): Response {
        val resp = try {
            client.newCall(Request.Builder().url(url).build()).await()
        } catch (e: IOException) {
            throw UnreachableException("Unreachable", 0, "Pocket Cloud can't be reached at $url: ${e.message}", e)
        }
        if (resp.isSuccessful) return resp
        throw readError(resp, "GET")
    }

    /** `GET /_pocket/health`: the feature list (a 0.2 server without one has the old five). */
    suspend fun health(): Set<String> {
        val resp = try {
            client.newCall(Request.Builder().url(url("/_pocket/health")).build()).await()
        } catch (e: IOException) {
            throw UnreachableException("Unreachable", 0, "Pocket Cloud can't be reached at $endpoint: ${e.message}", e)
        }
        return resp.use {
            if (!it.isSuccessful) throw readError(it, "GET")
            val obj = JSON.parseToJsonElement(it.body.string()).jsonObject
            val list = obj["features"]?.let { f -> (f as? kotlinx.serialization.json.JsonArray)?.mapNotNull { x -> x.jsonPrimitive.contentOrNull } }
            list?.toSet() ?: setOf("quota", "conflicts", "mirror-report", "share", "push")
        }
    }

    private fun readError(resp: Response, method: String): PocketException = resp.use {
        val text = if (method == "HEAD") "" else runCatching { it.body.string() }.getOrDefault("")
        val obj = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = obj?.str("error") ?: it.header("x-pocket-error-code") ?: when (it.code) {
            503 -> "ServiceUnavailable"
            else -> "HTTP${it.code}"
        }
        val msg = obj?.str("message") ?: "Pocket Cloud answered ${it.code} $code."
        errorFor(code, it.code, msg, cursor = obj?.str("cursor"), retryAfter = retryAfter(it)?.toIntOrNull())
    }
}

private const val RETRY_AFTER = "x-pocket-lib-retry-after"

private fun retryAfter(r: Response): String? = r.header(RETRY_AFTER) ?: r.header("Retry-After")

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

/** Run an OkHttp call without blocking a thread; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
