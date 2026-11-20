package cloud.pocket.lite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import okhttp3.Response
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One app on this device, from [Pocket.connect]. Every method is `suspend` and safe to call from any thread.
 * `bucket` is `"shared"` (all the app's devices) unless given; `"device"` is this device's own space.
 */
class PocketApp internal constructor(
    @PublishedApi internal val session: Session,
    @Volatile private var knownFeatures: Set<String>?,
) {
    val app: String get() = session.app
    val deviceId: String get() = session.deviceId
    val deviceName: String get() = session.deviceName

    /** List page size; tests shrink it. */
    internal var pageLimit = 1000

    /** Long-poll seconds for [changes]; tests shrink it. */
    internal var pollWait = LONG_POLL_S

    /** The server's features (`GET /_pocket/health`), asked again if the server was not reachable at connect. */
    suspend fun features(): Set<String> = knownFeatures ?: session.health().also { knownFeatures = it }

    @PublishedApi internal suspend fun need(feature: String) {
        if (feature !in features()) {
            throw FeatureOffException("FeatureMissing", 0, "This Pocket Cloud server does not have \"$feature\" yet.")
        }
    }

    private fun filePath(bucket: String, key: String) = "/_pocket/files/${Encode.component(bucket)}/${Encode.key(key)}"

    // ---------------------------------------------------------------- app info

    /** `GET /_pocket/app`: buckets, features and switches for this app and device. */
    suspend fun appInfo(): AppInfo {
        need("files")
        return session.call("GET", "/_pocket/app", retry = true).use { JSON.decodeFromString(AppInfo.serializer(), it.body.string()) }
    }

    // ---------------------------------------------------------------- files

    /** The file's bytes. Missing: [NotFoundException]. */
    suspend fun get(key: String, bucket: String = "shared"): ByteArray {
        need("files")
        return session.call("GET", filePath(bucket, key), retry = true).use { it.body.bytes() }
    }

    /** Stream the file to [dest] (through a temporary file next to it), with the file's time. */
    suspend fun getFile(key: String, dest: File, bucket: String = "shared"): Entry {
        need("files")
        return session.call("GET", filePath(bucket, key), retry = true).use { resp ->
            val entry = entryFromHeaders(bucket, key, resp)
            withContext(Dispatchers.IO) {
                val dir = dest.absoluteFile.parentFile.also { it.mkdirs() }
                val tmp = File(dir, ".pocket-tmp-" + System.nanoTime())
                try {
                    resp.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    if (entry.modified > 0) tmp.setLastModified((entry.modified * 1000).toLong())
                    replaceFile(tmp, dest)
                } finally {
                    tmp.delete()
                }
            }
            entry.copy(size = dest.length())
        }
    }

    /** Write [data]; with [ifMatch] only if the file still has that ETag. */
    suspend fun put(key: String, data: ByteArray, bucket: String = "shared", ifMatch: String? = null,
                    contentType: String? = null): Entry = putBody(key, Body.Bytes(data, contentType), bucket, ifMatch)

    /** Stream [src] (read twice: once to hash, once to send) and keep its modification time. */
    suspend fun putFile(key: String, src: File, bucket: String = "shared", ifMatch: String? = null,
                        contentType: String? = null): Entry {
        val body = withContext(Dispatchers.IO) { Body.FromFile(src, contentType) }
        return putBody(key, body, bucket, ifMatch, modified = src.lastModified() / 1000.0)
    }

    internal suspend fun putBody(key: String, body: Body, bucket: String, ifMatch: String?,
                                 ifNoneMatch: Boolean = false, modified: Double? = null): Entry {
        need("files")
        val h = mutableMapOf<String, String>()
        ifMatch?.let { h["if-match"] = it }
        if (ifNoneMatch) h["if-none-match"] = "*"
        modified?.let { h["x-pocket-modified"] = "%.3f".format(java.util.Locale.ROOT, it) }
        val conditional = ifMatch != null || ifNoneMatch
        return session.call("PUT", filePath(bucket, key), body = body, headers = h, retry = conditional).use {
            JSON.decodeFromString(Entry.serializer(), it.body.string())
        }
    }

    /** The file's details, or null when there is none. */
    suspend fun stat(key: String, bucket: String = "shared"): Entry? {
        need("files")
        return try {
            session.call("HEAD", filePath(bucket, key), retry = true).use { entryFromHeaders(bucket, key, it) }
        } catch (e: NotFoundException) {
            if (e.code == "NoSuchKey") null else throw e
        }
    }

    private fun entryFromHeaders(bucket: String, key: String, r: Response) = Entry(
        bucket = bucket, key = key,
        size = r.header("Content-Length")?.toLongOrNull() ?: -1,
        etag = r.header("ETag") ?: "",
        modified = r.header("x-pocket-modified")?.toDoubleOrNull() ?: 0.0,
    )

    /** Every file whose key starts with [prefix] (a plain string match), sorted by key; all pages. */
    fun list(prefix: String = "", bucket: String = "shared"): Flow<Entry> = flow {
        need("files")
        var cursor: String? = null
        do {
            val q = Encode.query(listOf("bucket" to bucket, "prefix" to prefix, "cursor" to cursor,
                "limit" to pageLimit.toString()))
            val page = session.call("GET", "/_pocket/list", q, retry = true).use {
                JSON.parseToJsonElement(it.body.string()).jsonObject
            }
            for (f in page["files"] as JsonArray) {
                emit(JSON.decodeFromJsonElement(Entry.serializer(), f).copy(bucket = bucket))
            }
            cursor = page.str("cursor")
        } while (cursor != null)
    }

    /** Delete one file; with [ifMatch] only if it still has that ETag. Missing: [NotFoundException]. */
    suspend fun delete(key: String, bucket: String = "shared", ifMatch: String? = null) {
        need("files")
        val h = ifMatch?.let { mapOf("if-match" to it) } ?: emptyMap()
        session.call("DELETE", filePath(bucket, key), headers = h).close()
    }

    /** Move one file, keeping its ETag and time. An existing target is [PreconditionFailedException] (`KeyExists`). */
    suspend fun move(src: String, dst: String, bucket: String = "shared", toBucket: String = bucket,
                     overwrite: Boolean = false, ifMatch: String? = null): Entry {
        need("move")
        Encode.checkKey(src); Encode.checkKey(dst)
        val body = buildJsonObject {
            putJsonObject("from") { put("bucket", bucket); put("key", src) }
            putJsonObject("to") { put("bucket", toBucket); put("key", dst) }
            ifMatch?.let { put("if_match", it) }
            put("overwrite", overwrite)
        }
        val m = session.call("POST", "/_pocket/move", body = Body.Bytes(body.toString().toByteArray(), "application/json")).use {
            JSON.parseToJsonElement(it.body.string()).jsonObject
        }
        return Entry(toBucket, dst, m["size"]!!.jsonPrimitive.content.toLong(), m.str("etag") ?: "",
            m.str("modified")?.toDoubleOrNull() ?: 0.0)
    }

    /** Read and parse a JSON file. Missing: [NotFoundException]. */
    suspend inline fun <reified T> getJson(key: String, bucket: String = "shared"): T =
        JSON.decodeFromString(serializer<T>(), get(key, bucket).decodeToString())

    /** Read and parse a JSON file, or [default] when there is none. */
    suspend inline fun <reified T> getJson(key: String, bucket: String = "shared", default: T): T = try {
        getJson<T>(key, bucket)
    } catch (e: NotFoundException) {
        if (e.code == "NoSuchKey") default else throw e
    }

    /** Write [value] as JSON (UTF-8, `application/json`). */
    suspend inline fun <reified T> putJson(key: String, value: T, bucket: String = "shared", ifMatch: String? = null): Entry =
        put(key, JSON.encodeToString(serializer<T>(), value).toByteArray(), bucket, ifMatch, "application/json")

    // ---------------------------------------------------------------- backup

    /** Client clock in unix seconds (backup names); tests move it. */
    internal var clock: () -> Double
        get() = session.clock
        set(v) { session.clock = v }

    /**
     * Upload [file] to `<prefix><name>/<UTC yyyymmdd-HHMMSS>-<name>`, then delete all but the [keep] newest there.
     * [copy] makes the consistent copy that is uploaded (give one for a database in use); without it the
     * file's bytes are copied as they are.
     */
    suspend fun backup(file: File, keep: Int = 7, prefix: String = "backups/", bucket: String = "shared",
                       copy: SafeCopy? = null): Entry {
        require(keep >= 1) { "keep is at least 1" }
        val name = file.name
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli((clock() * 1000).toLong()))
        val folder = "$prefix$name/"
        val tmp = withContext(Dispatchers.IO) {
            val t = Files.createTempFile("pocket-backup-", "-$name").toFile()
            if (copy != null) copy.copy(file, t)
            else Files.copy(file.toPath(), t.toPath(), StandardCopyOption.REPLACE_EXISTING)
            t
        }
        try {
            val entry = putFile("$folder$stamp-$name", tmp, bucket)
            val all = mutableListOf<Entry>()
            list(folder, bucket).collect { if (!it.key.substring(folder.length).contains('/')) all.add(it) }
            // the file just written always stays (a device clock that is behind would sort it last)
            for (old in all.filter { it.key != entry.key }.sortedByDescending { it.key }.drop(keep - 1)) {
                try { delete(old.key, bucket) } catch (_: NotFoundException) {}
            }
            return entry
        } finally {
            tmp.delete()
        }
    }

    /** The newest file under [prefix] to [dest]; null when there is none. */
    suspend fun restoreLatest(prefix: String, dest: File, bucket: String = "shared"): Entry? {
        var newest: Entry? = null
        list(prefix, bucket).collect { e ->
            val n = newest
            if (n == null || e.modified > n.modified || (e.modified == n.modified && e.key > n.key)) newest = e
        }
        return newest?.let { getFile(it.key, dest, bucket) }
    }

    // ---------------------------------------------------------------- sync

    /** Two-way sync between [folder] and [prefix] in [bucket]; see the README for the rules. */
    suspend fun sync(folder: File, bucket: String = "shared", prefix: String = "", allowMassDelete: Boolean = false): SyncResult =
        Sync(this, folder, bucket, prefix, allowMassDelete).run()

    // ---------------------------------------------------------------- change feed

    internal suspend fun changesPage(since: String?, limit: Int = 500, wait: Int = 0): ChangePage {
        need("changes")
        val q = Encode.query(listOf("since" to since, "limit" to limit.toString(), "wait" to wait.toString()))
        return session.call("GET", "/_pocket/changes", q, retry = true, extraReadMs = wait * 1000L).use {
            JSON.decodeFromString(ChangePage.serializer(), it.body.string())
        }
    }

    /**
     * Follow the change feed forever (long polling), oldest first, starting after [since] (or from now).
     * Cancel the collecting coroutine to stop. A cursor that is too old throws [CursorExpiredException].
     */
    fun changes(since: String? = null): Flow<Change> = flow {
        var cursor = since ?: changesPage(null).cursor
        while (true) {
            val page = changesPage(cursor, wait = if (pollWait > 0) pollWait else 0)
            for (c in page.changes) emit(c)
            cursor = page.cursor
        }
    }

    // ---------------------------------------------------------------- releases

    /**
     * Publish [file] as release [version]: upload it under `releases/<version>/`, then update `releases/latest.json`
     * (files of the same version are kept, so several platforms can share a release).
     */
    suspend fun publish(file: File, version: String, notes: String = "", platform: String? = null): Release {
        if (!VERSION.matches(version)) {
            throw PocketException("BadRequest", 0, "A version uses 1-64 of A-Z a-z 0-9 . _ + -.")
        }
        val body = withContext(Dispatchers.IO) { Body.FromFile(file, null) }
        putBody("releases/$version/${file.name}", body, "shared", null, modified = file.lastModified() / 1000.0)
        val mine = LatestFile(file.name, "$version/${file.name}", body.length,
            body.sha256, platform)
        while (true) {
            val old = try {
                session.call("GET", filePath("shared", "releases/latest.json"), retry = true).use {
                    it.header("ETag") to JSON.decodeFromString(LatestJson.serializer(), it.body.string())
                }
            } catch (e: NotFoundException) {
                null
            }
            val kept = old?.second?.takeIf { it.version == version }?.files?.filter { it.name != file.name } ?: emptyList()
            val latest = LatestJson(app, version, clock().toLong(), notes, kept + mine)
            val bytes = JSON.encodeToString(LatestJson.serializer(), latest).toByteArray()
            try {
                putBody("releases/latest.json", Body.Bytes(bytes, "application/json"), "shared",
                    ifMatch = old?.first, ifNoneMatch = old == null)
                return toRelease(latest, publishUrl())
            } catch (_: PreconditionFailedException) {
                // someone else published meanwhile: read it again
            }
        }
    }

    /** The release index. */
    fun releases(): Releases = Releases(this)

    class Releases internal constructor(private val owner: PocketApp) {
        /** The newest release (only its files for [platform] when given), or null when there is none. */
        suspend fun latest(platform: String? = null): Release? = owner.latest(platform)
    }

    /** The publish folder's key-less URL (null when the server has no Tailscale address). */
    suspend fun releasesUrl(): String? = appInfo().publish.url

    private suspend fun publishUrl(): String? = appInfo().publish.takeIf { it.enabled }?.url

    private suspend fun latest(platform: String?): Release? {
        val pub = if ("publish" in features()) publishUrl() else null
        // the key-less folder answers only on the Tailscale address: anything else falls back to the signed route
        val text = pub?.let { orNull { session.plainGet(it + "latest.json").use { r -> r.body.string() } } }
            ?: try {
                session.call("GET", filePath("shared", "releases/latest.json"), retry = true).use { it.body.string() }
            } catch (e: NotFoundException) {
                return null
            }
        val rel = toRelease(JSON.decodeFromString(LatestJson.serializer(), text), pub)
        if (platform == null) return rel
        val files = rel.files.filter { it.platform == platform || it.platform == null || it.platform == "any" }
        return if (files.isEmpty()) null else Release(rel.app, rel.version, rel.published, rel.notes, files)
    }

    /** null when the server refused or could not be reached (a fallback follows); cancellation still propagates. */
    private suspend fun <T> orNull(block: suspend () -> T): T? = try { block() } catch (_: PocketException) { null }

    private fun toRelease(l: LatestJson, pub: String?) = Release(l.app, l.version, l.published, l.notes,
        l.files.map { f ->
            // latest.json keeps paths raw; each segment is percent-encoded only to build the URL
            val url = pub?.plus(f.path.split('/').joinToString("/") { Encode.component(it) })
            ReleaseFile(f.name, f.path, f.size, f.sha256, f.platform, url, this)
        })

    internal suspend fun downloadRelease(f: ReleaseFile, dest: File): File {
        val target = if (dest.isDirectory) File(dest, f.name) else dest
        val resp = f.url?.let { orNull { session.plainGet(it) } }
            ?: session.call("GET", filePath("shared", "releases/" + f.path), retry = true)
        resp.use {
            withContext(Dispatchers.IO) {
                val dir = target.absoluteFile.parentFile.also { d -> d.mkdirs() }
                val tmp = File(dir, ".pocket-tmp-" + System.nanoTime())
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    it.body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                md.update(buf, 0, n); out.write(buf, 0, n); size += n
                            }
                        }
                    }
                    val sha = Signer.hex(md.digest())
                    if (size != f.size || sha != f.sha256.lowercase()) {
                        throw PocketException("ChecksumMismatch", 0,
                            "${f.name} did not match its release record (size $size, sha256 $sha).")
                    }
                    replaceFile(tmp, target)
                } finally {
                    tmp.delete()
                }
            }
        }
        return target
    }

    // ---------------------------------------------------------------- notify, share, quota

    /** Send a notification to people subscribed to `pocket-<app>-notify`. Returns the ntfy message id. Not retried. */
    suspend fun notify(message: String, title: String? = null, tags: List<String>? = null, priority: Int = 3,
                       click: String? = null): String {
        need("notify")
        val body = buildJsonObject {
            put("message", message)
            title?.let { put("title", it) }
            tags?.let { t -> putJsonArray("tags") { t.forEach { add(it) } } }
            put("priority", priority)
            click?.let { put("click", it) }
        }
        return session.call("POST", "/_pocket/notify", body = Body.Bytes(body.toString().toByteArray(), "application/json")).use {
            JSON.parseToJsonElement(it.body.string()).jsonObject.str("id") ?: ""
        }
    }

    /** A share link to one file, valid for [expires] seconds. */
    suspend fun share(key: String, expires: Long = 86_400, bucket: String = "shared", password: String? = null,
                      maxDownloads: Int? = null): Share {
        need("share")
        Encode.checkKey(key)
        val body = buildJsonObject {
            put("bucket", bucket); put("key", key); put("expires_s", expires)
            password?.let { put("password", it) }
            maxDownloads?.let { put("max_downloads", it) }
        }
        return session.call("POST", "/_pocket/share", body = Body.Bytes(body.toString().toByteArray(), "application/json")).use {
            JSON.decodeFromString(Share.serializer(), it.body.string())
        }
    }

    /** What this device may still write. */
    suspend fun quota(): Quota {
        need("quota")
        return session.call("GET", "/_pocket/quota", retry = true).use { JSON.decodeFromString(Quota.serializer(), it.body.string()) }
    }

    override fun toString() = "PocketApp($app on ${session.endpoint} as ${session.deviceId})"

    internal companion object {
        const val LONG_POLL_S = 30
        val VERSION = Regex("^[A-Za-z0-9._+-]{1,64}$")
    }
}
