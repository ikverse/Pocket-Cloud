package cloud.pocket.drive.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.pocket.drive.R

// docs/brand.md section 4: three variable fonts, bundled in res/font (licence in res/raw/ofl.txt).

/** Bricolage Grotesque 700, with the optical size set to the text size like the mockups' automatic opsz. */
private fun bricolage(size: Float) = FontFamily(
    Font(R.font.bricolage_grotesque, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.Setting("opsz", size.coerceIn(12f, 96f)))))

private fun instrument(weight: Int) = Font(R.font.instrument_sans, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Instrument = FontFamily(instrument(400), instrument(500), instrument(600))

val JetBrains = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private fun display(size: Float, line: Float, spacing: Float = 0f) =
    TextStyle(fontFamily = bricolage(size), fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private fun body(weight: Int, size: Float, line: Float) =
    TextStyle(fontFamily = Instrument, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp)

val PocketTypography = Typography(
    // Not used; Bricolage so nothing falls back to Roboto.
    displayLarge = display(57f, 64f), displayMedium = display(45f, 52f), displaySmall = display(36f, 44f),
    headlineLarge = display(32f, 36f),
    headlineMedium = display(28f, 31f, -0.56f),   // screen title, top of Home
    headlineSmall = display(26f, 29f, -0.52f),    // screen title, other screens
    titleLarge = display(19f, 23f, -0.19f),       // section title in a card
    titleMedium = display(16f, 19f),              // small section title, sheet title
    titleSmall = body(600, 15f, 20f),             // row title
    bodyLarge = body(400, 15f, 22f),
    bodyMedium = body(400, 13f, 19f),
    bodySmall = body(400, 12f, 16f),
    labelLarge = body(600, 15f, 20f),             // buttons
    labelMedium = body(600, 13f, 18f),            // small buttons, chips
    labelSmall = body(600, 11.5f, 14f),           // badges, bottom-bar labels
)

/** Keys, sizes, code, times in lists. */
val PocketMono = TextStyle(fontFamily = JetBrains, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp)

/** The label on a file-type tile: 500 at 10.5 sp (the caller upper-cases). */
val TileLabel = TextStyle(fontFamily = JetBrains, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 12.sp)

/** Tabular figures, for numbers that line up. */
const val TNUM = "tnum"

val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)
