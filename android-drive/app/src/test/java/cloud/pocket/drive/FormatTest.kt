package cloud.pocket.drive

import cloud.pocket.drive.data.GB
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.count
import cloud.pocket.drive.data.duration
import cloud.pocket.drive.data.gb
import cloud.pocket.drive.data.pct
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun bytesMatchTheWebPanel() {
        assertEquals("512 B", bytes(512.0))
        assertEquals("1.00 KB", bytes(1024.0))
        assertEquals("4.49 MB", bytes(4.49 * 1024 * 1024))
        assertEquals("10.4 GB", bytes(10.36 * GB))
        assertEquals("229 GB", bytes(228.7 * GB))
    }

    @Test
    fun allocationsReadInGbFromOneGbUp() {
        assertEquals("3 GB", gb(3 * GB))
        assertEquals("2.51 GB", gb(2.51 * GB))
        assertEquals("5.16 MB", gb(5.16 * 1024 * 1024))
    }

    @Test
    fun countsDurationsAndPercentages() {
        assertEquals("18,384", count(18384))
        assertEquals("6 d 20 h", duration(6 * 86400.0 + 20 * 3600 + 120))
        assertEquals("1 h 5 min", duration(3900.0))
        assertEquals(84.0, pct(2.52, 3.0), 0.01)
        assertEquals(100.0, pct(1.0, 0.0), 0.0)
    }
}
