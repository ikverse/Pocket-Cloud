package cloud.pocket.drive

import cloud.pocket.drive.ui.screens.QrPayload
import cloud.pocket.drive.ui.screens.QrSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    @Test
    fun theControlPanelsMachineSetupTextIsUnderstood() {
        val text = "[pocket]\nendpoint = http://pocket:9000\nenroll_secret = abc123\n"
        assertEquals(QrSetup("http://pocket:9000", "abc123"), QrPayload.parse(text))
    }

    @Test
    fun jsonIsUnderstood() {
        assertEquals(QrSetup("http://pocket:9000", "s"), QrPayload.parse("""{"endpoint":"http://pocket:9000","secret":"s"}"""))
        assertEquals(QrSetup("http://pocket:9000", "s"), QrPayload.parse("""{"endpoint":"http://pocket:9000","enroll_secret":"s"}"""))
    }

    @Test
    fun aPocketAddressIsUnderstood() {
        assertEquals(QrSetup("http://pocket:9000", "a b"), QrPayload.parse("pocket://enroll?endpoint=http%3A%2F%2Fpocket%3A9000&secret=a+b"))
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(QrPayload.parse("https://example.com"))
        assertNull(QrPayload.parse(""))
        assertNull(QrPayload.parse("[pocket]\nendpoint = http://pocket:9000\n"))
        assertNull(QrPayload.parse("{not json"))
    }
}
