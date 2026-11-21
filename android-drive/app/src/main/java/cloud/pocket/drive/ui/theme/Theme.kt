package cloud.pocket.drive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Roles mapped by use, not by tone order (docs/brand.md section 3). Dynamic colour is off: the brand teal shows everywhere.
private val LightColors = lightColorScheme(
    primary = AccentLight, onPrimary = Color.White, primaryContainer = AccentWashLight, onPrimaryContainer = AccentLight,
    inversePrimary = AccentDark,
    secondary = InkLight, onSecondary = BgLight, secondaryContainer = AccentWashLight, onSecondaryContainer = AccentLight,
    tertiary = WarnLight, onTertiary = Color.White, tertiaryContainer = WarnWashLight, onTertiaryContainer = InkLight,
    background = BgLight, onBackground = InkLight, surface = BgLight, onSurface = InkLight,
    surfaceVariant = SunkLight, onSurfaceVariant = SoftLight, surfaceTint = BgLight,
    inverseSurface = InkLight, inverseOnSurface = BgLight,
    error = BadLight, onError = Color.White, errorContainer = BadWashLight, onErrorContainer = BadLight,
    outline = FaintLight, outlineVariant = LineLight, scrim = Color.Black,
    surfaceBright = Color.White, surfaceDim = Color(0xFFDCE1EC),
    surfaceContainerLowest = Color.White, surfaceContainerLow = SurfaceLight, surfaceContainer = SunkLight,
    surfaceContainerHigh = SurfaceLight, surfaceContainerHighest = LineLight,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark, onPrimary = BgDark, primaryContainer = AccentWashDark, onPrimaryContainer = AccentDark,
    inversePrimary = AccentLight,
    secondary = InkDark, onSecondary = BgDark, secondaryContainer = AccentWashDark, onSecondaryContainer = AccentDark,
    tertiary = WarnDark, onTertiary = BgDark, tertiaryContainer = WarnWashDark, onTertiaryContainer = InkDark,
    background = BgDark, onBackground = InkDark, surface = BgDark, onSurface = InkDark,
    surfaceVariant = SunkDark, onSurfaceVariant = SoftDark, surfaceTint = BgDark,
    inverseSurface = InkDark, inverseOnSurface = BgDark,
    error = BadDark, onError = BgDark, errorContainer = BadWashDark, onErrorContainer = BadDark,
    outline = FaintDark, outlineVariant = LineDark, scrim = Color.Black,
    surfaceBright = LineDark, surfaceDim = BgDark,
    surfaceContainerLowest = Color(0xFF0A0F1B), surfaceContainerLow = SurfaceDark, surfaceContainer = SunkDark,
    surfaceContainerHigh = SurfaceDark, surfaceContainerHighest = LineDark,
)

/** Colours Material has no role for. Read with PocketTheme.extra. */
@Immutable
data class PocketExtra(
    val brand: Color, val faint: Color, val line: Color,
    val warn: Color, val warnWash: Color, val ok: Color, val okWash: Color, val bad: Color, val badWash: Color,
)

private val LightExtra = PocketExtra(BrandLight, FaintLight, LineLight, WarnLight, WarnWashLight, OkLight, OkWashLight, BadLight, BadWashLight)
private val DarkExtra = PocketExtra(BrandDark, FaintDark, LineDark, WarnDark, WarnWashDark, OkDark, OkWashDark, BadDark, BadWashDark)
val LocalPocketExtra = staticCompositionLocalOf { LightExtra }

object PocketTheme { val extra: PocketExtra @Composable get() = LocalPocketExtra.current }

@Composable
fun PocketDriveTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPocketExtra provides if (darkTheme) DarkExtra else LightExtra) {
        val colors = if (darkTheme) DarkColors else LightColors
        // Screens drawn on a plain background (no Surface or Scaffold) would otherwise get black text.
        MaterialTheme(colorScheme = colors, typography = PocketTypography, shapes = PocketShapes) {
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}
