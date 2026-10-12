package cloud.pocket.drive.data.sync

import cloud.pocket.drive.data.DOWNLOAD_ONLY
import cloud.pocket.drive.data.TWO_WAY
import cloud.pocket.drive.data.UPLOAD_ONLY
import kotlin.math.abs

/** A file on this phone: size and modification time (ms). */
data class LFile(val size: Long, val mtime: Long)

/** A file on the server. [etag] is its MD5 checksum when the server gave one. */
data class RFile(val size: Long, val mtime: Long, val etag: String?)

/** What a file looked like on both sides the last time they matched. */
data class Base(val localSize: Long, val localMtime: Long, val remoteSize: Long, val remoteMtime: Long, val etag: String)

sealed interface Action {
    val path: String

    data class Upload(override val path: String) : Action
    data class Download(override val path: String) : Action
    data class DeleteRemote(override val path: String) : Action
    data class DeleteLocal(override val path: String) : Action
    data class MoveRemote(val from: String, val to: String) : Action { override val path get() = to }
    data class MoveLocal(val from: String, val to: String) : Action { override val path get() = to }

    /** Both sides changed: the local file takes [conflictPath] (and is uploaded there), the server's file takes [path]. */
    data class Conflict(override val path: String, val conflictPath: String) : Action

    /** Same content on both sides: only remember it. */
    data class Adopt(override val path: String) : Action

    /** Gone from both sides: forget it. */
    data class Forget(override val path: String) : Action
}

/** A run that would delete a lot, held back until the user says what to do. [from] is "local", "remote" or "both". */
data class Guard(val deletes: Int, val from: String)

data class Plan(val actions: List<Action>, val guard: Guard?, val skipped: List<String>)

data class PlanConfig(
    val mode: String = TWO_WAY,
    val newestWins: Boolean = false,
    val device: String = "device",
    val today: String = "today",
    /** The user's answer to an earlier paused run: "", "delete" or "restore". */
    val decision: String = "",
)

object Planner {
    /** Modification times closer than this are the same (FAT cards store 2-second times). */
    const val TOLERANCE_MS = 2000L
    const val GUARD_COUNT = 50
    const val GUARD_MIN_FOR_SHARE = 5

    fun sameTime(a: Long, b: Long) = abs(a - b) <= TOLERANCE_MS

    fun localChanged(l: LFile, b: Base) = l.size != b.localSize || !sameTime(l.mtime, b.localMtime)

    fun remoteChanged(r: RFile, b: Base): Boolean {
        if (r.size != b.remoteSize) return true
        if (r.etag != null && b.etag.isNotEmpty()) return r.etag != b.etag
        return !sameTime(r.mtime, b.remoteMtime)
    }

    /** "dir/photo.jpg" -> "dir/photo (conflict, Pixel, 2026-10-12).jpg" */
    fun conflictName(path: String, device: String, today: String): String {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val last = name.lastIndexOf('.')
        val tar = if (last > 0) name.lastIndexOf(".tar", last - 1) else -1       // archive.tar.gz keeps both parts together
        val dot = if (tar > 0 && tar + 4 == last) tar else last.takeIf { it > 0 } ?: name.length
        val renamed = name.substring(0, dot) + " (conflict, $device, $today)" + name.substring(dot)
        return if (dir.isEmpty()) renamed else "$dir/$renamed"
    }

