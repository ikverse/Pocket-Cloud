package cloud.pocket.drive.data

import cloud.pocket.PocketException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pocket Cloud's own (non-S3) requests for this device: JSON, signed with the device key. */
class ServerApi(private val remote: Remote, private val settings: Settings) {

    class Failure(val status: Int, val code: String, message: String) : Exception(message)

    private data class Key(val access: String, val secret: String)

    private fun key(): Key? {
        val f = File(remote.home, "apps/${Remote.APP}.json")
        if (!f.isFile) return null
        val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val a = j.optString("access_key")
        val s = j.optString("secret_key")
        return if (a.isBlank() || s.isBlank()) null else Key(a, s)
    }

    /**
     * Call a /_pocket/ endpoint. [query] is the raw query string without the "?".
     * Enrolls again once if the server doesn't know this device's key any more.
     */
    suspend fun call(method: String, path: String, query: String = "", body: JSONObject? = null): JSONObject {
        try {
            return once(method, path, query, body)
        } catch (e: Failure) {
            if (e.status == 403 && (e.code == "InvalidAccessKeyId" || e.code == "SignatureDoesNotMatch")) {
                remote.reset()
                File(remote.home, "apps/${Remote.APP}.json").delete()
                remote.connect()
                return once(method, path, query, body)
            }
            throw e
        }
    }

    private suspend fun once(method: String, path: String, query: String, body: JSONObject?): JSONObject {
        remote.s3()                                  // makes sure the device is enrolled and its key saved
        val key = key() ?: throw PocketException("This device has no key yet.")
        val payload = body?.toString()?.toByteArray() ?: ByteArray(0)
        val date = System.currentTimeMillis() / 1000
        val signature = sign(key.secret, method, path, query, date, payload)
        val base = settings.endpointInUse.ifBlank { settings.endpoint() }.trimEnd('/')
        return withContext(Dispatchers.IO) {
            val conn = (URI.create(base + path + if (query.isEmpty()) "" else "?$query").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 60_000
                setRequestProperty("x-pocket-key", key.access)
                setRequestProperty("x-pocket-date", date.toString())
                setRequestProperty("x-pocket-sign", signature)
                setRequestProperty("Accept", "application/json")
                if (payload.isNotEmpty()) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(payload.size)
                }
            }
            try {
                if (payload.isNotEmpty()) conn.outputStream.use { it.write(payload) }
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
                val json = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                if (status !in 200..299) throw Failure(status, json.optString("error", "HTTP$status"), json.optString("message", "The server answered $status."))
                json
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---------------------------------------------------------------- what the server offers

    /** Room in this device's drive; null when the server can't say. */
    suspend fun quota(): Quota? {
        if (!settings.has("quota")) return null
        return try {
            val j = call("GET", "/_pocket/quota")
            Quota(j.optLong("allocation"), j.optLong("used_app"), j.optLong("used_device"), j.optLong("free"), j.optLong("free_shared"))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- versions and trash

    /**
     * Old versions of one file (overwritten or deleted), newest first.
     * Asks GET /_pocket/versions?bucket=&key=; the answer holds a list called "versions" (or "items") whose entries have
     * a version id, a size, a time and the reason.
     */
    suspend fun versions(bucket: String, key: String): List<OldVersion> =
        OldVersion.list(call("GET", "/_pocket/versions", query = "bucket=" + enc(bucket) + "&key=" + enc(key)), bucket, key)

    /** Deleted files below [prefix], newest first (GET /_pocket/trash?bucket=&prefix=). */
    suspend fun trash(bucket: String, prefix: String): List<OldVersion> =
        OldVersion.list(call("GET", "/_pocket/trash", query = "bucket=" + enc(bucket) + "&prefix=" + enc(prefix)), bucket, null)

    /** Bring a version back (POST /_pocket/restore). The current file, if any, becomes an old version itself. */
    suspend fun restore(v: OldVersion) {
        call("POST", "/_pocket/restore", body = JSONObject().put("bucket", v.bucket).put("key", v.key).put("version", v.version))
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- share links

    /** Make a temporary link to one of this device's files. */
    suspend fun createShare(bucket: String, key: String, expiresS: Long, password: String?, maxDownloads: Int?): Share {
        val body = JSONObject().put("bucket", bucket).put("key", key).put("expires_s", expiresS)
        if (!password.isNullOrEmpty()) body.put("password", password)
        if (maxDownloads != null) body.put("max_downloads", maxDownloads)
        return Share.from(call("POST", "/_pocket/share", body = body))
    }

    suspend fun shares(): List<Share> {
        val arr = call("GET", "/_pocket/shares").optJSONArray("shares") ?: return emptyList()
        return (0 until arr.length()).map { Share.from(arr.getJSONObject(it)) }
    }

    suspend fun removeShare(token: String) {
        call("DELETE", "/_pocket/share", query = "token=" + java.net.URLEncoder.encode(token, "UTF-8"))
    }

    companion object {
        /** The signature the server expects (see the server's sigv4.sign_pocket). */
        fun sign(secret: String, method: String, path: String, query: String, date: Long, body: ByteArray): String {
            val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val message = listOf(method.uppercase(), path, query, date.toString(), sha).joinToString("\n")
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Space left, in bytes. [free] is what this device can still write privately; [freeShared] what the shared space can still take. */
data class Quota(val allocation: Long, val usedApp: Long, val usedDevice: Long, val free: Long, val freeShared: Long)

/** A link for opening one file. [public] is false when only people on your Tailscale network can use it. */
data class Share(val token: String, val url: String, val public: Boolean, val bucket: String, val key: String, val expires: Double,
                 val downloads: Int, val maxDownloads: Int?, val hasPassword: Boolean) {
    companion object {
        fun from(j: JSONObject) = Share(j.optString("token"), j.optString("url"), j.optBoolean("public"), j.optString("bucket"), j.optString("key"),
            j.optDouble("expires"), j.optInt("downloads"), if (j.isNull("max_downloads")) null else j.optInt("max_downloads"), j.optBoolean("has_password"))
    }
}

/** One old copy of a file, kept by the server for a while. [time] is when it was replaced or deleted (seconds). */
data class OldVersion(val bucket: String, val key: String, val version: String, val size: Long, val time: Double, val reason: String, val by: String, val daysLeft: Int?) {
    companion object {
        fun list(j: JSONObject, bucket: String, key: String?): List<OldVersion> {
            val arr = j.optJSONArray("versions") ?: j.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("key").ifBlank { key ?: return@mapNotNull null }
                val v = o.optString("version").ifBlank { o.optString("id") }
                if (v.isBlank()) return@mapNotNull null
                OldVersion(o.optString("bucket").ifBlank { bucket }, k, v, o.optLong("size"),
                    o.optDouble("time", o.optDouble("deleted_at", o.optDouble("modified", 0.0))), o.optString("reason"), o.optString("by", o.optString("device_name")),
                    if (o.has("days_left") && !o.isNull("days_left")) o.optInt("days_left") else null)
            }
        }
    }
}
