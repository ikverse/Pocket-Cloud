package cloud.pocket.drive.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * The tag prefix that marks a release of this app.
 *
 * Both Pocket Cloud apps live in one repository, so each one's releases are told apart by their
 * tag: `drive-v0.2.0` is Pocket Drive, `panel-v0.2.0` is the control panel. A tag without this
 * prefix is some other app's release, or none at all, and is never offered here.
 */
const val TAG_PREFIX = "drive-v"

/** The name a fetched APK is kept under; see [downloadFileName]. */
private const val DOWNLOAD_PREFIX = "pocket-drive-"

/**
 * Where an interrupted download carries on from, given what the server answered.
 *
 * Only a 206 means the server honoured the range and is sending the rest. A 200 is the whole file
 * again - some servers ignore the header, and a CDN can answer a resumed request with a fresh copy
 * - and appending that to what is already on disk builds a file that is neither.
 */
fun resumeOffset(responseCode: Int, bytesOnDisk: Long): Long =
    if (responseCode == PARTIAL_CONTENT && bytesOnDisk > 0) bytesOnDisk else 0L

/**
 * How big the whole file is, given a response that may only be describing what is left of it.
 *
 * A resumed request's Content-Length counts the remainder, not the file, so the bytes already on
 * disk have to be added back or the progress bar reports 40% as 100%.
 */
fun totalBytes(contentLength: Long, resumeFrom: Long, offeredSize: Long): Long =
    if (contentLength > 0) contentLength + resumeFrom else offeredSize

private const val PARTIAL_CONTENT = 206

/**
 * Which of a release's APKs this device should download.
 *
 * These apps carry no native libraries, so a release holds one APK named for nothing but its
 * version. The architecture rules stay so a release split by chip one day still hands each phone
 * the right file: [abis] is the device's own list, best first.
 */
fun preferredApkName(names: List<String>, abis: List<String>): String? {
    val apks = names.filter { it.endsWith(APK_SUFFIX, ignoreCase = true) }
    if (apks.isEmpty()) return null
    abis.forEach { abi ->
        apks.firstOrNull { it.endsWith("-$abi$APK_SUFFIX", ignoreCase = true) }?.let { return it }
    }
    apks.firstOrNull { it.endsWith(UNIVERSAL_SUFFIX, ignoreCase = true) }?.let { return it }
    // An APK named for an architecture this device did not ask for is deliberately not a fallback:
    // Android refuses to install one, so offering it would promise an update that cannot happen.
    return apks.firstOrNull { name ->
        KNOWN_ABIS.none { name.endsWith("-$it$APK_SUFFIX", ignoreCase = true) }
    }
}

/** The version a tag names, or null when the tag is not one of this app's. */
fun tagVersion(tag: String, prefix: String = TAG_PREFIX): AppVersion? {
    val trimmed = tag.trim()
    if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
    return AppVersion.parse(trimmed.substring(prefix.length))
}

/**
 * Reads one GitHub release, or null for anything that is not an installable one of this app's.
 *
 * A release with no APK attached is not an update - a tag pushed while the build was still running
 * produces exactly that, and offering it would send someone to a Download button with nothing
 * behind it. Drafts and pre-releases are refused here as well as by the endpoint, because the
 * endpoint is a URL that could be pointed somewhere else and this is the rule.
 */
fun readRelease(
    document: String,
    abis: List<String> = emptyList(),
    prefix: String = TAG_PREFIX,
): AvailableUpdate? = runCatching { readRelease(JSONObject(document), abis, prefix) }.getOrNull()