    /**
     * Compare this phone, the server and the last common state, and say what to do for every path.
     * [md5] gives the checksum of a local file (read lazily, only where it settles a question).
     */
    fun plan(local: Map<String, LFile>, remote: Map<String, RFile>, base: Map<String, Base>, cfg: PlanConfig,
             md5: (String) -> String? = { null }): Plan {
        val up = cfg.mode != DOWNLOAD_ONLY
        val down = cfg.mode != UPLOAD_ONLY
        val deleteRemoteAllowed = cfg.mode == TWO_WAY
        val deleteLocalAllowed = cfg.mode != UPLOAD_ONLY

        // names that differ only by letter case can't both live on every storage: leave them alone
        val skipped = caseClashes(local.keys + remote.keys).toSet()

        val out = mutableListOf<Action>()
        val newUploads = mutableListOf<String>()        // on this phone only, never synced
        val newDownloads = mutableListOf<String>()      // on the server only, never synced
        val deleteRemote = mutableListOf<String>()      // deleted here, untouched on the server
        val deleteLocal = mutableListOf<String>()       // deleted on the server, untouched here

        fun same(p: String, l: LFile, r: RFile): Boolean =
            l.size == r.size && (sameTime(l.mtime, r.mtime) || (r.etag != null && md5(p) == r.etag))

        fun bothDiffer(p: String, l: LFile, r: RFile) {
            when {
                cfg.mode == UPLOAD_ONLY -> out += Action.Upload(p)
                cfg.mode == DOWNLOAD_ONLY -> out += Action.Download(p)
                cfg.newestWins -> out += if (l.mtime >= r.mtime) Action.Upload(p) else Action.Download(p)
                else -> out += Action.Conflict(p, conflictName(p, cfg.device, cfg.today))
            }
        }

        val paths = (local.keys + remote.keys + base.keys).toSortedSet()
        for (p in paths) {
            if (p in skipped) continue
            val l = local[p]
            val r = remote[p]
            val b = base[p]
            when {
                l == null && r == null -> if (b != null) out += Action.Forget(p)
                b == null -> when {
                    l != null && r == null -> if (up) newUploads += p
                    l == null && r != null -> if (down) newDownloads += p
                    l != null && r != null -> if (same(p, l, r)) out += Action.Adopt(p) else bothDiffer(p, l, r)
                }
                l == null -> {                                    // deleted here (r is not null)
                    if (!remoteChanged(r!!, b!!)) { if (deleteRemoteAllowed) deleteRemote += p }
                    else if (down) out += Action.Download(p)       // edited on the server meanwhile: keep it
                }
                r == null -> {                                    // deleted on the server
                    if (!localChanged(l!!, b!!)) { if (deleteLocalAllowed) deleteLocal += p }
                    else if (up) out += Action.Upload(p)           // edited here meanwhile: keep it
                }
                else -> {
                    val lc = localChanged(l!!, b!!)
                    val rc = remoteChanged(r!!, b)
                    when {
                        !lc && !rc -> {}
                        lc && !rc -> if (up) out += Action.Upload(p)
                        !lc && rc -> if (down) out += Action.Download(p)
                        same(p, l, r) -> out += Action.Adopt(p)
                        else -> bothDiffer(p, l, r)
                    }
                }
            }
        }

        // a file that vanished here and a new one of the same content that appeared: that is a rename
        if (cfg.mode == TWO_WAY) {
            val freeUploads = newUploads.toMutableList()
            val iterRemote = deleteRemote.iterator()
            while (iterRemote.hasNext()) {
                val d = iterRemote.next()
                val bd = base.getValue(d)
                val hit = freeUploads.firstOrNull { u ->
                    val lf = local.getValue(u)
                    lf.size == bd.localSize && (if (bd.etag.isNotEmpty()) md5(u) == bd.etag else sameTime(lf.mtime, bd.localMtime))
                }
                if (hit != null) {
                    freeUploads.remove(hit)
                    iterRemote.remove()
                    out += Action.MoveRemote(d, hit)
                }
            }
            newUploads.clear(); newUploads += freeUploads

            val freeDownloads = newDownloads.toMutableList()
            val iterLocal = deleteLocal.iterator()
            while (iterLocal.hasNext()) {
                val d = iterLocal.next()
                val bd = base.getValue(d)
                val hit = freeDownloads.firstOrNull { n ->
                    val rf = remote.getValue(n)
                    rf.size == bd.remoteSize && (if (bd.etag.isNotEmpty() && rf.etag != null) rf.etag == bd.etag else sameTime(rf.mtime, bd.remoteMtime))
                }
                if (hit != null) {
                    freeDownloads.remove(hit)
                    iterLocal.remove()
                    out += Action.MoveLocal(d, hit)
                }
            }
            newDownloads.clear(); newDownloads += freeDownloads
        }

        out += newUploads.map { Action.Upload(it) }
        out += newDownloads.map { Action.Download(it) }

        // deleting a lot at once needs the user's word
        val deletes = deleteRemote.size + deleteLocal.size
        var guard: Guard? = null
        if (deletes > 0) {
            val big = deletes > GUARD_COUNT || (deletes >= GUARD_MIN_FOR_SHARE && deletes * 5 > base.size)
            when {
                cfg.decision == "restore" -> {                    // bring back what was deleted, from the side that still has it
                    out += deleteRemote.map { Action.Download(it) }
                    out += deleteLocal.map { Action.Upload(it) }
                }
                big && cfg.decision != "delete" -> {
                    guard = Guard(deletes, if (deleteRemote.isNotEmpty() && deleteLocal.isNotEmpty()) "both" else if (deleteRemote.isNotEmpty()) "local" else "remote")
                }
                else -> {
                    out += deleteRemote.map { Action.DeleteRemote(it) }
                    out += deleteLocal.map { Action.DeleteLocal(it) }
                }
            }
        }
        return Plan(out, guard, skipped.toList().sorted())
    }

    /** Paths that collide when letter case is ignored (all variants are returned). */
    fun caseClashes(paths: Collection<String>): List<String> =
        paths.toSet().groupBy { it.lowercase() }.values.filter { it.size > 1 }.flatten()
}
