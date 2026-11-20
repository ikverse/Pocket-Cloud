package cloud.pocket.panel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cloud.pocket.panel.R

// docs/brand.md section 4. The three families are the variable files in res/font (SIL OFL, res/raw/ofl.txt).

/** Bricolage Grotesque 700 with its optical size set to the text size, as browsers do by themselves. */
@OptIn(ExperimentalTextApi::class)
fun bricolage(size: Float) = FontFamily(
    Font(R.font.bricolage_grotesque, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.Setting("opsz", size.coerceIn(12f, 96f)))),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(weights.map { w ->
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

val Instrument = variable(R.font.instrument_sans, 400, 500, 600)
val JetBrains = variable(R.font.jetbrains_mono, 400, 500)

private fun display(size: Float, line: Float, spacingEm: Float = 0f) = TextStyle(
    fontFamily = bricolage(size), fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = spacingEm.em,
)

private fun body(weight: Int, size: Float, line: Float) = TextStyle(
    fontFamily = Instrument, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp,
)

val PocketTypography = Typography(
    displayLarge = display(57f, 64f), displayMedium = display(45f, 52f), displaySmall = display(36f, 44f),
    headlineLarge = display(32f, 36f, -0.02f),
    headlineMedium = display(28f, 31f, -0.02f),     // screen title, top of Home
    headlineSmall = display(26f, 29f, -0.02f),      // screen title, other screens
    titleLarge = display(19f, 23f, -0.01f),         // section title in a card
    titleMedium = display(16f, 19f),                // small section title, sheet title
    titleSmall = body(600, 15f, 20f),               // row title
    bodyLarge = body(400, 15f, 22f),
    bodyMedium = body(400, 13f, 19f),
    bodySmall = body(400, 12f, 16f),
    labelLarge = body(600, 15f, 20f),
    labelMedium = body(600, 13f, 18f),
    labelSmall = body(600, 11.5f, 14f),
)

/** Keys, sizes, code and times in lists. Tabular figures so numbers line up. */
val PocketMono = TextStyle(fontFamily = JetBrains, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp,
    fontFeatureSettings = "tnum")

/** The same text with numbers that line up (sizes, percentages, counts). */
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
