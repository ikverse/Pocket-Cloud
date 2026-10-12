package cloud.pocket.drive.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app is willing to call an update.
 *
 * The answer decides whether a phone offers to replace itself, so everything that is not plainly a
 * newer, installable build of this app has to come back as nothing at all.
 */
class UpdateReleaseTest {

    private val split = listOf(
        "pocket-drive-1.0.1-arm64-v8a.apk",
        "pocket-drive-1.0.1-universal.apk",
        "pocket-drive-1.0.1-x86_64.apk",
    )

    private fun release(
        tag: String = "drive-v1.0.1",
        assets: List<String> = listOf("pocket-drive-1.0.1.apk"),
        size: Long = 24_500_000,
        notes: String = "Charts fit the whole range.",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String {
        val listed = assets.joinToString(",") { name ->
            """{
                "name": "$name",
                "size": $size,
                "browser_download_url": "https://example.invalid/$name"
            }"""
        }
        return """{
            "tag_name": "$tag",
            "draft": $draft,
            "prerelease": $prerelease,
            "body": "$notes",
            "assets": [$listed]
        }"""
    }

    private fun listing(vararg releases: String) = "[${releases.joinToString(",")}]"

    @Test
    fun `a release is read into what the screen needs to show`() {
        val update = requireNotNull(readRelease(release()))

        assertEquals(AppVersion(1, 0, 1), update.version)
        assertEquals("1.0.1", update.versionName)
        assertEquals("Charts fit the whole range.", update.notes)
        assertEquals("https://example.invalid/pocket-drive-1.0.1.apk", update.downloadUrl)
        assertEquals("23.4 MB", update.sizeLabel)
    }

    /** A tag pushed while the build is still running produces exactly this. */
    @Test
    fun `a release with no APK attached is not an update`() {
        assertNull(readRelease(release(assets = emptyList())))
        assertNull(readRelease(release(assets = listOf("release-notes.txt"))))
    }

    @Test
    fun `a draft or a pre-release is not offered`() {
        assertNull(readRelease(release(draft = true)))
        assertNull(readRelease(release(prerelease = true)))
    }

    @Test
    fun `anything that is not a release reads as no release`() {
        assertNull(readRelease("not json at all"))
        assertNull(readRelease("{}"))
        assertNull(readRelease(release(tag = "nightly")))
    }

    /**
     * Both apps release from one repository, and the tag is what tells them apart.
     *
     * The other app's release is not this app's update, however much newer it is: offering it
     * would download an APK Android then refuses as a different package.
     */
    @Test
    fun `only a tag with this app's prefix is this app's release`() {
        assertNull(readRelease(release(tag = "panel-v9.0.0", assets = listOf("pocket-panel-9.0.0.apk"))))
        assertNull(readRelease(release(tag = "v1.0.1")))
        assertEquals("1.0.1", readRelease(release(tag = "Drive-V1.0.1"))?.versionName)
    }

    @Test
    fun `a tag names a version only after its prefix`() {
        assertEquals(AppVersion(1, 2, 3), tagVersion("drive-v1.2.3"))
        assertEquals(AppVersion(0, 2, 0), tagVersion("panel-v0.2.0", prefix = "panel-v"))
        assertNull(tagVersion("1.2.3"))
        assertNull(tagVersion("drive-vnext"))
    }

    /** The listing holds both apps' releases in date order; the newest of this app's wins. */
    @Test
    fun `the newest of this app's releases is picked out of the listing`() {
        val document = listing(
            release(tag = "panel-v3.0.0", assets = listOf("pocket-panel-3.0.0.apk")),
            release(tag = "drive-v1.0.1"),
            release(tag = "drive-v1.2.0", assets = listOf("pocket-drive-1.2.0.apk")),
            release(tag = "drive-v1.0.9", assets = listOf("pocket-drive-1.0.9.apk")),
        )

        assertEquals("1.2.0", newestRelease(document)?.versionName)
        assertEquals("3.0.0", newestRelease(document, prefix = "panel-v")?.versionName)
    }

    @Test
    fun `a listing with none of this app's releases offers nothing`() {
        assertNull(newestRelease(listing(release(tag = "panel-v3.0.0"))))
        assertNull(newestRelease("[]"))
        assertNull(newestRelease("nonsense"))
    }

    /** A newer release that cannot be installed does not hide an older one that can. */
    @Test
    fun `a newer release without an APK falls back to the newest installable one`() {
        val document = listing(
            release(tag = "drive-v1.3.0", assets = emptyList()),
            release(tag = "drive-v1.2.0", assets = listOf("pocket-drive-1.2.0.apk")),
        )

        assertEquals("1.2.0", newestRelease(document)?.versionName)
    }

    @Test
    fun `only a strictly newer build is offered`() {
        assertEquals("1.0.1", updateOffered("1.0.0", listing(release()))?.versionName)
        // The version already installed, and one older than it.
        assertNull(updateOffered("1.0.1", listing(release())))
        assertNull(updateOffered("1.1.0", listing(release())))
        // The debug build of the same version is the same version.
        assertNull(updateOffered("1.0.1-debug", listing(release())))
    }

    /** A build that cannot say what it is offers nothing, rather than offering everything. */
    @Test
    fun `a version this build cannot read offers nothing`() {
        assertNull(updateOffered("", listing(release())))
    }

    @Test
    fun `a device downloads the build for its own chip`() {
        val phone = readRelease(release(assets = split), abis = listOf("arm64-v8a", "armeabi-v7a"))
        val emulator = readRelease(release(assets = split), abis = listOf("x86_64", "x86"))

        assertEquals("https://example.invalid/${split[0]}", phone?.downloadUrl)
        assertEquals("https://example.invalid/${split[2]}", emulator?.downloadUrl)
    }

    @Test
    fun `a device the release does not name takes the universal build`() {
        val chosen = readRelease(release(assets = split), abis = listOf("riscv64"))

        assertEquals("https://example.invalid/${split[1]}", chosen?.downloadUrl)
    }

    /** A release that carries one APK named for nothing but its version serves every phone. */
    @Test
    fun `a single unsplit APK updates any phone`() {
        val chosen = readRelease(release(), abis = listOf("arm64-v8a"))

        assertEquals("https://example.invalid/pocket-drive-1.0.1.apk", chosen?.downloadUrl)
    }

    /** An APK for another architecture is not a fallback: Android would refuse to install it. */
    @Test
    fun `a release with nothing this device can run is not an update`() {
        val chosen = readRelease(
            release(assets = listOf("pocket-drive-1.0.1-x86_64.apk")),
            abis = listOf("arm64-v8a"),
        )

        assertNull(chosen)
    }

    /**
     * A download has to outlive the app being restarted.
     *
     * Granting "install unknown apps" force-stops the app on some phones, so the name is what
     * survives, and the name carries the version.
     */
    @Test
    fun `a kept APK is found again by its name`() {
        val name = downloadFileName("1.0.2")

        assertEquals("pocket-drive-1.0.2.apk", name)
        assertEquals("1.0.2", downloadedVersionName(name))
    }

    @Test
    fun `a file that is not a kept APK is ignored rather than guessed at`() {
        assertNull(downloadedVersionName("pocket-drive-1.0.2.apk.part"))
        assertNull(downloadedVersionName("pocket-drive-latest.apk"))
        assertNull(downloadedVersionName("holiday-photo.jpg"))
        assertNull(downloadedVersionName("1.0.2.apk"))
    }

    /** Installing what this build has already caught up with would walk someone backwards. */
    @Test
    fun `only a kept APK newer than this build is worth offering`() {
        assertEquals(AppVersion(1, 0, 2), downloadedUpdateVersion("pocket-drive-1.0.2.apk", "1.0.1"))
        assertNull(downloadedUpdateVersion("pocket-drive-1.0.2.apk", "1.0.2"))
        assertNull(downloadedUpdateVersion("pocket-drive-1.0.2.apk", "1.0.3"))
        // The build that installed it now calls itself the same version, suffix and all.
        assertNull(downloadedUpdateVersion("pocket-drive-1.0.2.apk", "1.0.2-debug"))
    }

    @Test
    fun `versions compare as numbers, not text`() {
        assertTrue(AppVersion.parse("1.0.10")!! > AppVersion.parse("1.0.9")!!)
        assertEquals(AppVersion(0, 2, 0), AppVersion.parse("v0.2.0-debug"))
        assertNull(AppVersion.parse("next"))
    }

    @Test
    fun `a resumed download carries on from what is already on disk`() {
        assertEquals(50_000_000L, resumeOffset(206, 50_000_000))
    }

    /** A server that ignores the range header answers 200 with the whole file. */
    @Test
    fun `a server that ignores the range starts the file over`() {
        assertEquals(0L, resumeOffset(200, 50_000_000))
        assertEquals(0L, resumeOffset(206, 0))
    }

    @Test
    fun `the size of the whole file counts what has already arrived`() {
        assertEquals(75_000_000L, totalBytes(contentLength = 25_000_000, resumeFrom = 50_000_000, offeredSize = 75_000_000))
        assertEquals(75_000_000L, totalBytes(contentLength = 75_000_000, resumeFrom = 0, offeredSize = 75_000_000))
        assertEquals(75_000_000L, totalBytes(contentLength = -1, resumeFrom = 0, offeredSize = 75_000_000))
    }

    @Test
    fun `a download shorter than the release said is not finished`() {
        assertTrue(downloadIsShort(bytesOnDisk = 50_000_000, expectedBytes = 75_000_000))
    }

    @Test
    fun `a download of the size the release said is finished`() {
        assertFalse(downloadIsShort(bytesOnDisk = 75_000_000, expectedBytes = 75_000_000))
        assertFalse(downloadIsShort(bytesOnDisk = 80_000_000, expectedBytes = 75_000_000))
    }

    @Test
    fun `a release with no size to check against is left alone`() {
        assertFalse(downloadIsShort(bytesOnDisk = 1, expectedBytes = 0))
        assertFalse(downloadIsShort(bytesOnDisk = 0, expectedBytes = 0))
    }

    @Test
    fun `a size is reported in the unit someone decides with`() {
        assertEquals("23.4 MB", readRelease(release(size = 24_500_000))?.sizeLabel)
        assertEquals("512 KB", readRelease(release(size = 524_288))?.sizeLabel)
        assertEquals("size unknown", readRelease(release(size = 0))?.sizeLabel)
    }
}
