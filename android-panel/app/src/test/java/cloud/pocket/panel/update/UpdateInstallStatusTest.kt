package cloud.pocket.panel.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app says when an install does not happen.
 *
 * An installer that refuses silently looks exactly like a button that did nothing, so every
 * outcome has to come back as a sentence that says what to do next.
 */
class UpdateInstallStatusTest {

    @Test
    fun `a signing key that does not match says so, and says what it means`() {
        val message = installFailureMessage(
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
        )

        assertEquals("That build is signed with a different key, so it cannot replace this install.", message)
    }

    @Test
    fun `a full disk names the space, not the error code`() {
        assertEquals(
            "There is not enough free space to install the update.",
            installFailureMessage(PackageInstaller.STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE"),
        )
    }

    /** Pressing cancel is not a fault, and must not read like one. */
    @Test
    fun `a cancelled install is reported plainly`() {
        assertEquals("Install cancelled.", installFailureMessage(PackageInstaller.STATUS_FAILURE_ABORTED, null))
    }

    /** A status this build does not recognise still has to say something. */
    @Test
    fun `an unrecognised failure carries whatever the system said`() {
        val message = installFailureMessage(PackageInstaller.STATUS_FAILURE, "Something specific")

        assertTrue(message.contains("Something specific"))
    }

    @Test
    fun `an unrecognised failure with nothing to add still says it failed`() {
        assertEquals("The install failed.", installFailureMessage(PackageInstaller.STATUS_FAILURE, null))
        assertEquals("The install failed.", installFailureMessage(PackageInstaller.STATUS_FAILURE, "  "))
    }
}
