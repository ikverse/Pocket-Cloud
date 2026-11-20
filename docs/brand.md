# Pocket Cloud v2 — brand tokens

The branding section of `docs/plan-v2.md` as exact values. Session E (web panel), F (Pocket Drive) and G
(Android panel) take every colour, size and radius from here. **Nobody edits this file in step 1**; if a value
looks wrong, stop and tell the user.

The two apps share everything except the brand colour:

| App | Monogram | Brand (icon, charts) | Light accent (buttons, links, text) | Dark accent |
|---|---|---|---|---|
| Pocket Cloud — web panel + Android panel | **PC**, C in colour | Cobalt `#3350E8` | `#3350E8` | `#8198FF` |
| Pocket Drive | **PD**, D in colour | Teal `#12A37F` | `#0A7A5F` | `#4CD0A0` |

Contents: 1 colour tokens · 2 CSS · 3 Compose · 4 type · 5 radii and spacing · 6 monogram · 7 favicon

---

## 1. Colour tokens

### Shared neutrals (both apps, taken from the mockups)

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | `#E9EDF5` | `#0D1220` | Page / screen background |
| `surface` | `#FFFFFF` | `#161D31` | Cards, sheets, bars |
| `sunk` | `#F3F5FA` | `#111728` | Wells inside cards: meters' track, code blocks, segmented controls |
| `line` | `#D5DBE8` | `#29324C` | Borders, dividers, switch track off |
| `ink` | `#18203A` | `#E8ECF8` | Main text; also the selected nav item's fill and the transfer bar |
| `soft` | `#56607D` | `#A3ADC8` | Secondary text |
| `faint` | `#8A93AB` | `#6F7A98` | Hints, unselected tab labels, timestamps |
| `scrim` | `rgba(13,18,32,.45)` | `rgba(13,18,32,.45)` | Behind sheets and dialogs |

### Status (both apps)

| Token | Light | Dark | Use |
|---|---|---|---|
| `warn` | `#C97A00` | `#F1B445` | Warnings, SD-card fill in the phone drawing |
| `warn-wash` | `#FFF1D6` | `#3A2E12` | Warning notes and badges |
| `bad` | `#D1344B` | `#FF7388` | Errors, destructive actions |
| `bad-wash` | `#FFE4E8` | `#44202A` | Error badges |
| `ok` | `#17966B` | `#6FD08C` | Success |
| `ok-wash` | `#E1F5EA` | `#173A26` | Success badges |

Dark `ok` is `#6FD08C`, not the mockups' `#4CD0A0`: that value is exactly Pocket Drive's dark teal, and success
must not look like the brand. Light `ok` stays `#17966B`, which is still close to the teal, so **in Pocket Drive
success is never shown by colour alone**: it always comes with a check icon or the word ("Synced", "Done").

### Brand, per app

| Token | PC light | PC dark | PD light | PD dark | Use |
|---|---|---|---|---|---|
| `brand` | `#3350E8` | `#8198FF` | `#12A37F` | `#4CD0A0` | Icon letter, progress bars and meters, charts' first series, selected-tab icon, switch on |
| `accent` | `#3350E8` | `#8198FF` | `#0A7A5F` | `#4CD0A0` | Filled (primary) buttons, links, selected-tab label, focus ring, text in the brand colour |
| `accent-ink` | `#FFFFFF` | `#0D1220` | `#FFFFFF` | `#0D1220` | Text and icons on `accent` |
| `accent-wash` | `#E3E8FF` | `#1F2A52` | `#DDF4EC` | `#123A30` | Selected chips, "in use" badges, folder icon tiles |
| `on-accent-wash` | `#3350E8` | `#8198FF` | `#0A7A5F` | `#4CD0A0` | Text on `accent-wash` |

PD light `accent` is a deeper teal than the brand teal because white text on `#12A37F` is only 3.2:1;
`#0A7A5F` gives 5.3:1 on white and 4.5:1 on `bg`. Measured contrasts:

| Pair | Ratio |
|---|---|
| `#FFFFFF` on `#3350E8` (PC button) | 6.1 |
| `#3350E8` on `#E9EDF5` (PC link on bg) | 5.2 |
| `#0D1220` on `#8198FF` (PC dark button) | 7.0 |
| `#FFFFFF` on `#0A7A5F` (PD button) | 5.3 |
| `#0A7A5F` on `#E9EDF5` (PD link on bg) | 4.5 |
| `#0D1220` on `#4CD0A0` (PD dark button) | 9.6 |

### Icon tile (both apps, light and dark alike)

