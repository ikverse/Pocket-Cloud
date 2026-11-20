package cloud.pocket.lite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

@Serializable
internal data class SyncFile(val size: Long, val mtime: Long, val etag: String)

@Serializable
internal data class SyncState(
    val bucket: String? = null,
    val prefix: String? = null,
    val cursor: String? = null,
    /** List everything next time instead of reading the feed (deletes were held back). */
    val full: Boolean = false,
    val files: Map<String, SyncFile> = emptyMap(),
)

/**
 * Two-way sync of a folder with a prefix (docs/app-api.md section 10):
 *  - state in `<folder>/.pocket-sync.json`; nothing whose name starts with `.pocket-` is uploaded;
 *  - the first run never deletes; later runs read the change feed from the saved cursor;
 *  - a file changed on both sides keeps the remote under its key and uploads the local one as
 *    `<name> (conflict <device> <yyyy-mm-dd HHMM>).<ext>`;
 *  - more than 50 deletes in one run: none are done, the rest is, then `MassDelete` is thrown.
 */
internal class Sync(
    private val pocket: PocketApp,
    private val folder: File,
    private val bucket: String,
    private val prefix: String,
    private val allowMassDelete: Boolean,
) {
    private val stateFile = File(folder, STATE)
    private val uploaded = mutableListOf<String>()
    private val downloaded = mutableListOf<String>()
    private val deletedLocal = mutableListOf<String>()
    private val deletedRemote = mutableListOf<String>()
    private val conflicts = mutableListOf<String>()

    private data class Local(val file: File, val size: Long, val mtime: Long)

    suspend fun run(): SyncResult {
        folder.mkdirs()
        val old: SyncState? = withContext(Dispatchers.IO) {
            if (stateFile.isFile) runCatching { JSON.decodeFromString(SyncState.serializer(), stateFile.readText()) }.getOrNull()
            else null
        }?.takeIf { it.bucket == bucket && it.prefix == prefix }   // a state for another target counts as a first run
        val local = withContext(Dispatchers.IO) { scanLocal() }
        val (cursor, remote) = remoteState(old)
        val next = (old?.files ?: emptyMap()).toMutableMap()

        // plan
        val remoteDeletes = mutableListOf<String>()
        val localDeletes = mutableListOf<String>()
        val keys = (local.keys + remote.keys + (old?.files?.keys ?: emptySet())).toSortedSet()
        val actions = mutableListOf<suspend () -> Unit>()
        for (key in keys) {
            val l = local[key]
            val r = remote[key]
            val s = old?.files?.get(key)
            val lChanged = l != null && (s == null || l.size != s.size || l.mtime != s.mtime)
            val rChanged = r != null && (s == null || r != s.etag)
            when {
                l == null && r == null -> next.remove(key)
                s == null && l != null && r == null -> actions += { upload(key, l, null, next) }
                s == null && l == null && r != null -> actions += { download(key, next) }
                s == null -> actions += { bothNew(key, l!!, r!!, next) }
                l == null -> if (rChanged) actions += { download(key, next) } else remoteDeletes += key
                r == null -> if (lChanged) actions += { upload(key, l, null, next) } else localDeletes += key
                !lChanged && !rChanged -> {}
                lChanged && !rChanged -> actions += { upload(key, l, s.etag, next) }
                !lChanged -> actions += { download(key, next) }
                else -> actions += { bothNew(key, l, r, next) }
            }
        }
        for (a in actions) a()

        val count = remoteDeletes.size + localDeletes.size
        val blocked = count > MASS_DELETE && !allowMassDelete
        if (!blocked) {
            for (key in remoteDeletes) deleteRemote(key, old!!.files.getValue(key).etag, next)
            for (key in localDeletes) {
                local.getValue(key).file.delete()
                deletedLocal += key
                next.remove(key)
            }
        }
        withContext(Dispatchers.IO) {
            val tmp = File(folder, ".pocket-tmp-state-" + System.nanoTime())
            // with deletes held back, the next run lists everything so they are found again
            tmp.writeText(JSON.encodeToString(SyncState.serializer(),
                SyncState(bucket, prefix, cursor, full = blocked, files = next.toSortedMap())))
            replaceFile(tmp, stateFile)
        }
        if (blocked) {
            throw PocketException("MassDelete", 0, "This sync would delete $count files; nothing was deleted. " +
                "Pass allowMassDelete = true if that is right.")
        }
        return SyncResult(uploaded, downloaded, deletedLocal, deletedRemote, conflicts)
    }

    private fun scanLocal(): Map<String, Local> {
        val out = mutableMapOf<String, Local>()
        val root = folder.absoluteFile
        root.walkTopDown().onEnter { it == root || !it.name.startsWith(".pocket-") }.forEach { f ->
            if (!f.isFile || f.name.startsWith(".pocket-")) return@forEach
            val rel = f.relativeTo(root).invariantSeparatorsPath
            val key = prefix + rel
            if (runCatching { Encode.checkKey(key) }.isSuccess) out[key] = Local(f, f.length(), f.lastModified())
        }
        return out
    }

    /** The cursor to save and the remote files (key -> ETag) under the prefix. */
    private suspend fun remoteState(old: SyncState?): Pair<String?, Map<String, String>> {
        val hasFeed = "changes" in pocket.features()
        if (hasFeed && old?.cursor != null && !old.full) {
            try {
                val remote = old.files.mapValues { it.value.etag }.toMutableMap()
                var cursor: String = old.cursor
                while (true) {
                    val page = pocket.changesPage(cursor, limit = 1000)
                    for (c in page.changes) {
                        if (c.kind == "move") c.from?.let { if (mine(it.bucket, it.key)) remote.remove(it.key) }
                        if (!mine(c.bucket, c.key)) continue
                        if (c.kind == "delete") remote.remove(c.key) else remote[c.key] = c.etag ?: ""
                    }
                    cursor = page.cursor
                    if (!page.more) break
                }
                return cursor to remote
            } catch (e: CursorExpiredException) {
                return e.cursor to fullListing()
            }
        }
        val cursor = if (hasFeed) pocket.changesPage(null).cursor else null
        return cursor to fullListing()
    }

    private fun mine(b: String, key: String) = b == bucket && key.startsWith(prefix) &&
        key.removePrefix(prefix).split('/').none { it.startsWith(".pocket-") }

    private suspend fun fullListing(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        pocket.list(prefix, bucket).collect { if (mine(bucket, it.key)) out[it.key] = it.etag }
        return out
    }

    private fun localFile(key: String) = File(folder, key.removePrefix(prefix))

    private fun record(key: String, f: File, etag: String, next: MutableMap<String, SyncFile>) {
        next[key] = SyncFile(f.length(), f.lastModified(), etag)
    }

    private suspend fun upload(key: String, l: Local, ifMatch: String?, next: MutableMap<String, SyncFile>) {
        try {
            val body = withContext(Dispatchers.IO) { Body.FromFile(l.file, null) }
            val e = pocket.putBody(key, body, bucket, ifMatch, ifNoneMatch = ifMatch == null,
                modified = l.mtime / 1000.0)
            record(key, l.file, e.etag, next)
            uploaded += key
        } catch (_: PreconditionFailedException) {
            conflict(key, l, next)        // changed on the server meanwhile
        }
    }

    private suspend fun download(key: String, next: MutableMap<String, SyncFile>) {
        val f = localFile(key)
        try {
            val e = pocket.getFile(key, f, bucket)
            record(key, f, e.etag, next)
            downloaded += key
        } catch (e: NotFoundException) {
            if (e.code != "NoSuchKey") throw e
            next.remove(key)              // deleted on the server meanwhile
        }
    }

    private suspend fun bothNew(key: String, l: Local, remoteEtag: String, next: MutableMap<String, SyncFile>) {
        if (withContext(Dispatchers.IO) { md5(l.file) } == remoteEtag) record(key, l.file, remoteEtag, next)
        else conflict(key, l, next)
    }

    /** Keep the remote under its key; the local version goes up (and stays here) under a conflict name. */
    private suspend fun conflict(key: String, l: Local, next: MutableMap<String, SyncFile>) {
        val cKey = freeConflictKey(key)
        val cFile = localFile(cKey)
        withContext(Dispatchers.IO) {
            cFile.parentFile?.mkdirs()
            if (!l.file.renameTo(cFile)) { l.file.copyTo(cFile, overwrite = true); l.file.delete() }
            cFile.setLastModified(l.mtime)
        }
        val body = withContext(Dispatchers.IO) { Body.FromFile(cFile, null) }
        val e = pocket.putBody(cKey, body, bucket, null, ifNoneMatch = true, modified = l.mtime / 1000.0)
        record(cKey, cFile, e.etag, next)
        uploaded += cKey
        download(key, next)
        conflicts += key
        conflicts += cKey
    }

    private val usedNames = mutableSetOf<String>()

    /** A conflict name not used here, on the server, or earlier in this run: ` 2`, ` 3`, ... when needed. */
    private suspend fun freeConflictKey(key: String): String {
        var n = 1
        while (true) {
            val k = conflictKey(key, n++)
            if (k in usedNames || localFile(k).exists() || pocket.stat(k, bucket) != null) continue
            usedNames += k
            return k
        }
    }

    private fun conflictKey(key: String, n: Int): String {
        val slash = key.lastIndexOf('/')
        val dir = key.substring(0, slash + 1)
        val name = key.substring(slash + 1)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm").format(Date((pocket.clock() * 1000).toLong()))
        val device = pocket.deviceName.replace('/', '-')
        val count = if (n > 1) " $n" else ""
        return "$dir${name.substring(0, dot)} (conflict $device $stamp)$count${name.substring(dot)}"
    }

    private suspend fun deleteRemote(key: String, etag: String, next: MutableMap<String, SyncFile>) {
        try {
            pocket.delete(key, bucket, ifMatch = etag)
            deletedRemote += key
            next.remove(key)
        } catch (e: NotFoundException) {
            next.remove(key)
        } catch (_: PreconditionFailedException) {
            download(key, next)           // changed on the server meanwhile: keep the new version
        }
    }

    private fun md5(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return "\"" + Signer.hex(md.digest()) + "\""
    }

    companion object {
        const val STATE = ".pocket-sync.json"
        const val MASS_DELETE = 50
    }
}
