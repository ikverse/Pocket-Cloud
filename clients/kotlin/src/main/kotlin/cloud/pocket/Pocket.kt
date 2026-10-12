package cloud.pocket

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.client.config.RequestHttpChecksumConfig
import aws.smithy.kotlin.runtime.client.config.ResponseHttpChecksumConfig
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.retries.policy.RetryDirective
import aws.smithy.kotlin.runtime.retries.policy.RetryErrorType
import aws.smithy.kotlin.runtime.retries.policy.RetryPolicy
import aws.smithy.kotlin.runtime.retries.policy.StandardRetryPolicy
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom

/**
 * Pocket Cloud for Kotlin and Android apps.
 *
 * ```
 * val s3 = Pocket.connect("egx")                       // a ready S3Client (AWS SDK for Kotlin)
 * s3.putObject { bucket = "shared"; key = "prices/today.json"; body = ByteStream.fromString(json) }
 * ```
 *
 * The first time an app connects from a device, this enrolls it with the server and saves the device's key.
 * Every app has two buckets: "shared" (all its devices) and "device" (this device only).
 *
 * On a computer the machine setup comes from `~/.pocket/config` or the environment variables POCKET_ENDPOINT and
 * POCKET_ENROLL_SECRET (shared with the Python and Node libraries). Android apps pass them in code from their
 * build settings, with a folder in the app's private storage:
 * ```
 * Pocket.connect("egx", endpoint = BuildConfig.POCKET_ENDPOINT, enrollSecret = BuildConfig.POCKET_SECRET,
 *                home = File(context.filesDir, "pocket"), deviceName = Build.MODEL)
 * ```
 */
object Pocket {
    /**
     * Connect [app] and return a ready S3Client for its "shared" and "device" buckets.
     * Every argument is optional on a computer that has the machine setup file.
     */
    suspend fun connect(
        app: String,
        endpoint: String? = null,
        enrollSecret: String? = null,
        deviceId: String? = null,
        deviceName: String? = null,
        home: File? = null,
    ): S3Client {
        val device = Device(app, home, endpoint, enrollSecret, deviceId, deviceName)
        return S3Client {
            region = device.creds.region
            endpointUrl = Url.parse(device.endpoint)
            forcePathStyle = true
            requestChecksumCalculation = RequestHttpChecksumConfig.WHEN_REQUIRED
            responseChecksumValidation = ResponseHttpChecksumConfig.WHEN_REQUIRED
            credentialsProvider = DeviceCredentials(device)   // read on every request, so a new key applies at once
            retryPolicy = ReenrollPolicy(device)
        }
    }
}

/** Pocket Cloud could not be reached or refused this app or device. */
class PocketException(message: String) : IOException(message)

private val DEVICE_ID = Regex("^[A-Za-z0-9-]{8,64}$")

internal data class SavedKey(val endpoint: String, val deviceId: String, val accessKey: String, val secretKey: String,
                             val region: String)

