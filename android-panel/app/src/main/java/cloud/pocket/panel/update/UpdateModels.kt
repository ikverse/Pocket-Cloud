package cloud.pocket.panel.update

import java.io.File
import java.util.Locale

/** A build that exists somewhere else, and everything needed to decide whether to fetch it. */
data class AvailableUpdate(
    val version: AppVersion,
    /** As the release names itself, so the screen and the release page say the same thing. */
    val versionName: String,
    /** What the release says changed. Empty when the release said nothing. */
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
) {
    /** The size in the unit someone on mobile data decides with. */
    val sizeLabel: String get() = byteLabel(sizeBytes)
}

/**
 * A count of bytes as someone on mobile data would say it.
 *
 * US digits on purpose: this figure sits beside a version number, and one of the two rendering as
 * Arabic-Indic digits while the other does not reads as a fault in the app.
 */
fun byteLabel(bytes: Long): String = when {
    bytes <= 0 -> "size unknown"
    bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / BYTES_PER_MB)
    else -> "${bytes / 1024} KB"
}

/**
 * What a downloaded APK turned out to be.
 *
 * Damaged and wrong-key are kept apart: a truncated file cannot be read, an unreadable file has no
 * certificates, and no certificates compares unequal to this build's. Folded together, an ordinary
 * interrupted download would report that the release was signed by someone else.
 */
enum class DownloadedApk {
    /** Signed by the key this build was signed with. The only one worth installing. */
    MATCHES,

    /** Readable, and signed by something else. Nothing to do but uninstall by hand. */
    WRONG_KEY,

    /** Not a readable APK at all. Fetch it again. */
    DAMAGED,
}

/** Where the update check has got to, so the screen can say the same thing the app is doing. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Nothing newer exists. Carries the version asked about, so the answer names what it answered. */
    data class UpToDate(val versionName: String) : UpdateState

    data class Available(val update: AvailableUpdate) : UpdateState

    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState

    /** Downloaded, checked, and waiting for the one tap that hands it to Android's installer. */
    data class Ready(val update: AvailableUpdate, val file: File) : UpdateState

    data class Failed(val reason: String) : UpdateState
}

private const val BYTES_PER_MB = 1024 * 1024
