package cloud.pocket.drive.ui.components

import java.util.Locale

/** The kinds of file that get their own tile colour (the mockup's file-type tiles). */
enum class FileKind(val argb: Long) {
    PDF(0xFFD1344B), SHEET(0xFF17966B), IMAGE(0xFF8A4FD6), VIDEO(0xFFC97A00), AUDIO(0xFFB4487F),
    ARCHIVE(0xFF4A5578), TEXT(0xFF56607D), APP(0xFF3350E8), OTHER(0xFF56607D),
}

object FileTypes {
    private val kinds: Map<String, FileKind> = buildMap {
        listOf("pdf").forEach { put(it, FileKind.PDF) }
        listOf("xls", "xlsx", "ods", "csv", "tsv", "numbers").forEach { put(it, FileKind.SHEET) }
        listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tif", "tiff", "raw", "dng").forEach { put(it, FileKind.IMAGE) }
        listOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v").forEach { put(it, FileKind.VIDEO) }
        listOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus").forEach { put(it, FileKind.AUDIO) }
        listOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2", "tgz").forEach { put(it, FileKind.ARCHIVE) }
        listOf("txt", "md", "log", "json", "xml", "yaml", "yml", "doc", "docx", "odt", "rtf", "ppt", "pptx", "odp", "html", "kt", "py", "js").forEach { put(it, FileKind.TEXT) }
        listOf("apk", "aab", "exe", "msi").forEach { put(it, FileKind.APP) }
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").takeIf { '.' in name }?.lowercase(Locale.ROOT) ?: ""

    fun kind(name: String): FileKind = kinds[extension(name)] ?: FileKind.OTHER

    /** What the tile says: up to four letters of the extension, upper case; "FILE" when there is none. */
    fun label(name: String): String = extension(name).take(4).uppercase(Locale.ROOT).ifEmpty { "FILE" }
}
