package cloud.pocket.drive.data.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import cloud.pocket.drive.data.DriveDb
import cloud.pocket.drive.data.HashEntry
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One file found on this phone. [source] is what an upload reads: a path, or a content:// address for media. */
data class LocalEntry(val rel: String, val size: Long, val mtime: Long, val source: String)

/** What can't be scanned right now, in words for the user. */
class ScanProblem(message: String) : Exception(message)

object FileScanner {
    /** Names the sync never touches: its own temporary files and its trash. */
    fun ignored(name: String) = name.startsWith(".pocket-") || name.startsWith(".rclone_temp_")

    /** Every regular file below [root]. Throws [ScanProblem] when the folder can't be read, so an unavailable
     *  folder is never mistaken for an empty one. */
    fun scan(root: File): Map<String, LocalEntry> {
        if (!root.isDirectory) throw ScanProblem("The folder ${root.path} isn't available (is the storage connected?).")
        val out = linkedMapOf<String, LocalEntry>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: if (dir == root) throw ScanProblem("Can't read ${root.path}. Pocket Drive needs access to all files for this folder.") else continue
            for (f in children) {
                if (ignored(f.name)) continue
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) stack.add(f)
                else if (f.isFile) {
                    val rel = f.relativeTo(root).path.replace('\\', '/')
                    out[rel] = LocalEntry(rel, f.length(), f.lastModified(), f.path)
                }
            }
        }
        return out
    }
}

/** A folder of photos or videos as MediaStore knows it, e.g. DCIM/Camera/. */
data class MediaFolder(val path: String, val images: Int, val videos: Int)

object MediaScanner {
    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val files: Uri get() = MediaStore.Files.getContentUri("external")
    private fun typeClause(scope: String) = when (scope) {
        "images" -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
        "videos" -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        else -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
    }

    /** The photo and video files in [folder] (and below it). With [byDate] they are filed as year/month/name. */
    fun scan(ctx: Context, folder: String, scope: String, byDate: Boolean): Map<String, LocalEntry> {
        if (!hasPermission(ctx)) throw ScanProblem("Pocket Drive isn't allowed to read photos and videos.")
        val out = linkedMapOf<String, LocalEntry>()
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.RELATIVE_PATH)
        val like = Keys.like(folder)
        ctx.contentResolver.query(files, cols, "${typeClause(scope)} AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(like), null)?.use { c ->
            val year = SimpleDateFormat("yyyy", Locale.US)
            val month = SimpleDateFormat("MM", Locale.US)
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val size = c.getLong(2)
                val modified = c.getLong(3) * 1000
                val taken = if (c.isNull(4)) 0L else c.getLong(4)
                val relPath = c.getString(5) ?: ""
                if (size <= 0) continue                                    // still being written
                val sub = relPath.removePrefix(folder)
                val rel = if (byDate) {
                    val d = Date(if (taken > 0) taken else modified)
                    "${year.format(d)}/${month.format(d)}/$name"
                } else sub + name
                out[rel] = LocalEntry(rel, size, modified, ContentUris.withAppendedId(files, id).toString())
            }
        }
        return out
    }

    /** Every folder that holds photos or videos, with counts, for the picker. */
    fun folders(ctx: Context): List<MediaFolder> {
        if (!hasPermission(ctx)) return emptyList()
        val counts = linkedMapOf<String, IntArray>()
        val cols = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.MEDIA_TYPE)
        ctx.contentResolver.query(files, cols, typeClause("both"), null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0) ?: continue
                val arr = counts.getOrPut(p) { IntArray(2) }
                if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) arr[1]++ else arr[0]++
            }
        }
        return counts.map { (p, a) -> MediaFolder(p, a[0], a[1]) }.sortedByDescending { it.images + it.videos }
    }

    /** The content addresses to watch for new photos and videos. */
    val watchedUris: List<Uri> get() = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
}

private object Keys {
    /** SQL LIKE pattern for a folder and everything below it (escaping the wildcards in the name). */
    fun like(folder: String): String = folder.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}

/** MD5 of a stream as 32 hex letters (the form the server's checksums are in). */
object Md5 {
    fun hex(s: InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        s.use {
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Checksums of local files, read once per version of a file. Blocking: call off the main thread. */
class Hasher(private val db: DriveDb) {
    fun md5(cacheKey: String, size: Long, mtime: Long, open: () -> InputStream?): String? {
        val dao = db.sync()
        dao.hashNow(cacheKey)?.takeIf { it.size == size && it.mtime == mtime }?.let { return it.md5 }
        val hex = Md5.hex(open() ?: return null)
        dao.putHashNow(HashEntry(cacheKey, size, mtime, hex))
        return hex
    }

    fun md5(ctx: Context, e: LocalEntry): String? = md5(e.source, e.size, e.mtime) {
        if (e.source.startsWith("/")) FileInputStream(e.source) else ctx.contentResolver.openInputStream(Uri.parse(e.source))
    }
}

/** Files that were deleted on the server are kept here, on the phone, for 30 days. */
object LocalTrash {
    const val DIR = ".pocket-trash"
    private const val KEEP_DAYS = 30

    data class Item(val rel: String, val file: File, val deletedAt: Long)

    fun put(root: File, rel: String): Boolean {
        val src = File(root, rel)
        if (!src.isFile) return false
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val dst = File(root, "$DIR/$day/$rel")
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
        removeEmptyDirs(src.parentFile, root)
        return true
    }

    fun list(root: File): List<Item> {
        val trash = File(root, DIR)
        val out = mutableListOf<Item>()
        val days = trash.listFiles()?.filter { it.isDirectory } ?: return out
        for (d in days) for (f in d.walkTopDown().filter { it.isFile }) {
            out += Item(f.relativeTo(d).path.replace('\\', '/'), f, f.lastModified().coerceAtLeast(runCatching { SimpleDateFormat("yyyyMMdd", Locale.US).parse(d.name)?.time }.getOrNull() ?: 0))
        }
        return out.sortedByDescending { it.deletedAt }
    }

    /** Put a trashed file back where it was (it becomes a new local file and goes up with the next run). */
    fun restore(root: File, item: Item): Boolean {
        val dst = File(root, item.rel)
        if (dst.exists()) return false
        dst.parentFile?.mkdirs()
        if (!item.file.renameTo(dst)) { item.file.copyTo(dst); item.file.delete() }
        return true
    }

    fun purge(root: File) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600_000L
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        File(root, DIR).listFiles()?.forEach { d ->
            val t = runCatching { fmt.parse(d.name)?.time }.getOrNull() ?: return@forEach
            if (t < limit) d.deleteRecursively()
        }
    }

    fun removeEmptyDirs(start: File?, stop: File) {
        var d = start
        while (d != null && d != stop && d.path.startsWith(stop.path)) {
            if (d.list()?.isEmpty() != true) return
            d.delete()
            d = d.parentFile
        }
    }
}

/** What kind of network and power the phone has right now. */
object Net {
    /** True on Wi-Fi or Ethernet, also when a VPN such as Tailscale runs on top of it. */
    fun onUnmeteredLink(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    fun charging(ctx: Context): Boolean {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        return bm.isCharging
    }

    /** Syncing a folder by its path needs "all files access", which exists from Android 11. */
    fun folderSyncSupported(): Boolean = Build.VERSION.SDK_INT >= 30

    fun allFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
}