internal class Device(
    val app: String, home: File?, endpoint: String?, enrollSecret: String?, deviceId: String?, deviceName: String?,
) {
    private val home: File = home ?: System.getenv("POCKET_HOME")?.let(::File)
        ?: File(System.getProperty("user.home"), ".pocket")
    val endpoint: String
    private val enrollSecret: String
    val deviceId: String
    private val deviceName: String
    private val file = File(this.home, "apps/$app.json")
    @Volatile var creds: SavedKey

    init {
        var ep = endpoint ?: System.getenv("POCKET_ENDPOINT")
        var secret = enrollSecret ?: System.getenv("POCKET_ENROLL_SECRET")
        if (ep == null || secret == null) {
            val ini = readIni(File(this.home, "config"))
            ep = ep ?: ini["endpoint"]
            secret = secret ?: ini["enroll_secret"]
        }
        if (ep.isNullOrBlank() || secret.isNullOrBlank()) {
            throw PocketException("This device is not set up for Pocket Cloud. Save the machine setup file from the " +
                "control panel's Settings page as ${File(this.home, "config")}, or pass endpoint and enrollSecret.")
        }
        this.endpoint = ep.trimEnd('/')
        this.enrollSecret = secret
        this.deviceId = resolveDeviceId(deviceId)
        this.deviceName = deviceName ?: System.getenv("POCKET_DEVICE_NAME")
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Kotlin device"
        creds = load() ?: enroll()
    }

    private fun resolveDeviceId(given: String?): String {
        (given ?: System.getenv("POCKET_DEVICE_ID"))?.let {
            if (!DEVICE_ID.matches(it)) throw PocketException("A device id uses 8-64 letters, digits and dashes.")
            return it
        }
        val f = File(home, "device_id")
        if (f.isFile) f.readText().trim().takeIf { DEVICE_ID.matches(it) }?.let { return it }
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val id = "dev-" + bytes.joinToString("") { "%02x".format(it) }
        writePrivate(f, id + "\n")
        return id
    }

    private fun load(): SavedKey? {
        if (!file.isFile) return null
        val m = runCatching { Json.parse(file.readText()) as Map<*, *> }.getOrNull() ?: return null
        val key = SavedKey(m["endpoint"] as? String ?: return null, m["device_id"] as? String ?: return null,
            m["access_key"] as? String ?: return null, m["secret_key"] as? String ?: return null,
            m["region"] as? String ?: "us-east-1")
        return key.takeIf { it.endpoint == endpoint && it.deviceId == deviceId }
    }

    /** Enroll (again) with the server and save the key. Blocking; also used from the retry policy. */
    @Synchronized
    fun enroll(): SavedKey {
        val body = Json.write(mapOf("secret" to enrollSecret, "app" to app, "device_id" to deviceId,
            "device_name" to deviceName))
        val conn = try {
            (URI.create("$endpoint/_pocket/enroll").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        } catch (e: IOException) {
            throw PocketException("Pocket Cloud can't be reached at $endpoint: ${e.message}")
        }
        val status = try { conn.responseCode } catch (e: IOException) {
            throw PocketException("Pocket Cloud can't be reached at $endpoint: ${e.message}")
        }
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        val m = runCatching { Json.parse(text) as Map<*, *> }.getOrNull() ?: emptyMap<String, Any?>()
        if (status !in 200..299) {
            throw PocketException("Pocket Cloud refused to enroll $app: ${m["message"] ?: "HTTP $status"}")
        }
        val key = SavedKey(endpoint, deviceId, m["access_key"] as String, m["secret_key"] as String,
            m["region"] as? String ?: "us-east-1")
        writePrivate(file, Json.write(mapOf("endpoint" to key.endpoint, "device_id" to key.deviceId,
            "access_key" to key.accessKey, "secret_key" to key.secretKey, "region" to key.region)))
        creds = key
        return key
    }
}

private class DeviceCredentials(private val device: Device) : CredentialsProvider {
    override suspend fun resolve(attributes: Attributes): Credentials =
        device.creds.let { Credentials(it.accessKey, it.secretKey) }
}

/**
 * The SDK's standard retry rules, plus two for Pocket Cloud:
 *  - "try again" answers (500, 502, 503, 504), which the server gives while it restarts its storage server;
 *  - when the server says the device moved to a new address or its key was replaced: enroll again (at most
 *    once every 10 seconds) and retry.
 */
private class ReenrollPolicy(private val device: Device) : RetryPolicy<Any?> {
    private val normal = StandardRetryPolicy.Default
    @Volatile private var lastReenroll = 0L

    override fun evaluate(result: Result<Any?>): RetryDirective {
        val e = result.exceptionOrNull()
        if (e is S3Exception) {
            val http = e.sdkErrorMetadata.protocolResponse as? HttpResponse
            val status = http?.status?.value
            if (status in setOf(500, 502, 503, 504)) return RetryDirective.RetryError(RetryErrorType.ServerSide)
            val moved = http?.headers?.get("x-pocket-error") == "device-address-changed"
            val badKey = e.sdkErrorMetadata.errorCode == "InvalidAccessKeyId"
            if (status == 403 && (moved || badKey)) {
                // Requests failing together share one re-enrollment; the SDK's attempt limit bounds the retries.
                synchronized(this) {
                    if (System.currentTimeMillis() - lastReenroll > 10_000) {
                        if (runCatching { device.enroll() }.isFailure) return normal.evaluate(result)
                        lastReenroll = System.currentTimeMillis()
                    }
                }
                return RetryDirective.RetryError(RetryErrorType.ClientSide)
            }
        }
        return normal.evaluate(result)
    }
}

private fun readIni(f: File): Map<String, String> {
    if (!f.isFile) return emptyMap()
    val out = mutableMapOf<String, String>()
    var section: String? = null
    for (raw in f.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length - 1).trim(); continue }
        val eq = line.indexOfFirst { it == '=' || it == ':' }
        if (section == "pocket" && eq > 0) out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
    }
    return out
}

/** Write a file only this user can read, replacing any old one in one step. */
private fun writePrivate(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, ".tmp-" + System.nanoTime())
    tmp.writeText(text)
    tmp.setReadable(false, false); tmp.setReadable(true, true)
    tmp.setWritable(false, false); tmp.setWritable(true, true)
    if (!tmp.renameTo(f)) {            // Windows won't rename over an existing file
        f.delete()
        if (!tmp.renameTo(f)) throw IOException("could not save $f")
    }
}

/** Just enough JSON for the enrollment exchange and the saved key file. */
internal object Json {
    fun write(m: Map<String, Any?>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Number, is Boolean -> v.toString()
        else -> str(v.toString())
    }

    private fun str(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "trailing data" }; v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> number()
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad literal" }; i += w.length; return v }
        fun number(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value()); ws()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("bad array") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = linkedMapOf<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val k = string(); ws()
                require(s[i++] == ':') { "expected :" }
                out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("bad object") }
            }
        }
    }
}
