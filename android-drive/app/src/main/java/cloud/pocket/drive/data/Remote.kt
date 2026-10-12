package cloud.pocket.drive.data

import android.content.Context
import android.webkit.MimeTypeMap
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.abortMultipartUpload
import aws.sdk.kotlin.services.s3.completeMultipartUpload
import aws.sdk.kotlin.services.s3.copyObject
import aws.sdk.kotlin.services.s3.createMultipartUpload
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.deleteObjects
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.listParts
import aws.sdk.kotlin.services.s3.model.CompletedMultipartUpload
import aws.sdk.kotlin.services.s3.model.CompletedPart
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NoSuchUpload
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.putObject
import aws.sdk.kotlin.services.s3.uploadPart
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toInputStream
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import cloud.pocket.Pocket
import cloud.pocket.PocketException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlin.time.Duration.Companion.hours

/** One row of a folder listing. [key] is the object key, with a trailing slash for folders. */
data class Entry(
    val name: String,
    val key: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,        // ms since 1970
    val etag: String? = null,
    /** What the user sees as the location: the same as [key], except inside a vault where the key holds encrypted names. */
    val path: String = key,
)

/** What the server said about itself on its health page. */
data class Health(val version: String, val features: Set<String>, val lan: String)

/** The facts about a downloaded object. */
data class Fetched(val size: Long, val modified: Long)

/** The facts about an object found by a HEAD request. */
data class Head(val size: Long, val modified: Long, val etag: String?, val mtime: Long)

/**
 * Everything the app asks of the Pocket Cloud server over S3: listing, transfers, copy and delete. The enrollment,
 * signing and re-enrollment are done by the Pocket client library.
 */
class Remote(private val app: Context, val settings: Settings) {
    private val lock = Mutex()
    private var client: S3Client? = null
    private var clientFor: String? = null
    private var clientSecret: String? = null

    /** Folder of the Pocket library's saved device id and key. */
    val home: File = File(app.filesDir, "pocket")

    // ---------------------------------------------------------------- connecting

    /** The S3 client for the address in use (enrolling this device the first time). */
    suspend fun s3(): S3Client = lock.withLock {
        val endpoint = settings.endpointInUse.ifBlank { settings.endpoint() }
        val secret = settings.secret ?: throw PocketException("This device is not set up. Open Settings and enter the server address and secret.")
        if (endpoint.isBlank()) throw PocketException("No server address is set.")
        val current = client
        if (current != null && clientFor == endpoint && clientSecret == secret) return@withLock current
        current?.close()
        client = null
        val created = withContext(Dispatchers.IO) {
            Pocket.connect(APP, endpoint, secret, null, settings.deviceName, home)
        }
        client = created
        clientFor = endpoint
        clientSecret = secret
        created
    }

    /** Close the client; the next call connects again (after a change of address or secret). */
    suspend fun reset() = lock.withLock {
        client?.close()
        client = null
        clientFor = null
    }

