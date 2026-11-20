package cloud.pocket.lite

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The in-memory Pocket Cloud of docs/app-api.md section 11: a real HTTP server on 127.0.0.1 with a random port,
 * the real signature check, plain file routes, list, move, change feed (long poll), publish folder, notify (with
 * the real rate limit), quota, share, and the `/_fake/` test controls.
 */
class FakePocketServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    private val lock = Object()

    private data class Dev(val app: String, val uid: String, val name: String, val accessKey: String, val secret: String)
    private data class Obj(val bytes: ByteArray, val contentType: String?, val etag: String, val modified: Double)
    private data class ObjKey(val app: String, val bucket: String, val space: String, val key: String)
    private data class Rec(val seq: Long, val app: String, val bucket: String, val space: String, val json: JsonObject)
    private class AppConf(var publish: Boolean = false, var notify: Boolean = false, var readOnly: Boolean = false,
                          var allocation: Long = 1L shl 30, var retention: JsonElement = JsonArray(emptyList()))
    private data class Fail(val method: String, val path: String, val status: Int, val code: String, var times: Int)
    private data class Sent(val app: String, val device: String, val json: JsonObject, val time: Double)

    private val allFeatures = listOf("quota", "conflicts", "mirror-report", "share", "push", "files", "move",
        "changes", "publish", "retention", "notify")

    private var features = allFeatures.toMutableList()
    private var fixedNow: Double? = null
    private val devices = mutableMapOf<String, Dev>()          // access key -> device
    private val objects = sortedMapOf<ObjKey, Obj>(compareBy<ObjKey>({ it.app }, { it.bucket }, { it.space }).thenComparator { a, b -> byteCompare(a.key, b.key) })
    private val changes = mutableListOf<Rec>()
    private var seq = 0L
    private var expiredUpTo = 0L
    private val apps = mutableMapOf<String, AppConf>()
    private val fails = mutableListOf<Fail>()
    private var movedTimes = 0
    private val sent = mutableListOf<Sent>()
    private val requests = mutableListOf<JsonObject>()
    private val shares = mutableListOf<JsonObject>()

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "fake-pocket").apply { isDaemon = true } }
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    override fun close() = server.stop(0)

    fun now(): Double = fixedNow ?: (System.currentTimeMillis() / 1000.0)

    private fun conf(app: String) = apps.getOrPut(app) { AppConf() }

    private class Refuse(val status: Int, val code: String, message: String,
                         val headers: Map<String, String> = emptyMap(), val extra: JsonObject? = null) : Exception(message)

    private class Answer(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap(),
                         val length: Long? = null)

    private fun json(status: Int, obj: JsonElement, headers: Map<String, String> = emptyMap()) =
        Answer(status, obj.toString().toByteArray(), headers + ("Content-Type" to "application/json"))

    // ------------------------------------------------------------------ dispatch

    private fun handle(ex: HttpExchange) {
        val method = ex.requestMethod.uppercase()
        val path = ex.requestURI.rawPath
        val query = ex.requestURI.rawQuery ?: ""
        var answer: Answer
        var code: String? = null
        try {
            answer = route(ex, method, path, query)
        } catch (r: Refuse) {
            code = r.code
            val body = buildJsonObject {
                put("error", r.code); put("message", r.message ?: r.code)
                r.extra?.forEach { (k, v) -> put(k, v) }
            }
            answer = if (method == "HEAD") Answer(r.status, headers = r.headers + ("x-pocket-error-code" to r.code))
                     else json(r.status, body, r.headers)
        } catch (e: Exception) {
            code = "InternalError"
            answer = json(500, buildJsonObject { put("error", "InternalError"); put("message", e.toString()) })
        }
        if (!path.startsWith("/_fake/")) synchronized(lock) {
            requests += buildJsonObject {
                put("method", method); put("path", path); put("query", query); put("status", answer.status); put("code", code)
            }
        }
        answer.headers.forEach { (k, v) -> ex.responseHeaders.set(k, v) }
        if (method == "HEAD" || answer.status == 304) {
            answer.length?.let { ex.responseHeaders.set("Content-Length", it.toString()) }
            ex.sendResponseHeaders(answer.status, -1)
        } else {
            ex.sendResponseHeaders(answer.status, if (answer.body.isEmpty()) -1 else answer.body.size.toLong())
            if (answer.body.isNotEmpty()) ex.responseBody.use { it.write(answer.body) }
        }
        ex.close()
        bodies.remove(ex)
    }

    private fun route(ex: HttpExchange, method: String, path: String, query: String): Answer {
        if (path.startsWith("/_fake/")) return fake(ex, method, path, query)
        synchronized(lock) {
            fails.firstOrNull { it.times > 0 && it.method == method && path.startsWith(it.path) }?.let {
                it.times--
                val h = if (it.status == 503 || it.status == 429) mapOf("Retry-After" to "0") else emptyMap()
                throw Refuse(it.status, it.code, "Fake failure.", h)
            }
        }
        if (path == "/_pocket/health" && method == "GET") {
            return json(200, buildJsonObject {
                put("ok", true); put("version", "0.3.0-fake")
                putJsonArray("features") { synchronized(lock) { features.forEach { add(JsonPrimitive(it)) } } }
            })
        }
        if (path == "/_pocket/enroll" && method == "POST") return enroll(readBody(ex))
        if (path.startsWith("/_pocket/pub/")) return pub(method, path)

        val feature = when {
            path == "/_pocket/app" || path.startsWith("/_pocket/files/") || path == "/_pocket/list" -> "files"
            path == "/_pocket/move" -> "move"
            path == "/_pocket/changes" -> "changes"
            path == "/_pocket/notify" -> "notify"
            path == "/_pocket/quota" -> "quota"
            path == "/_pocket/share" || path == "/_pocket/shares" -> "share"
            else -> null
        }
        if (feature == null || feature !in synchronized(lock) { features }) throw Refuse(404, "NotFound", "No such route.")

        // signature: for uploads checked from x-pocket-content-sha256 before the body is read
        val headerSha = ex.requestHeaders.getFirst("x-pocket-content-sha256")
        if (method == "PUT" && path.startsWith("/_pocket/files/") && headerSha == null) {
            throw Refuse(400, "BadRequest", "x-pocket-content-sha256 is required for uploads.")
        }
        val dev = authenticate(ex, method, path, query, headerSha)
        val body = readBody(ex)
        val sha = Signer.sha256(body)
        if (headerSha != null && headerSha != sha) {
            if (method == "PUT") throw Refuse(400, "BadDigest", "The body did not match x-pocket-content-sha256.")
            throw Refuse(403, "SignatureDoesNotMatch", "Body hash mismatch.")
        }
        val q = parseQuery(query)
        return synchronized(lock) {
            when {
                path == "/_pocket/app" && method == "GET" -> appInfo(dev)
                path.startsWith("/_pocket/files/") -> files(ex, method, path, dev, body)
                path == "/_pocket/list" && method == "GET" -> list(dev, q)
                path == "/_pocket/move" && method == "POST" -> move(dev, parseJson(body))
                path == "/_pocket/changes" && method == "GET" -> null
                path == "/_pocket/notify" && method == "POST" -> notify(dev, parseJson(body))
                path == "/_pocket/quota" && method == "GET" -> quota(dev)
                path == "/_pocket/share" && method == "POST" -> share(dev, parseJson(body))
                path == "/_pocket/shares" && method == "GET" -> json(200, buildJsonObject {
                    put("shares", JsonArray(shares.filter { it["_dev"]!!.str() == dev.uid }.map { strip(it) }))
                })
                path == "/_pocket/share" && method == "DELETE" -> {
                    val token = q["token"] ?: ""
                    if (!shares.removeIf { it["token"]!!.str() == token && it["_dev"]!!.str() == dev.uid }) {
                        throw Refuse(404, "NoSuchShare", "No such share link of yours.")
                    }
                    json(200, buildJsonObject { put("ok", true) })
                }
                else -> throw Refuse(405, "MethodNotAllowed", "Method not allowed.")
            }
        } ?: changesRoute(dev, q)
    }

    private fun authenticate(ex: HttpExchange, method: String, path: String, query: String, headerSha: String?): Dev {
        val h = ex.requestHeaders
        val key = h.getFirst("x-pocket-key") ?: throw Refuse(403, "MissingSignature", "Not signed.")
        val dev = synchronized(lock) { devices[key] } ?: throw Refuse(403, "InvalidAccessKeyId", "Unknown key.")
        val date = h.getFirst("x-pocket-date")?.toLongOrNull() ?: throw Refuse(403, "MissingSignature", "No date.")
        if (abs(now() - date) > 900) throw Refuse(403, "RequestTimeTooSkewed", "Clock is more than 15 minutes off.")
        // the real check is against the body hash; for a non-upload without the header, hash the body first
        val bodySha = headerSha ?: Signer.sha256(peekBody(ex))
        val text = listOf(method, path, query, date.toString(), bodySha).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(dev.secret.toByteArray(), "HmacSHA256")) }
        val want = Signer.hex(mac.doFinal(text.toByteArray()))
        if (!MessageDigest.isEqual(want.toByteArray(), (h.getFirst("x-pocket-sign") ?: "").toByteArray())) {
            throw Refuse(403, "SignatureDoesNotMatch", "Signature does not match.")
        }
        synchronized(lock) {
            if (movedTimes > 0) {
                movedTimes--
                throw Refuse(403, "AccessDenied", "This device moved.", mapOf("x-pocket-error" to "device-address-changed"))
            }
        }
        return dev
    }

    // the body is read once and kept on the exchange, so the signature check can hash it first
    // (HttpExchange attributes are shared by the whole context, so not those)
    private val bodies = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<HttpExchange, ByteArray>())

    private fun peekBody(ex: HttpExchange): ByteArray = bodies.getOrPut(ex) { ex.requestBody.readBytes() }

    private fun readBody(ex: HttpExchange): ByteArray = peekBody(ex)

    // ------------------------------------------------------------------ enroll / app

    private fun enroll(body: ByteArray): Answer {
        val m = parseJson(body)
        if (m.s("secret") != "fake-enroll-secret") throw Refuse(403, "Forbidden", "The enrollment secret is not right.")
        val app = m.s("app") ?: ""
        val uid = m.s("device_id") ?: ""
        if (!Regex("^[a-z0-9][a-z0-9-]{0,62}$").matches(app)) throw Refuse(400, "BadRequest", "Bad app name.")
        val dev = synchronized(lock) {
            devices.values.firstOrNull { it.app == app && it.uid == uid } ?: run {
                val rnd = SecureRandom()
                val ak = "AKFAKE" + (1..10).joinToString("") { rnd.nextInt(10).toString() }
                val sk = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(30).also { rnd.nextBytes(it) })
                Dev(app, uid, m.s("device_name") ?: "", ak, sk).also { devices[ak] = it; conf(app) }
            }
        }
        return json(200, buildJsonObject {
            put("app", app); put("device_id", uid); put("access_key", dev.accessKey); put("secret_key", dev.secret)
            put("endpoint", url)
            putJsonObject("endpoints") { put("tailscale", url); put("lan", JsonNull) }
            put("region", "us-east-1")
            putJsonArray("buckets") { add(JsonPrimitive("shared")); add(JsonPrimitive("device")) }
            put("allocation_bytes", conf(app).allocation)
            put("push", JsonNull)
        })
    }

    private fun appInfo(dev: Dev): Answer {
        val c = conf(dev.app)
        return json(200, buildJsonObject {
            put("app", dev.app); put("device_id", dev.uid)
            putJsonArray("buckets") { add(JsonPrimitive("shared")); add(JsonPrimitive("device")) }
            putJsonArray("features") { features.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("publish") { put("enabled", c.publish && "publish" in features); put("url", "$url/_pocket/pub/${dev.app}/") }
            putJsonObject("notify") { put("enabled", c.notify && "notify" in features); put("topic", "pocket-${dev.app}-notify"); put("url", JsonNull) }
            put("push", JsonNull)
            put("retention", c.retention)
            putJsonObject("changes") { put("keep_days", 30) }
            put("read_only", c.readOnly)
        })
    }

    // ------------------------------------------------------------------ files

    private fun space(dev: Dev, bucket: String) = when (bucket) {
        "shared" -> ""
        "device" -> dev.uid
        else -> throw Refuse(404, "NoSuchBucket", "No bucket $bucket.")
    }

    private fun decodeKey(raw: String): String {
        val key = raw.split('/').joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
        try { Encode.checkKey(key) } catch (e: PocketException) { throw Refuse(400, "BadKey", "Bad key.") }
        return key
    }

    private fun fileHeaders(o: Obj) = mapOf(
        "Content-Type" to (o.contentType ?: "application/octet-stream"),
        "ETag" to o.etag,
        "Last-Modified" to DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli((o.modified * 1000).toLong()).atZone(ZoneOffset.UTC)),
        "x-pocket-modified" to o.modified.toString(),
        "Accept-Ranges" to "bytes",
    )

    private fun files(ex: HttpExchange, method: String, path: String, dev: Dev, body: ByteArray): Answer {
        val rest = path.removePrefix("/_pocket/files/")
        val slash = rest.indexOf('/')
        if (slash <= 0) throw Refuse(400, "BadKey", "No key.")
        val bucket = URLDecoder.decode(rest.substring(0, slash), Charsets.UTF_8)
        val sp = space(dev, bucket)
        val key = decodeKey(rest.substring(slash + 1))
        val ok = ObjKey(dev.app, bucket, sp, key)
        val cur = objects[ok]
        val h = ex.requestHeaders
        val ifMatch = h.getFirst("if-match")
        val ifNone = h.getFirst("if-none-match")
        when (method) {
            "GET", "HEAD" -> {
                cur ?: throw Refuse(404, "NoSuchKey", "No such file.")
                if (ifNone != null && ifNone == cur.etag) return Answer(304, headers = fileHeaders(cur))
                if (ifMatch != null && ifMatch != cur.etag) throw Refuse(412, "PreconditionFailed", "ETag differs.")
                val range = h.getFirst("Range")
                if (range != null) {
                    val m = Regex("^bytes=(\\d*)-(\\d*)$").find(range) ?: throw Refuse(416, "InvalidRange", "Bad range.")
                    val n = cur.bytes.size.toLong()
                    val (a, b) = m.groupValues[1] to m.groupValues[2]
                    val start = if (a.isEmpty()) n - b.toLong() else a.toLong()
                    val end = if (a.isEmpty() || b.isEmpty()) n - 1 else minOf(b.toLong(), n - 1)
                    if (start < 0 || start >= n || end < start) throw Refuse(416, "InvalidRange", "Outside the file.")
                    val part = cur.bytes.copyOfRange(start.toInt(), end.toInt() + 1)
                    return Answer(206, if (method == "HEAD") ByteArray(0) else part,
                        fileHeaders(cur) + ("Content-Range" to "bytes $start-$end/$n"), part.size.toLong())
                }
                return Answer(200, if (method == "HEAD") ByteArray(0) else cur.bytes, fileHeaders(cur), cur.bytes.size.toLong())
            }
            "PUT" -> {
                val len = h.getFirst("Content-Length")?.toLongOrNull() ?: throw Refuse(400, "BadRequest", "Content-Length is required.")
                if (len > 2147483648L) throw Refuse(400, "EntityTooLarge", "Over 2 GiB.")
                val c = conf(dev.app)
                if (c.readOnly) throw Refuse(403, "ReadOnly", "The server is read-only.")
                if (ifMatch != null && (cur == null || cur.etag != ifMatch)) throw Refuse(412, "PreconditionFailed", "ETag differs.")
                if (ifNone == "*" && cur != null) throw Refuse(412, "PreconditionFailed", "File exists.")
                if (used(dev.app) - (cur?.bytes?.size ?: 0) + body.size > c.allocation) {
                    throw Refuse(403, "QuotaExceeded", "${dev.app} would go over its allocation.")
                }
                val modified = h.getFirst("x-pocket-modified")?.toDoubleOrNull() ?: now()
                val o = Obj(body, h.getFirst("Content-Type"), md5(body), modified)
                objects[ok] = o
                record(dev, bucket, sp, key, "put", o)
                return json(200, buildJsonObject {
                    put("bucket", bucket); put("key", key); put("size", body.size); put("etag", o.etag)
                    put("modified", o.modified); put("created", cur == null)
                })
            }
            "DELETE" -> {
                if (conf(dev.app).readOnly) throw Refuse(403, "ReadOnly", "The server is read-only.")
                cur ?: throw Refuse(404, "NoSuchKey", "No such file.")
                if (ifMatch != null && ifMatch != cur.etag) throw Refuse(412, "PreconditionFailed", "ETag differs.")
                objects.remove(ok)
                record(dev, bucket, sp, key, "delete", null)
                return json(200, buildJsonObject { put("bucket", bucket); put("key", key); put("deleted", true) })
            }
        }
        throw Refuse(405, "MethodNotAllowed", "Method not allowed.")
    }

    private fun used(app: String) = objects.entries.filter { it.key.app == app }.sumOf { it.value.bytes.size.toLong() }

    private fun list(dev: Dev, q: Map<String, String>): Answer {
        val bucket = q["bucket"] ?: throw Refuse(400, "BadRequest", "bucket is required.")
        val sp = space(dev, bucket)
        val prefix = q["prefix"] ?: ""
        val delim = q["delimiter"]
        if (delim != null && delim != "/") throw Refuse(400, "BadRequest", "Only / is a delimiter.")
        val limit = (q["limit"] ?: "1000").toIntOrNull()?.takeIf { it in 1..1000 } ?: throw Refuse(400, "BadRequest", "Bad limit.")
        val after = q["cursor"]?.let {
            val parts = String(Base64.getUrlDecoder().decode(it)).split('\u0000')
            if (parts.size != 4 || parts[0] != bucket || parts[1] != prefix || parts[2] != (delim ?: "")) {
                throw Refuse(400, "BadRequest", "Cursor for another listing.")
            }
            parts[3]
        }
        // entries: (sort name, file or folder)
        val items = sortedMapOf<String, Any>(Comparator { a, b -> byteCompare(a, b) })
        for ((k, o) in objects) {
            if (k.app != dev.app || k.bucket != bucket || k.space != sp || !k.key.startsWith(prefix)) continue
            if (delim != null) {
                val i = k.key.indexOf('/', prefix.length)
                if (i >= 0) { items[k.key.substring(0, i + 1)] = "folder"; continue }
            }
            items[k.key] = k.key to o
        }
        val page = items.entries.filter { after == null || byteCompare(it.key, after) > 0 }
        val shown = page.take(limit)
        val more = page.size > limit
        return json(200, buildJsonObject {
            put("bucket", bucket); put("prefix", prefix)
            putJsonArray("files") {
                for ((_, v) in shown) if (v is Pair<*, *>) {
                    val o = v.second as Obj
                    add(buildJsonObject { put("key", v.first as String); put("size", o.bytes.size); put("etag", o.etag); put("modified", o.modified) })
                }
            }
            if (delim != null) putJsonArray("folders") { for ((k, v) in shown) if (v == "folder") add(JsonPrimitive(k)) }
            put("cursor", if (more) Base64.getUrlEncoder().withoutPadding()
                .encodeToString("$bucket\u0000$prefix\u0000${delim ?: ""}\u0000${shown.last().key}".toByteArray()) else null)
        })
    }

    private fun move(dev: Dev, m: JsonObject): Answer {
        if (conf(dev.app).readOnly) throw Refuse(403, "ReadOnly", "The server is read-only.")
        val from = m["from"] as? JsonObject ?: throw Refuse(400, "BadRequest", "from is required.")
        val to = m["to"] as? JsonObject ?: throw Refuse(400, "BadRequest", "to is required.")
        val fb = from.s("bucket") ?: ""; val tb = to.s("bucket") ?: ""
        val fk = from.s("key") ?: ""; val tk = to.s("key") ?: ""
        for (k in listOf(fk, tk)) try { Encode.checkKey(k) } catch (e: PocketException) { throw Refuse(400, "BadKey", "Bad key.") }
        val src = ObjKey(dev.app, fb, space(dev, fb), fk)
        val dst = ObjKey(dev.app, tb, space(dev, tb), tk)
        val o = objects[src] ?: throw Refuse(404, "NoSuchKey", "No such file.")
        m.s("if_match")?.let { if (it != o.etag) throw Refuse(412, "PreconditionFailed", "ETag differs.") }
        if (objects.containsKey(dst) && (m["overwrite"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw Refuse(409, "KeyExists", "The target exists.")
        }
        objects.remove(src)
        objects[dst] = o
        record(dev, tb, dst.space, tk, "move", o, from = buildJsonObject { put("bucket", fb); put("key", fk) })
        return json(200, buildJsonObject {
            put("from", buildJsonObject { put("bucket", fb); put("key", fk) })
            put("to", buildJsonObject { put("bucket", tb); put("key", tk) })
            put("size", o.bytes.size); put("etag", o.etag)
        })
    }

    // ------------------------------------------------------------------ change feed

    private fun record(dev: Dev?, bucket: String, sp: String, key: String, kind: String, o: Obj?,
                       from: JsonObject? = null, source: String = "app") {
        seq += 1
        val j = buildJsonObject {
            put("seq", seq); put("bucket", bucket); put("key", key); put("kind", kind)
            if (from != null) put("from", from)
            put("size", o?.bytes?.size); put("etag", o?.etag); put("time", now())
            put("device", dev?.uid); put("source", source)
        }
        changes += Rec(seq, dev?.app ?: "", bucket, sp, j)
        lock.notifyAll()
    }

    private fun visible(r: Rec, dev: Dev) = r.app == dev.app && (r.bucket == "shared" || r.space == dev.uid)

    private fun changesRoute(dev: Dev, q: Map<String, String>): Answer {
        val limit = (q["limit"] ?: "500").toIntOrNull()?.takeIf { it in 1..1000 } ?: throw Refuse(400, "BadRequest", "Bad limit.")
        val wait = (q["wait"] ?: "0").toIntOrNull()?.takeIf { it in 0..60 } ?: throw Refuse(400, "BadRequest", "Bad wait.")
        synchronized(lock) {
            val since = q["since"] ?: return json(200, buildJsonObject {
                put("changes", JsonArray(emptyList())); put("cursor", seq.toString()); put("more", false)
            })
            val c = since.toLongOrNull()
            if (c == null || c < expiredUpTo || c > seq) {
                throw Refuse(410, "CursorExpired", "That cursor is too old.", extra = buildJsonObject { put("cursor", seq.toString()) })
            }
            val deadline = System.currentTimeMillis() + wait * 1000L
            while (true) {
                val after = changes.filter { it.seq > c && visible(it, dev) }
                val left = deadline - System.currentTimeMillis()
                if (after.isNotEmpty() || left <= 0) {
                    val shown = after.take(limit)
                    val more = after.size > limit
                    val cursor = if (more) shown.last().seq else seq
                    return json(200, buildJsonObject {
                        put("changes", JsonArray(shown.map { it.json })); put("cursor", cursor.toString()); put("more", more)
                    })
                }
                lock.wait(left)
            }
        }
    }

    // ------------------------------------------------------------------ publish folder

    private fun pub(method: String, path: String): Answer {
        if (method != "GET" && method != "HEAD") throw Refuse(405, "MethodNotAllowed", "Read-only.")
        val rest = path.removePrefix("/_pocket/pub/")
        val slash = rest.indexOf('/')
        val notFound = Refuse(404, "NotFound", "Not found.")
        if (slash <= 0 || slash == rest.length - 1) throw notFound
        val app = rest.substring(0, slash)
        synchronized(lock) {
            if ("publish" !in features || apps[app]?.publish != true) throw notFound
            val key = "releases/" + try { decodeKey(rest.substring(slash + 1)) } catch (_: Refuse) { throw notFound }
            val o = objects[ObjKey(app, "shared", "", key)] ?: throw notFound
            val name = key.substringAfterLast('/')
            val cache = if (key.count { it == '/' } == 1 && (name == "latest.json" || (name.startsWith("latest") && name.endsWith(".yml"))))
                "no-cache" else "max-age=86400"
            return Answer(200, if (method == "HEAD") ByteArray(0) else o.bytes, fileHeaders(o) + ("Cache-Control" to cache), o.bytes.size.toLong())
        }
    }

    // ------------------------------------------------------------------ notify, quota, share

    private fun notify(dev: Dev, m: JsonObject): Answer {
        if (!conf(dev.app).notify) throw Refuse(403, "FeatureOff", "Notifications are off for ${dev.app}.")
        val message = m.s("message") ?: ""
        if (message.isEmpty() || message.length > 4096) throw Refuse(400, "BadRequest", "message is 1-4096 characters.")
        if ((m.s("title")?.length ?: 0) > 200) throw Refuse(400, "BadRequest", "title is up to 200 characters.")
        val tags = (m["tags"] as? JsonArray)?.map { it.str() ?: "" }
        if (tags != null && (tags.size > 10 || tags.any { !Regex("^[a-z0-9_-]{1,40}$").matches(it) })) {
            throw Refuse(400, "BadRequest", "Bad tags.")
        }
        val priority = (m["priority"] as? JsonPrimitive)?.intOrNull ?: 3
        if (priority !in 1..5) throw Refuse(400, "BadRequest", "priority is 1-5.")
        if ((m.s("click")?.length ?: 0) > 2000) throw Refuse(400, "BadRequest", "click is up to 2000 characters.")
        val t = now()
        val mine = sent.filter { it.app == dev.app }
        val minute = mine.filter { it.time > t - 60 }
        val day = mine.filter { it.time > t - 86400 }
        if (minute.size >= 10 || day.size >= 200) {
            val oldest = if (minute.size >= 10) minute.minOf { it.time } + 60 else day.minOf { it.time } + 86400
            throw Refuse(429, "RateLimited", "Too many notifications.", mapOf("Retry-After" to maxOf(1, ceil(oldest - t).toInt()).toString()))
        }
        sent += Sent(dev.app, dev.uid, buildJsonObject {
            put("app", dev.app); put("device", dev.uid); put("message", message); put("title", m.s("title"))
            put("tags", tags?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) } ?: JsonNull)
            put("priority", priority); put("click", m.s("click")); put("time", t)
        }, t)
        return json(200, buildJsonObject { put("ok", true); put("id", "fake${sent.size}"); put("topic", "pocket-${dev.app}-notify") })
    }

    private fun quota(dev: Dev): Answer {
        val c = conf(dev.app)
        val usedApp = used(dev.app)
        val usedDev = objects.entries.filter { it.key.app == dev.app && it.key.space == dev.uid }.sumOf { it.value.bytes.size.toLong() }
        return json(200, buildJsonObject {
            put("app", dev.app); put("device_id", dev.uid); put("allocation", c.allocation); put("used_app", usedApp)
            put("used_device", usedDev); put("device_cap", JsonNull); put("reserve", 0); put("held_for_others", 0)
            put("free", maxOf(0, c.allocation - usedApp)); put("free_shared", maxOf(0, c.allocation - usedApp))
            put("read_only", c.readOnly)
        })
    }

    private fun share(dev: Dev, m: JsonObject): Answer {
        val bucket = m.s("bucket") ?: ""
        if (bucket !in listOf("shared", "device")) throw Refuse(400, "BadRequest", "bucket is 'shared' or 'device'.")
        val key = m.s("key") ?: ""
        objects[ObjKey(dev.app, bucket, space(dev, bucket), key)] ?: throw Refuse(404, "NoSuchKey", "There is no such file to share.")
        val expires = (m["expires_s"] as? JsonPrimitive)?.longOrNull ?: 86400
        if (expires !in 60..2592000) throw Refuse(400, "BadRequest", "expires_s is between 60 and 2592000.")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also { SecureRandom().nextBytes(it) })
        val t = now()
        val sh = buildJsonObject {
            put("token", token); put("url", "$url/s/$token"); put("public", false); put("bucket", bucket); put("key", key)
            put("created", t); put("expires", t + expires); put("downloads", 0)
            put("max_downloads", (m["max_downloads"] as? JsonPrimitive)?.intOrNull)
            put("has_password", m.s("password") != null); put("_dev", dev.uid)
        }
        shares += sh
        return json(200, strip(sh))
    }

    private fun strip(o: JsonObject) = JsonObject(o.filterKeys { !it.startsWith("_") })

    // ------------------------------------------------------------------ /_fake/ controls

    private fun fake(ex: HttpExchange, method: String, path: String, query: String): Answer {
        val body = if (method == "POST") parseJson(readBody(ex)) else JsonObject(emptyMap())
        val q = parseQuery(query)
        synchronized(lock) {
            when (path) {
                "/_fake/reset" -> {
                    features = allFeatures.toMutableList(); fixedNow = null; devices.clear(); objects.clear()
                    changes.clear(); seq = 0; expiredUpTo = 0; apps.clear(); fails.clear(); movedTimes = 0
                    sent.clear(); requests.clear(); shares.clear()
                }
                "/_fake/clock" -> fixedNow = (body["now"] as? JsonPrimitive)?.doubleOrNull
                "/_fake/features" -> features = (body["features"] as JsonArray).map { it.str()!! }.toMutableList()
                "/_fake/app" -> {
                    val c = conf(q["app"] ?: throw Refuse(400, "BadRequest", "?app= is required."))
                    (body["publish"] as? JsonPrimitive)?.booleanOrNull?.let { c.publish = it }
                    (body["notify"] as? JsonPrimitive)?.booleanOrNull?.let { c.notify = it }
                    (body["read_only"] as? JsonPrimitive)?.booleanOrNull?.let { c.readOnly = it }
                    (body["allocation"] as? JsonPrimitive)?.longOrNull?.let { c.allocation = it }
                    body["retention"]?.let { c.retention = it }
                }
                "/_fake/fail" -> {
                    val route = body.s("route")!!.split(' ', limit = 2)
                    fails += Fail(route[0], route[1], (body["status"] as JsonPrimitive).intOrNull!!, body.s("code")!!,
                        (body["times"] as? JsonPrimitive)?.intOrNull ?: 1)
                }
                "/_fake/moved" -> movedTimes = (body["times"] as? JsonPrimitive)?.intOrNull ?: 1
                "/_fake/expire-changes" -> {
                    val before = body.s("before")!!.toLong()
                    changes.removeIf { it.seq <= before }
                    expiredUpTo = before
                }
                "/_fake/notifications" -> return json(200, buildJsonObject { put("sent", JsonArray(sent.map { it.json })) })
                "/_fake/requests" -> return json(200, buildJsonObject { put("requests", JsonArray(requests.toList())) })
                else -> throw Refuse(404, "NotFound", "No such fake control.")
            }
            return json(200, buildJsonObject { put("ok", true) })
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun parseJson(b: ByteArray): JsonObject = try {
        if (b.isEmpty()) JsonObject(emptyMap()) else JSON.parseToJsonElement(b.decodeToString()).jsonObject
    } catch (e: Exception) {
        throw Refuse(400, "BadRequest", "Malformed JSON.")
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        val i = it.indexOf('=')
        val k = if (i < 0) it else it.substring(0, i)
        val v = if (i < 0) "" else it.substring(i + 1)
        URLDecoder.decode(k.replace("+", "%2B"), Charsets.UTF_8) to URLDecoder.decode(v.replace("+", "%2B"), Charsets.UTF_8)
    }

    private fun JsonObject.s(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    companion object {
        fun md5(b: ByteArray) = "\"" + Signer.hex(MessageDigest.getInstance("MD5").digest(b)) + "\""

        fun byteCompare(a: String, b: String): Int {
            val x = a.toByteArray(); val y = b.toByteArray()
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return x.size - y.size
        }
    }
}
