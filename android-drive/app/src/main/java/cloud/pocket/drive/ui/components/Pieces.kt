package cloud.pocket.drive.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.pocket.drive.R
import cloud.pocket.drive.ui.theme.PocketMono
import cloud.pocket.drive.ui.theme.PocketTheme
import cloud.pocket.drive.ui.theme.TNUM
import cloud.pocket.drive.ui.theme.Tile
import cloud.pocket.drive.ui.theme.TileLabel

// The pieces of the v2 design (mockup/drive-v2.html), with the values of docs/brand.md.

val CardShape = RoundedCornerShape(18.dp)
val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 20.dp)

/** A flat card: surface colour, 1 dp line border, no shadow. */
@Composable
fun PCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
          gap: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Rows with a 1 dp divider between them; 11 dp above and below each row, none at the ends. */
@Composable
fun <T> Divided(items: List<T>, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    Column(modifier) {
        items.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Box(Modifier.padding(top = if (i == 0) 0.dp else 11.dp, bottom = if (i == items.lastIndex) 0.dp else 11.dp)) { row(item) }
        }
    }
}

/** A card with a section title and divided rows. */
@Composable
fun <T> ListCard(title: String?, items: List<T>, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    PCard(modifier) {
        if (title != null) SectionHeader(title)
        Divided(items, row = row)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(bottom = 10.dp))
}

/** The big title at the top of a screen, with optional actions at its end. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, big: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Title and secondary line. */
@Composable
fun TwoLine(title: String, sub: String?, modifier: Modifier = Modifier, subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
            titleStyle: TextStyle = MaterialTheme.typography.titleSmall, third: String? = null) {
    Column(modifier) {
        Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = subColor,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!third.isNullOrEmpty()) Text(third, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A row: leading piece, title and text growing, trailing pieces. 48 dp tall at least. */
@Composable
fun ItemRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
            trailing: @Composable RowScope.() -> Unit = {}, body: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp)
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f)) { body() }
        trailing()
    }
}

@Composable
fun Chevron() { Text("›", style = MaterialTheme.typography.titleLarge, color = PocketTheme.extra.faint) }

// ------------------------------------------------------------------ badges, notes, meters

enum class Tone { NEUTRAL, ACCENT, OK, WARN, BAD }

/** A small pill. OK badges always carry a check mark, so success never rests on colour alone. */
@Composable
fun Badge(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val x = PocketTheme.extra
    val (bg, fg) = when (tone) {
        Tone.NEUTRAL -> c.surfaceContainer to c.onSurfaceVariant
        Tone.ACCENT -> c.primaryContainer to c.onPrimaryContainer
        Tone.OK -> x.okWash to x.ok
        Tone.WARN -> x.warnWash to x.warn
        Tone.BAD -> x.badWash to x.bad
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(bg)
            .then(if (tone == Tone.NEUTRAL) Modifier.border(1.dp, c.outlineVariant, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (tone == Tone.OK) Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = fg)
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TNUM), color = fg, maxLines = 1)
    }
}

/** A warning (or error) note with a wash background. */
@Composable
fun Note(modifier: Modifier = Modifier, bad: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val x = PocketTheme.extra
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (bad) x.badWash else x.warnWash).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

/** A meter: brand fill on the sunk track. [fraction] null shows an empty track. */
@Composable
fun Meter(fraction: Float?, modifier: Modifier = Modifier, height: Dp = 8.dp, track: Color? = null, fill: Color? = null) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        modifier.fillMaxWidth().height(height).clip(shape).background(track ?: c.surfaceContainer)
            .then(if (track == null) Modifier.border(1.dp, c.outlineVariant, shape) else Modifier),
    ) {
        val f = (fraction ?: 0f).coerceIn(0f, 1f)
        if (f > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(shape).background(fill ?: PocketTheme.extra.brand))
    }
}

// ------------------------------------------------------------------ controls

/** The segmented control: a sunk well with the chosen item raised. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp)).padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) c.surfaceContainerLow else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = 8.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp, fontFeatureSettings = TNUM),
                    color = if (on) c.onSurface else c.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** A switch in the brand colour (track off in the line colour). */
@Composable
fun PSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    Switch(checked, onChange, modifier, enabled = enabled, colors = SwitchDefaults.colors(
        checkedTrackColor = PocketTheme.extra.brand, checkedThumbColor = c.surfaceContainerLow, checkedBorderColor = Color.Transparent,
        uncheckedTrackColor = c.outlineVariant, uncheckedThumbColor = c.surfaceContainerLow, uncheckedBorderColor = Color.Transparent,
    ))
}

