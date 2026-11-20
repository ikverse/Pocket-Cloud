package cloud.pocket.panel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.pocket.panel.R
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.ui.theme.PocketMono
import cloud.pocket.panel.ui.theme.PocketTheme
import cloud.pocket.panel.ui.theme.Tile

/**
 * The signature of the Home screen (mockup/panel-v2.html): the phone with its internal storage filling up in the
 * brand colour, and its SD card beside it in the warn colour. Drawn in the mockup's 250 × 300 units.
 * The fill rises once when the screen opens; with animations turned off in Android it is simply there.
 */
@Composable
fun PhoneDrawing(intUsed: Double, intTotal: Double, sdPresent: Boolean, sdUsed: Double, sdTotal: Double, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val x = PocketTheme.extra
    val measurer = rememberTextMeasurer()
    val label = PocketMono.copy(fontSize = 11.sp, color = cs.onSurfaceVariant)
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(900, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))) }
    val intF = if (intTotal > 0) (intUsed / intTotal).toFloat().coerceIn(0f, 1f) else 0f
    val sdF = if (sdPresent && sdTotal > 0) (sdUsed / sdTotal).toFloat().coerceIn(0f, 1f) else 0f
    val said = "The phone: internal storage ${(intF * 100).toInt()}% full" + if (sdPresent) ", SD card ${(sdF * 100).toInt()}% full" else ", no SD card"
    Canvas(modifier.width(210.dp).size(210.dp, 252.dp).semantics { contentDescription = said }) {
        val k = size.width / 250f
        fun o(px: Float, py: Float) = Offset(px * k, py * k)
        fun s(w: Float, h: Float) = Size(w * k, h * k)
        fun r(v: Float) = CornerRadius(v * k)
        val stroke = Stroke(3f * k)

        // the phone
        drawRoundRect(cs.surfaceContainerLow, o(30f, 14f), s(128f, 272f), r(22f))
        drawRoundRect(cs.onSurface, o(30f, 14f), s(128f, 272f), r(22f), style = stroke)
        val H = 196f
        drawRoundRect(cs.surfaceContainer, o(42f, 50f), s(104f, H), r(8f))
        val ih = H * intF * rise.value
        clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(42f * k, 50f * k, 146f * k, (50f + H) * k, r(8f))) }) {
            drawRect(x.brand, o(42f, 50f + H - ih), s(104f, ih))
        }
        drawCircle(cs.onSurface, 3.5f * k, o(94f, 32f))
        drawRoundRect(x.line, o(76f, 262f), s(36f, 5f), r(2.5f))
        centred(measurer.measure("${bytes(intUsed)} / ${bytes(intTotal)}", label), 94f * k, (50f + H - H * intF * rise.value - 8f) * k - 13f * k)

        // the SD card
        val card = Path().apply {
            moveTo(176f * k, 196f * k); lineTo(220f * k, 196f * k); lineTo(230f * k, 208f * k); lineTo(230f * k, 278f * k)
            quadraticTo(230f * k, 284f * k, 224f * k, 284f * k); lineTo(176f * k, 284f * k)
            quadraticTo(170f * k, 284f * k, 170f * k, 278f * k); lineTo(170f * k, 202f * k)
            quadraticTo(170f * k, 196f * k, 176f * k, 196f * k); close()
        }
        drawPath(card, cs.surfaceContainerLow)
        drawPath(card, cs.onSurface, style = stroke)
        drawRoundRect(cs.surfaceContainer, o(178f, 208f), s(44f, 74f), r(3f))
        val sh = 74f * sdF * rise.value
        drawRoundRect(x.warn, o(178f, 208f + 74f - sh), s(44f, sh), r(3f))
        centred(measurer.measure(if (sdPresent) "SD ${bytes(sdUsed)}" else "No SD card", label), 200f * k, 188f * k - 13f * k)
    }
}

private fun DrawScope.centred(t: androidx.compose.ui.text.TextLayoutResult, cx: Float, top: Float) =
    drawText(t, topLeft = Offset(cx - t.size.width / 2f, top))

/** The launcher monogram on its rounded tile (radius 22% of the side), for the sign-in screen and About. */
@Composable
fun MonogramTile(side: Int = 72) {
    // The foreground is drawn in a 108-unit square whose centre 66 is the visible icon; show that centre.
    Box(Modifier.size(side.dp).clip(RoundedCornerShape((side * 0.22f).dp)).background(Tile),
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Pocket Cloud",
            modifier = Modifier.requiredSize((side * 108f / 66f).dp))
    }
}
