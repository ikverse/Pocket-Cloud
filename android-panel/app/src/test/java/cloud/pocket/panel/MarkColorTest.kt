package cloud.pocket.panel

import cloud.pocket.panel.ui.components.markColorIndex
import cloud.pocket.panel.ui.components.markInitials
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An app's mark must look the same here and on the web panel. The expected values were computed by running
 * server/panel_web/panel.js's own markColor and initials (v2/web-panel, lines 45-47) in Node.
 */
class MarkColorTest {
    @Test fun colourIndexMatchesTheWebPanel() {
        val js = mapOf(
            "egx-analyzer" to 5, "signal-lab" to 0, "signal-lab-egx" to 1, "racewall" to 3, "village" to 4,
            "modelgen" to 7, "deskglow" to 6, "spen-lab" to 6, "a" to 1, "" to 0,
        )
        js.forEach { (id, index) -> assertEquals(id, index, markColorIndex(id)) }
    }

    @Test fun initialsMatchTheWebPanel() {
        val js = mapOf("EGX Analyzer" to "EA", "signal-lab" to "SL", "Signal Lab EGX" to "SL", "racewall" to "R",
            "my_app name" to "MA", "" to "?", "  " to "?")
        js.forEach { (name, initials) -> assertEquals(name, initials, markInitials(name)) }
    }
}
