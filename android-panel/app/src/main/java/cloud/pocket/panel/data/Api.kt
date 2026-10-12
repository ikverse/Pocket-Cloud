package cloud.pocket.panel.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** A JSON object from the control API, with short typed getters. */
class J(val o: JSONObject) {
    fun s(k: String, d: String = ""): String = if (o.isNull(k)) d else o.optString(k, d)
    fun l(k: String): Long = o.optLong(k, 0)
    fun d(k: String): Double = o.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
    fun i(k: String): Int = o.optInt(k, 0)
    fun b(k: String): Boolean = o.optBoolean(k, false)
    fun has(k: String) = o.has(k) && !o.isNull(k)
    fun obj(k: String): J = J(o.optJSONObject(k) ?: JSONObject())
    fun arr(k: String): List<J> = o.optJSONArray(k).objects()
    fun nums(k: String): List<Double> = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optDouble(it, 0.0) } } ?: emptyList()
    fun strs(k: String): List<String> = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
    fun keys(): List<String> = o.keys().asSequence().toList()
}

fun JSONArray?.objects(): List<J> = if (this == null) emptyList() else List(length()) { J(optJSONObject(it) ?: JSONObject()) }

fun body(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
}

/** The control API answered with an error (status 0: the server could not be reached). */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/** Talks to the Pocket Cloud control API (the same one the web panel uses). */
class PocketApi(val base: String, @Volatile var token: String?) {

    suspend fun get(path: String): J = call("GET", path, null)
    suspend fun post(path: String, body: JSONObject = JSONObject()): J = call("POST", path, body)
    suspend fun patch(path: String, body: JSONObject): J = call("PATCH", path, body)
    suspend fun delete(path: String): J = call("DELETE", path, null)

    suspend fun call(method: String, path: String, body: JSONObject?): J = withContext(Dispatchers.IO) {
        val conn = open(path, method)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray()
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            read(conn)
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(0, "Unreachable", "Pocket CloudServer can't be reached. Is the phone on and connected to Tailscale?")
        } finally {
            conn.disconnect()
        }
    }

    /** Upload a file into an app's space. */
    suspend fun upload(app: String, space: String, key: String, input: InputStream, length: Long): J = withContext(Dispatchers.IO) {
        val conn = open("/api/files/upload?" + query("app" to app, "space" to space, "key" to key), "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(length)
            conn.outputStream.use { out -> input.use { it.copyTo(out, 256 * 1024) } }
            read(conn)
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(0, "Unreachable", "The upload was cut off: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Download a file into out. Returns the number of bytes. */
    suspend fun download(app: String, space: String, key: String, out: OutputStream): Long = withContext(Dispatchers.IO) {
        val conn = open("/api/files/download?" + query("app" to app, "space" to space, "key" to key), "GET")
        try {
            if (conn.responseCode !in 200..299) read(conn)
            conn.inputStream.use { it.copyTo(out, 256 * 1024) }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(0, "Unreachable", "The download was cut off: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URI.create(base.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun read(conn: HttpURLConnection): J {
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        val json = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        if (status !in 200..299) {
            throw ApiException(status, json.optString("error", "HTTP$status"), json.optString("message", "The server answered $status."))
        }
        return J(json)
    }

    companion object {
        fun query(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }
}
