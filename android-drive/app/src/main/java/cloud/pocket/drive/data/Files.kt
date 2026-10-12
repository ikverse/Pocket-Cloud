package cloud.pocket.drive.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Facts about something the user picked to upload: a file path or a content:// address. */
data class Picked(val uri: String, val name: String, val size: Long, val modified: Long)

/** Reading local files, whether they are given as paths or as content:// addresses. */
object Local {
    private fun isPath(src: String) = src.startsWith("/") || src.startsWith("file:")
    private fun fileOf(src: String) = File(if (src.startsWith("file:")) Uri.parse(src).path ?: src else src)

    fun size(ctx: Context, src: String): Long {
        if (isPath(src)) return fileOf(src).length()
        ctx.contentResolver.openFileDescriptor(Uri.parse(src), "r")?.use { return it.statSize }
        return -1
    }

    fun modified(ctx: Context, src: String): Long {
        if (isPath(src)) return fileOf(src).lastModified()
        return describe(ctx, Uri.parse(src))?.modified ?: 0
    }

    fun describe(ctx: Context, uri: Uri): Picked? {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "last_modified"), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: uri.lastPathSegment ?: "file"
            val size = if (c.isNull(1)) -1 else c.getLong(1)
            val modified = if (c.columnCount > 2 && !c.isNull(2)) c.getLong(2) else 0
            return Picked(uri.toString(), name, size, modified)
        }
        return null
    }

    /** Read [len] bytes starting at [offset]. */
    fun readRange(ctx: Context, src: String, offset: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        if (isPath(src)) {
            RandomAccessFile(fileOf(src), "r").use { f -> f.seek(offset); f.readFully(out) }
            return out
        }
        val uri = Uri.parse(src)
        val seekable = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val channel = FileInputStream(pfd.fileDescriptor).channel
                channel.position(offset)
                val buf = java.nio.ByteBuffer.wrap(out)
                while (buf.hasRemaining()) if (channel.read(buf) < 0) throw IOException("The file ended early.")
                true
            } ?: false
        } catch (e: java.io.EOFException) {
            throw e
        } catch (e: IOException) {
            if (e.message?.contains("ended early") == true) throw e
            false           // a provider that hands out a pipe can't seek
        }
        if (seekable) return out
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            skipFully(s, offset)
            var n = 0
            while (n < len) {
                val r = s.read(out, n, len - n)
                if (r < 0) throw IOException("The file ended early.")
                n += r
            }
            return out
        }
        throw IOException("Can't open the file.")
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) { if (s.read() < 0) throw IOException("The file ended early."); left-- } else left -= k
        }
    }

    /** Every file under a folder the user picked with the system folder picker, with its path below the folder. */
    fun walkTree(ctx: Context, tree: Uri): List<Pair<String, Picked>> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val out = mutableListOf<Pair<String, Picked>>()
        fun walk(dir: DocumentFile, rel: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) walk(f, "$rel$name/")
                else if (f.isFile) out += (rel + name) to Picked(f.uri.toString(), name, f.length(), f.lastModified())
            }
        }
        walk(root, (root.name ?: "folder") + "/")
        return out
    }
}

/** The public Downloads folder, through MediaStore (needs no storage permission). */
object Downloads {
    /** Copy [file] into Downloads/Pocket Drive/[subdir]/[name]; returns where it ended up. */
    fun save(ctx: Context, file: File, name: String, subdir: String, mime: String): Uri {
        val relative = (Environment.DIRECTORY_DOWNLOADS + "/Pocket Drive/" + subdir).trimEnd('/') + "/"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create the file in Downloads.")
        try {
            cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IOException("Can't write to Downloads.")
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}