    /** Ask a server address for its health page. Null when it can't be reached. */
    suspend fun probe(endpoint: String, timeoutMs: Int = 4000): Health? = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext null
        val conn = try {
            (URI.create(endpoint.trimEnd('/') + "/_pocket/health").toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
        } catch (e: Exception) {
            return@withContext null
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val json = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
            if (!json.optBoolean("ok", true)) return@withContext null
            val features = json.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            Health(json.optString("version"), features, json.optString("lan_endpoint"))
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pick the best reachable address (the home-network one when the server has it and it answers, otherwise the
     * primary one), remember the server's features, and make sure this device is enrolled. Returns the address.
     */
    suspend fun connect(): String {
        val primary = settings.endpoint()
        if (primary.isBlank()) throw PocketException("No server address is set.")
        var chosen = primary
        var health = probe(primary)
        val lan = health?.lan?.takeIf { it.isNotBlank() } ?: settings.lanEndpoint
        if (lan.isNotBlank() && lan != primary && (health == null || "lan" in health.features)) {
            probe(lan, 1500)?.let { chosen = lan; health = it }
        }
        val h = health ?: throw PocketException("Pocket Cloud can't be reached at $primary. Is the phone on, and is Tailscale connected?")
        settings.features = h.features + settings.forcedFeatures
        settings.serverVersion = h.version
        settings.lanEndpoint = h.lan
        if (settings.endpointInUse != chosen) reset()
        settings.endpointInUse = chosen
        s3()
        settings.enrolled = true
        return chosen
    }

    // ---------------------------------------------------------------- listing

    /** The folders and files directly inside [prefix] ("" for the top). */
    suspend fun list(bucket: String, prefix: String): List<Entry> {
        val s3 = s3()
        val out = mutableListOf<Entry>()
        var token: String? = null
        do {
            val r = s3.listObjectsV2 {
                this.bucket = bucket
                this.prefix = prefix
                delimiter = "/"
                continuationToken = token
            }
            r.commonPrefixes?.forEach { cp ->
                val p = cp.prefix ?: return@forEach
                out += Entry(Keys.name(p), p, true)
            }
            r.contents?.forEach { o ->
                val key = o.key ?: return@forEach
                if (key == prefix || key.endsWith("/") || Keys.isHidden(key)) return@forEach       // a folder's own marker
                out += Entry(Keys.name(key), key, false, o.size ?: 0, o.lastModified?.epochMilliseconds ?: 0, o.eTag)
            }
            token = if (r.isTruncated == true) r.nextContinuationToken else null
        } while (token != null)
        return out
    }

    /** Every file under [prefix], at any depth. */
    suspend fun listAll(bucket: String, prefix: String): List<Entry> {
        val s3 = s3()
        val out = mutableListOf<Entry>()
        var token: String? = null
        do {
            val r = s3.listObjectsV2 {
                this.bucket = bucket
                this.prefix = prefix
                continuationToken = token
            }
            r.contents?.forEach { o ->
                val key = o.key ?: return@forEach
                if (key.endsWith("/")) return@forEach
                out += Entry(Keys.name(key), key, false, o.size ?: 0, o.lastModified?.epochMilliseconds ?: 0, o.eTag)
            }
            token = if (r.isTruncated == true) r.nextContinuationToken else null
        } while (token != null)
        return out
    }

    suspend fun head(bucket: String, key: String): Head? = try {
        val r = s3().headObject { this.bucket = bucket; this.key = key }
        val mtime = r.metadata?.entries?.firstOrNull { it.key.equals("mtime", true) }?.value?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0
        Head(r.contentLength ?: 0, r.lastModified?.epochMilliseconds ?: 0, r.eTag, mtime)
    } catch (e: S3Exception) {
        if (statusOf(e) == 404) null else throw e
    }

    // ---------------------------------------------------------------- changing things

    suspend fun createFolder(bucket: String, prefix: String) {
        val folder = Keys.folder(prefix)
        s3().putObject { this.bucket = bucket; key = folder + Keys.KEEP; body = ByteStream.fromBytes(ByteArray(0)) }
    }

    suspend fun delete(bucket: String, key: String) {
        s3().deleteObject { this.bucket = bucket; this.key = key }
    }

    /** Delete many files at once (the server takes up to 1000 per request). */
    suspend fun deleteMany(bucket: String, keys: List<String>, onProgress: (Int) -> Unit = {}) {
        val s3 = s3()
        var done = 0
        keys.chunked(500).forEach { chunk ->
            s3.deleteObjects {
                this.bucket = bucket
                delete { objects = chunk.map { k -> ObjectIdentifier { key = k } }; quiet = true }
            }
            done += chunk.size
            onProgress(done)
        }
    }

    suspend fun copy(bucket: String, from: String, to: String) {
        s3().copyObject { this.bucket = bucket; key = to; copySource = Keys.copySource(bucket, from) }
    }

    /** Move one file: the server's own move when it has one, otherwise a copy and a delete. */
    suspend fun move(bucket: String, from: String, to: String) {
        copy(bucket, from, to)
        delete(bucket, from)
    }

    // ---------------------------------------------------------------- uploading

    suspend fun putSmall(bucket: String, key: String, bytes: ByteArray, mtime: Long) {
        s3().putObject {
            this.bucket = bucket
            this.key = key
            body = ByteStream.fromBytes(bytes)
            contentType = mimeOf(key)
            if (mtime > 0) metadata = mapOf("mtime" to seconds(mtime))
        }
    }

    suspend fun startMultipart(bucket: String, key: String, mtime: Long): String {
        val r = s3().createMultipartUpload {
            this.bucket = bucket
            this.key = key
            contentType = mimeOf(key)
            if (mtime > 0) metadata = mapOf("mtime" to seconds(mtime))
        }
        return r.uploadId ?: throw IOException("The server did not start the upload.")
    }

    suspend fun putPart(bucket: String, key: String, uploadId: String, number: Int, bytes: ByteArray): String {
        val r = s3().uploadPart {
            this.bucket = bucket
            this.key = key
            this.uploadId = uploadId
            partNumber = number
            body = ByteStream.fromBytes(bytes)
        }
        return r.eTag ?: throw IOException("The server did not confirm part $number.")
    }

    /** The parts the server holds for an upload (number -> etag), or null when the upload no longer exists. */
    suspend fun heldParts(bucket: String, key: String, uploadId: String): Map<Int, String>? {
        val s3 = s3()
        val out = linkedMapOf<Int, String>()
        var marker: String? = null
        try {
            do {
                val r = s3.listParts { this.bucket = bucket; this.key = key; this.uploadId = uploadId; partNumberMarker = marker }
                r.parts?.forEach { p -> if (p.partNumber != null && p.eTag != null) out[p.partNumber!!] = p.eTag!! }
                marker = if (r.isTruncated == true) r.nextPartNumberMarker else null
            } while (marker != null)
        } catch (e: NoSuchUpload) {
            return null
        } catch (e: S3Exception) {
            if (statusOf(e) == 404) return null else throw e
        }
        return out
    }

    suspend fun finishMultipart(bucket: String, key: String, uploadId: String, parts: Map<Int, String>) {
        s3().completeMultipartUpload {
            this.bucket = bucket
            this.key = key
            this.uploadId = uploadId
            multipartUpload = CompletedMultipartUpload {
                this.parts = parts.toSortedMap().map { (n, tag) -> CompletedPart { partNumber = n; eTag = tag } }
            }
        }
    }

    suspend fun abortMultipart(bucket: String, key: String, uploadId: String) {
        runCatching { s3().abortMultipartUpload { this.bucket = bucket; this.key = key; this.uploadId = uploadId } }
    }

    // ---------------------------------------------------------------- downloading

    /**
     * Download an object into [out], continuing from byte [start] when the file already holds that much.
     * [onBytes] is told how many bytes the file holds now.
     */
    suspend fun downloadTo(bucket: String, key: String, out: File, start: Long, onBytes: suspend (Long) -> Unit): Fetched {
        val s3 = s3()
        val request = GetObjectRequest {
            this.bucket = bucket
            this.key = key
            if (start > 0) range = "bytes=$start-"
        }
        return s3.getObject(request) { resp ->
            val body = resp.body ?: throw IOException("The server sent no data.")
            val total = start + (resp.contentLength ?: 0L)
            withContext(Dispatchers.IO) {
                out.parentFile?.mkdirs()
                body.toInputStream().use { input ->
                    FileOutputStream(out, start > 0).use { fos ->
                        val buf = ByteArray(256 * 1024)
                        var got = start
                        var reported = start
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            got += n
                            if (got - reported >= 512 * 1024) {
                                onBytes(got)
                                reported = got
                            }
                        }
                        fos.flush()
                        fos.fd.sync()
                        onBytes(got)
                    }
                }
            }
            Fetched(total, resp.lastModified?.epochMilliseconds ?: 0)
        }
    }

    /** A temporary address for reading one file, for the media player. Works from this device only. */
    suspend fun presign(bucket: String, key: String): String {
        val request = s3().presignGetObject(GetObjectRequest { this.bucket = bucket; this.key = key }, 6.hours)
        return request.url.toString()
    }

    companion object {
        const val APP = "drive"
        const val PART_SIZE = 8 * 1024 * 1024
        const val MULTIPART_ABOVE = 8 * 1024 * 1024L

        fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        fun statusOf(e: S3Exception): Int? = (e.sdkErrorMetadata.protocolResponse as? HttpResponse)?.status?.value

        /** A message a person can act on. */
        fun explain(e: Throwable): String = when (e) {
            is PocketException -> e.message ?: "Pocket Cloud refused the request."
            is S3Exception -> when (e.sdkErrorMetadata.errorCode) {
                "QuotaExceeded" -> e.sdkErrorMetadata.errorMessage ?: "There is no room left in this drive."
                "AccessDenied" -> e.sdkErrorMetadata.errorMessage ?: "Pocket Cloud refused this device."
                "NoSuchKey" -> "That file is no longer on the server."
                else -> e.sdkErrorMetadata.errorMessage ?: e.message ?: "The server reported an error."
            }
            is java.net.UnknownHostException -> "Can't find the server. Is Tailscale connected?"
            is java.net.ConnectException, is java.net.SocketTimeoutException ->
                "Pocket Cloud can't be reached. Is the phone on and Tailscale connected?"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
