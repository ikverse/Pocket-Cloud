package cloud.pocket.drive

import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {
    @Test
    fun namesAndParents() {
        assertEquals("c.txt", Keys.name("a/b/c.txt"))
        assertEquals("b", Keys.name("a/b/"))
        assertEquals("a/b/", Keys.parent("a/b/c.txt"))
        assertEquals("a/", Keys.parent("a/b/"))
        assertEquals("", Keys.parent("c.txt"))
    }

    @Test
    fun movingAFolderRewritesEveryKeyUnderIt() {
        assertEquals("New/x/y.txt", Keys.moved("Old/x/y.txt", "Old/", "New/"))
        assertEquals("Elsewhere/Old/y.txt", Keys.moved("Old/y.txt", "Old/", "Elsewhere/Old/"))
        assertEquals("b.txt", Keys.moved("a.txt", "a.txt", "b.txt"))
        assertEquals("other/z", Keys.moved("other/z", "Old/", "New/"))
    }

    @Test
    fun aFolderNamedLikeAPrefixIsNotConfusedWithIt() {
        // "Old2/f" starts with "Old" but not with "Old/"
        assertEquals("Old2/f", Keys.moved("Old2/f", "Old/", "New/"))
    }

    @Test
    fun badNamesAreRefused() {
        assertNotNull(Keys.problem(""))
        assertNotNull(Keys.problem("a/b"))
        assertNotNull(Keys.problem(".."))
        assertNotNull(Keys.problem(Keys.KEEP))
        assertNull(Keys.problem("photo 1 (copy).jpg"))
    }

    @Test
    fun hiddenFilesAreTheFolderMarkerAndRcloneTemps() {
        assertTrue(Keys.isHidden("Docs/${Keys.KEEP}"))
        assertTrue(Keys.isHidden(".rclone_temp_abc"))
        assertTrue(!Keys.isHidden("Docs/report.pdf"))
    }

    @Test
    fun copySourceIsEncodedButKeepsSlashes() {
        assertEquals("shared/My%20Docs/caf%C3%A9.txt", Keys.copySource("shared", "My Docs/café.txt"))
        assertEquals("device/a%2Bb/c", Keys.copySource("device", "a+b/c"))
    }

    @Test
    fun uniqueNamesGetANumber() {
        assertEquals("a.txt", Keys.unique("a.txt", setOf("b.txt")))
        assertEquals("a (2).txt", Keys.unique("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", Keys.unique("a.txt", setOf("a.txt", "a (2).txt")))
        assertEquals("noext (2)", Keys.unique("noext", setOf("noext")))
    }

    @Test
    fun serverAddressesAreCompleted() {
        assertEquals("http://pocket:9000", Settings.normalizeAddress("pocket"))
        assertEquals("http://10.0.2.2:9766", Settings.normalizeAddress("10.0.2.2:9766"))
        assertEquals("http://100.82.2.29:9000", Settings.normalizeAddress(" http://100.82.2.29 "))
        assertEquals("https://example.com:8443", Settings.normalizeAddress("https://example.com:8443/"))
        assertEquals("", Settings.normalizeAddress("  "))
    }
}