| Token | Value |
|---|---|
| `tile` | `#161D31` |
| `tile-letter` | `#D0D4E0` (the first letter, P) |
| second letter | PC `#3350E8`, PD `#12A37F` |

The icon does not change with the theme.

### Shadow

| Token | Light | Dark |
|---|---|---|
| `shadow` | `0 1px 2px rgba(24,32,58,.06), 0 8px 24px -12px rgba(24,32,58,.18)` | `0 1px 2px rgba(0,0,0,.3), 0 10px 28px -14px rgba(0,0,0,.6)` |

On Android, cards are flat: a 1 dp `line` border and no elevation shadow (the mockups' shadow is too soft to
matter at phone size). The FAB keeps a shadow (elevation 6 dp).

---

## 2. CSS

Session E puts this at the top of `server/panel_web/panel.css`. The structure is the mockups': system theme by
default, `data-theme="light"` / `"dark"` on `<html>` to force one.

```css
:root {
  --bg: #e9edf5; --surface: #ffffff; --sunk: #f3f5fa; --line: #d5dbe8;
  --ink: #18203a; --soft: #56607d; --faint: #8a93ab; --scrim: rgba(13,18,32,.45);
  --warn: #c97a00; --warn-wash: #fff1d6; --bad: #d1344b; --bad-wash: #ffe4e8; --ok: #17966b; --ok-wash: #e1f5ea;
  --brand: #3350e8; --accent: #3350e8; --accent-ink: #ffffff; --accent-wash: #e3e8ff; --on-accent-wash: #3350e8;
  --tile: #161d31; --tile-letter: #d0d4e0; --tile-brand: #3350e8;
  --shadow: 0 1px 2px rgba(24,32,58,.06), 0 8px 24px -12px rgba(24,32,58,.18);
  --display: "Bricolage Grotesque", "Segoe UI", system-ui, sans-serif;
  --body: "Instrument Sans", "Segoe UI", system-ui, sans-serif;
  --mono: "JetBrains Mono", Consolas, ui-monospace, monospace;
  --r-xs: 6px; --r-sm: 8px; --r-md: 10px; --r-lg: 12px; --r-card: 16px; --r-sheet: 26px; --r-pill: 999px;
  color-scheme: light;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg: #0d1220; --surface: #161d31; --sunk: #111728; --line: #29324c;
  --ink: #e8ecf8; --soft: #a3adc8; --faint: #6f7a98;
  --warn: #f1b445; --warn-wash: #3a2e12; --bad: #ff7388; --bad-wash: #44202a; --ok: #6fd08c; --ok-wash: #173a26;
  --brand: #8198ff; --accent: #8198ff; --accent-ink: #0d1220; --accent-wash: #1f2a52; --on-accent-wash: #8198ff;
  --shadow: 0 1px 2px rgba(0,0,0,.3), 0 10px 28px -14px rgba(0,0,0,.6);
  color-scheme: dark;
} }
:root[data-theme="dark"] {
  --bg: #0d1220; --surface: #161d31; --sunk: #111728; --line: #29324c;
  --ink: #e8ecf8; --soft: #a3adc8; --faint: #6f7a98;
  --warn: #f1b445; --warn-wash: #3a2e12; --bad: #ff7388; --bad-wash: #44202a; --ok: #6fd08c; --ok-wash: #173a26;
  --brand: #8198ff; --accent: #8198ff; --accent-ink: #0d1220; --accent-wash: #1f2a52; --on-accent-wash: #8198ff;
  --shadow: 0 1px 2px rgba(0,0,0,.3), 0 10px 28px -14px rgba(0,0,0,.6);
  color-scheme: dark;
}
```

For a Pocket Drive page (the mockup, or any future web view), only the brand lines differ:

```css
/* light */  --brand: #12a37f; --accent: #0a7a5f; --accent-ink: #ffffff; --accent-wash: #ddf4ec; --on-accent-wash: #0a7a5f; --tile-brand: #12a37f;
/* dark  */  --brand: #4cd0a0; --accent: #4cd0a0; --accent-ink: #0d1220; --accent-wash: #123a30; --on-accent-wash: #4cd0a0;
```

uPlot charts read `--brand` for the first series, then `--warn`, `--soft`, `--bad` in that order.

The fonts load from Google Fonts as in the mockups (the panel is only reached on the tailnet, with a fallback
stack if offline); session E may instead self-host the three files under `server/panel_web/vendor/fonts/`.

---

## 3. Compose

**Dynamic colour is off** in both apps (`dynamicColor = false` by default, no setting), so the brand colour
shows on Android 12 and later too. `tools/gen_scheme.py` in `android-panel` is no longer used for the scheme;
the roles below are mapped by hand from the tokens.

