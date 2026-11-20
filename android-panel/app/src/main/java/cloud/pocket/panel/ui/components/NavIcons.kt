package cloud.pocket.panel.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// The bottom bar's five icons, the same drawings as mockup/panel-v2.html (22 × 22, 1.8 stroke, round ends).

private fun outline(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 22.dp, 22.dp, 22f, 22f).apply {
    paths.forEach { d ->
        addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

object NavIcons {
    val Home = outline("home", "M3 10.5 11 4l8 6.5V19H3z")
    val Apps = outline("apps", roundRect(3f, 3f, 7f, 7f, 2f), roundRect(12f, 3f, 7f, 7f, 2f),
        roundRect(3f, 12f, 7f, 7f, 2f), roundRect(12f, 12f, 7f, 7f, 2f))
    val Files = outline("files", "M3 6h6l2 2h8v10H3z")
    val Phone = outline("phone", roundRect(6f, 2f, 10f, 18f, 2.5f), "M10 17h2")
    val Settings = outline("settings", "M14 11a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        "M11 2v3M11 17v3M2 11h3M17 11h3M4.6 4.6l2.1 2.1M15.3 15.3l2.1 2.1M4.6 17.4l2.1-2.1M15.3 6.7l2.1-2.1")
}