/** Filled button in the accent colour. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = if (small) 36.dp else 44.dp), enabled = enabled,
        shape = RoundedCornerShape(if (small) 9.dp else 12.dp),
        contentPadding = if (small) PaddingValues(horizontal = 10.dp, vertical = 5.dp) else PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** The plain button: surface, line border, ink text (bad text when [danger]). */
@Composable
fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, danger: Boolean = false, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    OutlinedButton(onClick, modifier.heightIn(min = if (small) 36.dp else 44.dp), enabled = enabled,
        shape = RoundedCornerShape(if (small) 9.dp else 12.dp), border = BorderStroke(1.dp, c.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surfaceContainerLow, contentColor = if (danger) PocketTheme.extra.bad else c.onSurface),
        contentPadding = if (small) PaddingValues(horizontal = 10.dp, vertical = 5.dp) else PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** The search field: surface, line border, radius 14. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.primary),
        modifier = modifier.fillMaxWidth().onFocusChangedCompat(onFocus),
        decorationBox = { inner ->
            Row(Modifier.clip(shape).background(c.surfaceContainerLow).border(1.dp, c.outlineVariant, shape).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = PocketTheme.extra.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = PocketTheme.extra.faint, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

private fun Modifier.onFocusChangedCompat(onFocus: (Boolean) -> Unit) = this.onFocusChanged { onFocus(it.isFocused) }

// ------------------------------------------------------------------ tiles and marks

/** A 40 dp file-type tile: the extension on its kind's colour; folders on the accent wash; vaults on ink. */
@Composable
fun FileTile(name: String, isDir: Boolean, modifier: Modifier = Modifier, vault: Boolean = false, size: Dp = 40.dp) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(size * 0.3f)
    when {
        vault -> Box(modifier.size(size).clip(shape).background(c.inverseSurface), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, Modifier.size(size / 2), tint = c.inverseOnSurface)
        }
        isDir -> Box(modifier.size(size).clip(shape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Folder, null, Modifier.size(size / 2), tint = c.onPrimaryContainer)
        }
        else -> Box(modifier.size(size).clip(shape).background(Color(FileTypes.kind(name).argb)), contentAlignment = Alignment.Center) {
            Text(FileTypes.label(name), style = TileLabel, color = Color.White, maxLines = 1)
        }
    }
}

/** A square tile with an icon on a colour (Home's camera and vault cards). */
@Composable
fun IconTile(icon: ImageVector, bg: Color, fg: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
    }
}

/** The PD monogram on its dark tile (radius 22 % of the side), as on the launcher. */
@Composable
fun Monogram(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Tile), contentAlignment = Alignment.Center) {
        // The foreground is drawn in the 108 dp adaptive viewport; the visible launcher mask is the middle 72.
        Image(painterResource(R.drawable.ic_launcher_foreground), null,
            Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
    }
}

/** A dot in a status colour. */
@Composable
fun Dot(color: Color, size: Dp = 9.dp) { Box(Modifier.size(size).clip(CircleShape).background(color)) }

/** Mono text (keys, addresses, times). */
@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = PocketMono.copy(fontFeatureSettings = TNUM), color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))

// ------------------------------------------------------------------ the sheet's action rows

/** One action in a bottom sheet: icon, label, optional trailing piece. */
@Composable
fun SheetAction(label: String, icon: ImageVector?, onClick: () -> Unit, danger: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    val color = if (danger) PocketTheme.extra.bad else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = color, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ------------------------------------------------------------------ the mockup's line icons (22 x 22, stroke 1.8)

private fun lineIcon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 22.dp, 22.dp, 22f, 22f).apply {
    paths.forEach { d ->
        addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

object LineIcons {
    val Home = lineIcon("home", "M3 10.5 11 4l8 6.5V19H3z")
    val Files = lineIcon("files", "M3 6h6l2 2h8v10H3z")
    val Sync = lineIcon("sync", "M4 9a7 7 0 0 1 12-3l2 2M18 13a7 7 0 0 1-12 3l-2-2M18 4v4h-4M4 18v-4h4")
    val Activity = lineIcon("activity", "M3 11h4l2-6 4 12 2-6h4")
    val Settings = lineIcon("settings", "M14 11a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
        "M11 2v3M11 17v3M2 11h3M17 11h3M4.6 4.6l2.1 2.1M15.3 15.3l2.1 2.1M4.6 17.4l2.1-2.1M15.3 6.7l2.1-2.1")
}