Material roles are mapped **by use**, not by tone order: `surfaceContainerLow` is the card colour and
`surfaceContainer` is the sunk colour in both themes, even though in dark the sunk colour is darker.

| Role | Token | | Role | Token |
|---|---|---|---|---|
| `background`, `surface` | `bg` | | `onBackground`, `onSurface` | `ink` |
| `surfaceContainerLow` (cards) | `surface` | | `onSurfaceVariant` | `soft` |
| `surfaceContainerLowest` | light `#FFFFFF`, dark `#0A0F1B` | | `outline` | `faint` |
| `surfaceContainer`, `surfaceVariant` (sunk) | `sunk` | | `outlineVariant` | `line` |
| `surfaceContainerHigh` (sheets, bottom bar) | `surface` | | `inverseSurface` | `ink` |
| `surfaceContainerHighest` (switch track off) | `line` | | `inverseOnSurface` | `bg` |
| `primary` | `accent` | | `onPrimary` | `accent-ink` |
| `primaryContainer` | `accent-wash` | | `onPrimaryContainer` | `on-accent-wash` |
| `secondary` | `ink` (selected nav pill) | | `onSecondary` | `bg` |
| `secondaryContainer` | `accent-wash` (nav indicator) | | `onSecondaryContainer` | `on-accent-wash` |
| `tertiary` | `warn` | | `onTertiary` | light `#FFFFFF`, dark `#0D1220` |
| `tertiaryContainer` | `warn-wash` | | `onTertiaryContainer` | `ink` |
| `error` | `bad` | | `onError` | light `#FFFFFF`, dark `#0D1220` |
| `errorContainer` | `bad-wash` | | `onErrorContainer` | `bad` |
| `surfaceTint` | `bg` (no tint on elevation) | | `scrim` | `#000000` (alpha set by components) |
| `surfaceBright` | light `#FFFFFF`, dark `#29324C` | | `surfaceDim` | light `#DCE1EC`, dark `#0D1220` |
| `inversePrimary` | the other theme's `accent` | | | |

### `Color.kt` (both apps; brand block differs)

```kotlin
package cloud.pocket.panel.ui.theme      // cloud.pocket.drive.ui.theme in Pocket Drive

import androidx.compose.ui.graphics.Color

// neutrals and status, the same in both apps
val BgLight = Color(0xFFE9EDF5);      val BgDark = Color(0xFF0D1220)
val SurfaceLight = Color(0xFFFFFFFF); val SurfaceDark = Color(0xFF161D31)
val SunkLight = Color(0xFFF3F5FA);    val SunkDark = Color(0xFF111728)
val LineLight = Color(0xFFD5DBE8);    val LineDark = Color(0xFF29324C)
val InkLight = Color(0xFF18203A);     val InkDark = Color(0xFFE8ECF8)
val SoftLight = Color(0xFF56607D);    val SoftDark = Color(0xFFA3ADC8)
val FaintLight = Color(0xFF8A93AB);   val FaintDark = Color(0xFF6F7A98)
val WarnLight = Color(0xFFC97A00);    val WarnDark = Color(0xFFF1B445)
val WarnWashLight = Color(0xFFFFF1D6); val WarnWashDark = Color(0xFF3A2E12)
val BadLight = Color(0xFFD1344B);     val BadDark = Color(0xFFFF7388)
val BadWashLight = Color(0xFFFFE4E8); val BadWashDark = Color(0xFF44202A)
val OkLight = Color(0xFF17966B);      val OkDark = Color(0xFF6FD08C)
val OkWashLight = Color(0xFFE1F5EA);  val OkWashDark = Color(0xFF173A26)
val Tile = Color(0xFF161D31);         val TileLetter = Color(0xFFD0D4E0)

// Pocket Cloud
val BrandLight = Color(0xFF3350E8);   val BrandDark = Color(0xFF8198FF)
val AccentLight = Color(0xFF3350E8);  val AccentDark = Color(0xFF8198FF)
val AccentWashLight = Color(0xFFE3E8FF); val AccentWashDark = Color(0xFF1F2A52)

// Pocket Drive (instead of the block above)
// val BrandLight = Color(0xFF12A37F);   val BrandDark = Color(0xFF4CD0A0)
// val AccentLight = Color(0xFF0A7A5F);  val AccentDark = Color(0xFF4CD0A0)
// val AccentWashLight = Color(0xFFDDF4EC); val AccentWashDark = Color(0xFF123A30)
```

### `Theme.kt`

