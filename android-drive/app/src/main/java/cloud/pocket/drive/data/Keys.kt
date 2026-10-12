package cloud.pocket.drive.data

import java.net.URLEncoder

/** The two buckets every Pocket Cloud app has. */
const val SHARED = "shared"
const val DEVICE = "device"

fun bucketLabel(bucket: String) = if (bucket == DEVICE) "This device" else "My Drive"

/** Helpers for S3 keys: "a/b/c.txt" is the file c.txt in folder a/b. Folder prefixes end in "/" (or are empty). */
object Keys {
    /** rclone's S3 server can't make an empty folder, so a new folder holds this hidden empty file. */
    const val KEEP = ".pocket-keep"

    fun isHidden(key: String) = name(key) == KEEP || name(key).startsWith(".rclone_temp_")

    fun name(key: String): String = key.trimEnd('/').substringAfterLast('/')

    /** Folder prefix of a key: "a/b/c.txt" -> "a/b/", "c.txt" -> "". */
    fun parent(key: String): String {
        val t = key.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i < 0) "" else t.substring(0, i + 1)
    }

    fun join(prefix: String, name: String): String = prefix + name.trim('/')

    fun folder(prefix: String): String = if (prefix.isEmpty() || prefix.endsWith("/")) prefix else "$prefix/"

    /** Where [key] ends up when the folder or file at [from] is moved or renamed to [to]. */
    fun moved(key: String, from: String, to: String): String =
        if (key == from) to else if (key.startsWith(from)) to + key.substring(from.length) else key

    /** Names the server can't store, or that would confuse it. Null when fine, otherwise the reason. */
    fun problem(name: String): String? = when {
        name.isBlank() -> "A name can't be empty."
        name == "." || name == ".." -> "That name can't be used."
        name.contains('/') -> "A name can't contain a slash."
        name.any { it.code < 32 } -> "A name can't contain control characters."
        name.startsWith(".rclone_temp_") || name == KEEP -> "That name is reserved."
        else -> null
    }

    /** CopySource header value: bucket/key, URL-encoded but keeping slashes. */
    fun copySource(bucket: String, key: String): String =
        "$bucket/" + key.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** A name that doesn't clash with [taken]: "photo.jpg" -> "photo (2).jpg". */
    fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while ("$stem ($n)$ext" in taken) n++
        return "$stem ($n)$ext"
    }
}
