package cloud.pocket.lite

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** A file: [modified] is unix seconds. ETags are compared as opaque strings, quotes included. */
@Serializable
data class Entry(
    val bucket: String = "shared",
    val key: String,
    val size: Long,
    val etag: String,
    val modified: Double,
)

@Serializable
data class Location(val bucket: String, val key: String)

/** One change-feed record (section 5 of docs/app-api.md). [kind] is `put`, `delete` or `move`. */
@Serializable
data class Change(
    val seq: Long,
    val bucket: String,
    val key: String,
    val kind: String,
    val from: Location? = null,
    val size: Long? = null,
    val etag: String? = null,
    val time: Double,
    val device: String? = null,
    val source: String,
)

@Serializable
internal data class ChangePage(val changes: List<Change>, val cursor: String, val more: Boolean = false)

/** The newest release in the publish folder; [files] is already filtered by platform when one was asked for. */
class Release internal constructor(
    val app: String,
    val version: String,
    val published: Long,
    val notes: String,
    val files: List<ReleaseFile>,
) {
    /** Download the first file (to [dest], or into it when it is a folder) and check its size and sha256. */
    suspend fun download(dest: File): File =
        (files.firstOrNull() ?: throw NotFoundException("NotFound", 0, "This release has no files.")).download(dest)

    override fun toString() = "Release($app $version, ${files.map { it.name }})"
}

class ReleaseFile internal constructor(
    val name: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val platform: String?,
    /** Key-less URL in the publish folder, or null when the folder is off (then the signed route is used). */
    val url: String?,
    private val owner: PocketApp,
) {
    /** Download to [dest] (or into it when it is a folder); refuses a size or sha256 mismatch. */
    suspend fun download(dest: File): File = owner.downloadRelease(this, dest)

    override fun toString() = "ReleaseFile($name, $size bytes, $platform)"
}

@Serializable
internal data class LatestJson(
    val app: String,
    val version: String,
    val published: Long,
    val notes: String = "",
    val files: List<LatestFile> = emptyList(),
)

@Serializable
internal data class LatestFile(val name: String, val path: String, val size: Long, val sha256: String,
                               val platform: String? = null)

/** A share link (the existing `POST /_pocket/share` answer). Times are unix seconds. */
@Serializable
data class Share(
    val token: String,
    val url: String,
    val public: Boolean = false,
    val bucket: String,
    val key: String,
    val created: Double,
    val expires: Double,
    val downloads: Int = 0,
    @SerialName("max_downloads") val maxDownloads: Int? = null,
    @SerialName("has_password") val hasPassword: Boolean = false,
)

/** `GET /_pocket/quota`, fields unchanged. Sizes in bytes. */
@Serializable
data class Quota(
    val app: String,
    @SerialName("device_id") val deviceId: String,
    val allocation: Long,
    @SerialName("used_app") val usedApp: Long,
    @SerialName("used_device") val usedDevice: Long,
    @SerialName("device_cap") val deviceCap: Long? = null,
    val reserve: Long = 0,
    @SerialName("held_for_others") val heldForOthers: Long = 0,
    val free: Long,
    @SerialName("free_shared") val freeShared: Long,
    @SerialName("read_only") val readOnly: Boolean = false,
)

/** What [PocketApp.sync] did, as lists of keys. */
data class SyncResult(
    val uploaded: List<String>,
    val downloaded: List<String>,
    val deletedLocal: List<String>,
    val deletedRemote: List<String>,
    val conflicts: List<String>,
)

/** `GET /_pocket/app`: what this app and device may use. */
@Serializable
data class AppInfo(
    val app: String,
    @SerialName("device_id") val deviceId: String,
    val buckets: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val publish: Switch = Switch(),
    val notify: Switch = Switch(),
    val push: Push? = null,
    val retention: List<RetentionRule> = emptyList(),
    val changes: ChangesInfo? = null,
    @SerialName("read_only") val readOnly: Boolean = false,
) {
    @Serializable
    data class Switch(val enabled: Boolean = false, val url: String? = null, val topic: String? = null)
    @Serializable
    data class Push(val topic: String, val url: String)
    @Serializable
    data class RetentionRule(val bucket: String, val prefix: String,
                             @SerialName("keep_last") val keepLast: Int? = null,
                             @SerialName("keep_days") val keepDays: Int? = null)
    @Serializable
    data class ChangesInfo(@SerialName("keep_days") val keepDays: Int = 30)
}

/**
 * Makes a consistent copy of a file that is in use, for [PocketApp.backup]. For an SQLite database, use the
 * database's own backup (`VACUUM INTO '<dest>'`, or Android's `SQLiteDatabase` with a checkpoint first) rather
 * than copying the bytes of a file that may be mid-write.
 */
fun interface SafeCopy {
    fun copy(src: File, dest: File)
}