```kotlin
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
fun PocketCloudTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {  // PocketDriveTheme in Drive
    CompositionLocalProvider(LocalPocketExtra provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors,
                      typography = PocketTypography, shapes = PocketShapes, content = content)
    }
}
```

Both apps also follow an in-app theme choice (System / Light / Dark) where one exists today. Status bar and
navigation bar are edge-to-edge, with dark icons in light theme and light icons in dark theme.

---

## 4. Type

### Families

| Role | Family | Weights used | Files (Android `res/font/`) |
|---|---|---|---|
| Display (titles, monogram) | Bricolage Grotesque | 700 | `bricolage_grotesque.ttf` (the variable font) |
| Body (everything else) | Instrument Sans | 400, 500, 600 | `instrument_sans.ttf` (variable) |
| Data (sizes, keys, code, times in lists) | JetBrains Mono | 400, 500 | `jetbrains_mono.ttf` (variable) |

All three are SIL Open Font License; each app keeps `OFL.txt` next to them in `res/raw/` and lists them on its
About/licences screen. On Android use the variable files with `FontVariation.Settings` (`wght`, and `opsz` =
the text size in sp for Bricolage, like the mockups' automatic optical size); the apps' minimum SDK is above 26,
where variable fonts work. On the web, Google Fonts:
`family=Bricolage+Grotesque:opsz,wght@12..96,700&family=Instrument+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500`.

Numbers that line up (sizes, percentages, counts) use tabular figures: CSS `font-variant-numeric:
tabular-nums`, Compose `fontFeatureSettings = "tnum"`.

### Scale

Web panel (desktop width; under 860 px the phone column applies):

| Name | Font | Size / line height | Letter spacing | Phone width |
|---|---|---|---|---|
| `h1` page title | Bricolage 700 | 34 / 1.1 | −0.02em | 28 |
| `h2` card title | Bricolage 700 | 19 / 1.2 | −0.01em | 19 |
| brand name in the rail | Bricolage 700 | 20 | −0.02em | — |
| `h3` row title | Instrument 600 | 15 / 1.3 | 0 | 15 |
| body | Instrument 400 | 15 / 1.5 | 0 | 15 |
| small, secondary | Instrument 400 | 13 / 1.45 | 0 | 13 |
| button | Instrument 500 | 15 (small button 13) | 0 | same |
| badge, tab label | Instrument 600 | 11.5 | 0 | 11.5 |
| mono | JetBrains 400 | 12.5 / 1.6 | 0 | 12.5 |

Android (both apps, sp). Drive's mockup sizes are the phone sizes; the Android panel uses the same:

| `Typography` slot | Font | Size / line | Spacing | Use |
|---|---|---|---|---|
| `headlineMedium` | Bricolage 700 | 28 / 31 | −0.56 | Screen title (big, top of Home) |
| `headlineSmall` | Bricolage 700 | 26 / 29 | −0.52 | Screen title (other screens) |
| `titleLarge` | Bricolage 700 | 19 / 23 | −0.19 | Section title in a card |
| `titleMedium` | Bricolage 700 | 16 / 19 | 0 | Small section title, sheet title |
| `titleSmall` | Instrument 600 | 15 / 20 | 0 | Row title |
| `bodyLarge` | Instrument 400 | 15 / 22 | 0 | Body |
| `bodyMedium` | Instrument 400 | 13 / 19 | 0 | Secondary text |
| `bodySmall` | Instrument 400 | 12 / 16 | 0 | Footnotes |
| `labelLarge` | Instrument 600 | 15 / 20 | 0 | Buttons |
| `labelMedium` | Instrument 600 | 13 / 18 | 0 | Small buttons, chips |
| `labelSmall` | Instrument 600 | 11.5 / 14 | 0 | Badges, bottom-bar labels |
| `PocketMono` (extra style) | JetBrains 400 | 12.5 / 18 | 0 | Keys, sizes, code, file-type labels (500 at 10.5 sp, upper case, on file icons) |

`displayLarge/Medium/Small` and `headlineLarge` are not used; set them to Bricolage 700 so nothing falls back to
Roboto.

---

## 5. Radii and spacing

### Corner radii

| Token | Web panel | Android (dp) | Used on |
|---|---|---|---|
| `r-xs` | 6 px | 6 | Focus ring corners, tiny tags |
| `r-sm` | 8 px | 9 | Small buttons, segmented-control items |
| `r-md` | 10 px | 12 | Buttons, nav items, segmented control, sheet action rows (10) |
| `r-lg` | 12 px | 12 | Chips, code blocks, toasts, notes (web) |
| `r-tile` | 11 px at 38 px | 12 at 40 dp | App marks and file-type tiles |
| `r-search` | — | 14 | Search field |
| `r-card` | 16 px | 18 | Cards (Android: also notes and file chips at 16) |
| `r-xfer` | — | 16 | Transfer bar |
| `r-fab` | — | 18 | FAB (56 dp) |
| `r-sheet` | — | 26 (top corners) | Bottom sheets |
| `r-pill` | 999 px | 50% | Badges, meters, switches |

Compose `Shapes`: `extraSmall` 6, `small` 9, `medium` 12, `large` 18, `extraLarge` 26.

### Spacing

Base unit 2; the values in use are 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 26, 34.

| What | Web panel | Android (dp) |
|---|---|---|
| Page padding | 34 top, `clamp(16px, 4vw, 48px)` sides, 80 bottom; phone width 22 / 16 / 96 | 22 top, 16 sides, 20 bottom |
| Gap between cards | 16 | 12 |
| Card padding | 18 vertical, 20 horizontal | 14 vertical, 16 horizontal |
| List row | 12 vertical, 1 px `line` divider between rows | 11 vertical, 1 dp divider |
| Gap inside a row | 12 | 12 |
| Gap between a title and its text | 6–12 | 6–10 |
| Side rail | 228 wide, 22 / 14 padding | — |
| Bottom bar | 5 tabs, 10 / 9 padding per tab, 20 px icons | 5 tabs, 8 / 4 per tab, 22 dp icons |
| Main column max width | 1180 | — |
| Minimum touch target | 40 px | 48 dp |

Controls: switch 40 × 23 px (web), 42 × 24 dp (Android, or Material's own Switch recoloured); meters 8 tall
(4 in the transfer bar); status dot 9.

---

## 6. Monogram (launcher icon)

The same placement as RaceWall's `ic_launcher_foreground.xml` (`RaceWall-Android/app/src/main/res/drawable`),
with the letters redrawn from Bricolage Grotesque 700.

### Geometry (in the 108 × 108 adaptive-icon viewport)

| | Value |
|---|---|
| Viewport | `108 × 108`, drawn at 108 dp |
| Safe zone | the centre 66 × 66 (x and y 21–87); everything stays inside it |
| Letter height (cap height of the ink) | 23.76: y from 42.12 to 65.88, centred on 54 |
| Letter pair width | 56: x from 26 to 82, centred on 54. If Bricolage's P + C or P + D at that height come out narrower than 56, keep the height and centre the pair; never stretch |
| Gap between the two letters | 0.7, measured between the ink of the two letters (RaceWall's gap) |
| First letter (P) | `#D0D4E0` |
| Second letter | PC: C in `#3350E8`; PD: D in `#12A37F` |
| Background | `#161D31`, full bleed (`ic_launcher_background` colour resource) |

The letters are **outlined paths**, converted from the font at weight 700 and optical size 96, rounded to two
decimals like RaceWall's file, with a comment at the top saying where they came from. Never live text.

### Files per app

```
res/drawable/ic_launcher_foreground.xml   the two letter paths, coloured
res/drawable/ic_launcher_monochrome.xml   the same two paths, both #FFFFFFFF (Android tints them for themed icons)
res/values/colors.xml                     <color name="ic_launcher_background">#161D31</color>
res/mipmap-anydpi/ic_launcher.xml         adaptive-icon: background, foreground, monochrome
res/mipmap-anydpi/ic_launcher_round.xml   the same
```

Pocket Drive's old folder icon and the Android panel's `pocket_logo.xml` / `pocket_blue` background are
replaced. In-app, where a logo shows (setup screen, About), use the same foreground on a rounded `tile`
square (radius 22% of the side).

---

## 7. Favicon (web panel)

`server/panel_web/favicon.svg`, linked as `<link rel="icon" href="favicon.svg" type="image/svg+xml">`. It is the
launcher icon cropped tighter so the letters stay readable at 16 px:

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="22 22 64 64">
  <rect x="22" y="22" width="64" height="64" rx="14" fill="#161D31"/>
  <path fill="#D0D4E0" d="…P, the same path data as ic_launcher_foreground.xml…"/>
  <path fill="#3350E8" d="…C, the same path data…"/>
</svg>
```

- The tile does not change with the theme (a dark tile reads on both light and dark browser tabs).
- The rail's brand mark in the panel uses the same SVG at 28 px, followed by "Pocket Cloud" in Bricolage 700 20 px.
- A 180 × 180 PNG (`apple-touch-icon.png`) rendered from the same SVG goes next to it for phones that save the
  panel to the home screen.