private fun readRelease(json: JSONObject, abis: List<String>, prefix: String): AvailableUpdate? {
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
    val tag = json.optString("tag_name")
    val version = tagVersion(tag, prefix) ?: return null
    val assets = json.optJSONArray("assets") ?: return null
    val downloadable = (0 until assets.length())
        .mapNotNull(assets::optJSONObject)
        .filter { it.optString("browser_download_url").isNotBlank() }
    val chosen = preferredApkName(downloadable.map { it.optString("name") }, abis)
        ?.let { name -> downloadable.first { it.optString("name") == name } }
        ?: return null
    return AvailableUpdate(
        version = version,
        versionName = tag.trim().substring(prefix.length).trimStart('v', 'V').ifBlank { version.toString() },
        notes = json.optString("body").trim(),
        downloadUrl = chosen.optString("browser_download_url"),
        sizeBytes = chosen.optLong("size"),
    )
}

/**
 * The newest installable release of this app in a releases listing, or null when there is none.
 *
 * The listing is asked for rather than `/releases/latest` because that endpoint knows nothing of
 * prefixes: with two apps in one repository, "latest" is whichever app was released last. A single
 * release object is accepted too, so a listing of one and the one itself read the same.
 */
fun newestRelease(
    document: String,
    abis: List<String> = emptyList(),
    prefix: String = TAG_PREFIX,
): AvailableUpdate? = runCatching {
    val text = document.trim()
    val releases = if (text.startsWith("[")) {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull(array::optJSONObject)
    } else {
        listOf(JSONObject(text))
    }
    releases.mapNotNull { readRelease(it, abis, prefix) }.maxByOrNull { it.version }
}.getOrNull()

/**
 * The update worth telling someone about, or null when there is none.
 *
 * Strictly newer, never merely different: reinstalling the version already on the phone is not an
 * update, and a release republished to fix its notes must not send every device back to the
 * release page. A version this build cannot make sense of offers nothing rather than everything.
 */
fun updateOffered(
    currentVersionName: String,
    document: String,
    abis: List<String> = emptyList(),
    prefix: String = TAG_PREFIX,
): AvailableUpdate? {
    val current = AppVersion.parse(currentVersionName) ?: return null
    return newestRelease(document, abis, prefix)?.takeIf { it.version > current }
}

/**
 * The name a fetched APK is kept under while it waits to be installed.
 *
 * The version is in the name because the name is all that survives. Granting "install unknown apps"
 * restarts the app on some phones - Samsung force-stops it - and everything the app knew about the
 * download dies with the process while the file itself sits there.
 */
fun downloadFileName(versionName: String): String = "$DOWNLOAD_PREFIX$versionName$APK_SUFFIX"

/** The version a kept APK names, or null when the file is not one this app wrote. */
fun downloadedVersionName(fileName: String): String? = fileName
    .takeIf { it.startsWith(DOWNLOAD_PREFIX) && it.endsWith(APK_SUFFIX) }
    ?.removePrefix(DOWNLOAD_PREFIX)
    ?.removeSuffix(APK_SUFFIX)
    ?.takeIf { AppVersion.parse(it) != null }

/**
 * The version a kept APK would install, or null when there is no reason to keep it.
 *
 * Anything this build has already caught up with is rubbish: the file was written by the version
 * before this one, and offering to install it would walk someone backwards.
 */
fun downloadedUpdateVersion(fileName: String, currentVersionName: String): AppVersion? {
    val downloaded = downloadedVersionName(fileName)?.let(AppVersion::parse) ?: return null
    val current = AppVersion.parse(currentVersionName) ?: return null
    return downloaded.takeIf { it > current }
}

/**
 * Whether a download that appears to have ended is actually incomplete.
 *
 * A connection closed early reads as end-of-file with no exception, so "the stream ended" and "the
 * file arrived" are the same event to a reader. The release states the byte count, which is the
 * only thing that tells them apart. A release that stated no size cannot be checked, and an
 * unverifiable download is not a failed one.
 */
fun downloadIsShort(bytesOnDisk: Long, expectedBytes: Long): Boolean =
    expectedBytes > 0 && bytesOnDisk < expectedBytes

private const val APK_SUFFIX = ".apk"
private const val UNIVERSAL_SUFFIX = "-universal$APK_SUFFIX"

/** Only used to recognise a name as architecture-specific, never to decide what a device can run. */
private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
