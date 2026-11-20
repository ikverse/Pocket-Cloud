package cloud.pocket.panel.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.pocket.panel.data.ApiException
import cloud.pocket.panel.data.PocketApi
import cloud.pocket.panel.ui.theme.CardShape
import cloud.pocket.panel.ui.theme.PocketMono
import cloud.pocket.panel.ui.theme.PocketTheme
import cloud.pocket.panel.ui.theme.TileShape
import cloud.pocket.panel.ui.theme.bricolage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What every screen needs: the API, a way to report outcomes, and navigation. */
class Panel(
    val api: PocketApi,
    val snackbar: SnackbarHostState,
    val scope: CoroutineScope,
    val open: (String) -> Unit,
    val back: () -> Unit,
    val signedOut: () -> Unit,
    /** How many pages deep the current tab is; a sub-page (more than 1) gets a back arrow. */
    val depth: () -> Int = { 1 },
    /** The name of the bottom-bar tab the current page belongs to, shown next to the back arrow. */
    val tabLabel: () -> String = { "" },
) {
    /** Run an action; show its outcome (or error) in the snackbar. Returns true on success. */
    fun act(done: String?, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                done?.let { snackbar.showSnackbar(it) }
                onDone()
            } catch (e: ApiException) {
                if (e.status == 401) signedOut() else snackbar.showSnackbar(e.message ?: "Something went wrong.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {           // files on the phone itself: can't open, can't write…
                snackbar.showSnackbar(e.message ?: "Something went wrong.")
            }
        }
    }
}

val LocalPanel = compositionLocalOf<Panel> { error("no panel") }

/** Data loaded from the API, reloadable, with loading and error states. */
class Load<T>(initial: T?) {
    var data by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var version by mutableIntStateOf(0)
    fun reload() { version++ }
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, loader: suspend PocketApi.() -> T): Load<T> {
    val panel = LocalPanel.current
    val state = remember(*keys) { Load<T>(null) }
    LaunchedEffect(state, state.version) {
        state.loading = true
        try {
            state.data = panel.api.loader()
            state.error = null
        } catch (e: ApiException) {
            if (e.status == 401) panel.signedOut() else state.error = e.message
        } finally {
            state.loading = false
        }
    }
    return state
}

/**
 * A screen in the v2 layout: a big title at the top of the page (with its [actions] beside it and a [lede]
 * below), a back arrow and the tab's name above it on a sub-page, pull to refresh, snackbar, optional FAB.
 */
@Composable
fun <T> PocketScreen(
    title: String,
    load: Load<T>,
    showBack: Boolean = false,
    lede: String? = null,
    big: Boolean = false,
    titleLeading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: LazyListScope.(T) -> Unit,
) {
    val panel = LocalPanel.current
    val back = showBack || panel.depth() > 1
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (back) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = panel.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(panel.tabLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        snackbarHost = { PocketSnackbarHost(panel.snackbar) },
        floatingActionButton = fab,
    ) { inner ->
        PullToRefreshBox(isRefreshing = load.loading && load.data != null, onRefresh = { load.reload() },
            modifier = Modifier.padding(inner).fillMaxSize()) {
            val data = load.data
            when {
                data != null -> LazyColumn(contentPadding = PaddingValues(16.dp, if (back) 4.dp else 22.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "pocket-title") { ScreenTitle(title, big, lede, titleLeading, actions) }
                    content(data)
                }
                load.error != null -> ErrorBox(load.error!!) { load.reload() }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String, big: Boolean, lede: String?, leading: (@Composable () -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.invoke()
            Text(title, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        if (lede != null) Text(lede, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** The snackbar as the mockups' toast: ink on bg, 12 dp corners. */
@Composable
fun PocketSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(state) { Snackbar(it, shape = RoundedCornerShape(12.dp)) }
}

@Composable
fun ErrorBox(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        TextButton(onClick = retry) { Text("Try again") }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp, start = 4.dp))
}

/** A flat card: surface colour, 1 dp line border, 18 dp corners, no shadow (brand.md section 1). */
@Composable
fun PocketCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
               border: Boolean = true, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = CardShape, color = color,
        border = if (border) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null, content = content)
}

private const val ROW = "pocket-row"

/**
 * A titled card holding list rows. Rows ([PocketListItem]) that follow one another get a 1 dp divider between them,
 * as in the mockups' lists; other content (notes, buttons) sits between them without one.
 */
@Composable
fun SectionCard(title: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    PocketCard {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (title != null || action != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
                action?.invoke()
            }
            DividedColumn(content)
        }
    }
}

/** A column that draws a divider between two rows next to each other (children tagged by PocketListItem). */
@Composable
private fun DividedColumn(content: @Composable () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val lines = remember { mutableListOf<Float>() }
    Layout(content, Modifier.fillMaxWidth().drawBehind {
        val inset = 16.dp.toPx()
        lines.forEach { y -> drawLine(line, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx()) }
    }) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val rows = measurables.map { it.layoutId == ROW }
        val height = placeables.sumOf { it.height }
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, if (constraints.maxHeight == Constraints.Infinity) Int.MAX_VALUE else constraints.maxHeight)) {
            lines.clear()
            var y = 0
            placeables.forEachIndexed { i, p ->
                if (i > 0 && rows[i] && rows[i - 1]) lines.add(y.toFloat())
                p.place(0, y)
                y += p.height
            }
        }
    }
}

/**
 * A list row in the v2 style: a 600-weight title, secondary text in the soft colour, transparent so the card shows
 * through. Takes the same slots as Material's ListItem; [colors] is accepted and ignored.
 */
@Composable
fun PocketListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") colors: ListItemColors? = null,
) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { ProvideTextStyle(MaterialTheme.typography.titleSmall) { headlineContent() } },
        modifier = modifier.layoutId(ROW),
        overlineContent = overlineContent?.let { o -> { ProvideTextStyle(PocketMono.copy(fontSize = 11.5.sp, color = PocketTheme.extra.faint)) { o() } } },
        supportingContent = supportingContent?.let { s -> { ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = cs.onSurfaceVariant)) { s() } } },
        leadingContent = leadingContent,
        trailingContent = trailingContent?.let { t -> { ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = cs.onSurfaceVariant)) { t() } } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    PocketCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** Two stat cards side by side. */
@Composable
fun StatRow(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        a(Modifier.weight(1f))
        b(Modifier.weight(1f))
    }
}

/** Colour for a use level: bad at 100%, warn from the warning level, the brand colour below. */
@Composable
fun levelColor(pct: Double, warn: Double = 80.0): Color = when {
    pct >= 100 -> PocketTheme.extra.bad
    pct >= warn -> PocketTheme.extra.warn
    else -> PocketTheme.extra.brand
}

/** The mockups' meter: 8 dp pill, sunk track with a line border, filled in the level colour. */
@Composable
fun UsageBar(pct: Double, warn: Double = 80.0, modifier: Modifier = Modifier) {
    val fill = levelColor(pct, warn)
    val f = (pct / 100).toFloat().coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        .semantics { contentDescription = "${(f * 100).toInt()}% used" }) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).height(8.dp).clip(CircleShape).background(fill))
    }
}

enum class Tone { Plain, Accent, Warn, Bad, Ok }

/** A small pill: "Filling up", "In use", "waiting". */
@Composable
fun PillBadge(text: String, tone: Tone = Tone.Plain) {
    val cs = MaterialTheme.colorScheme
    val x = PocketTheme.extra
    val (bg, fg, edge) = when (tone) {
        Tone.Plain -> Triple(cs.surfaceContainer, cs.onSurfaceVariant, cs.outlineVariant)
        Tone.Accent -> Triple(cs.primaryContainer, cs.onPrimaryContainer, Color.Transparent)
        Tone.Warn -> Triple(x.warnWash, x.warn, Color.Transparent)
        Tone.Bad -> Triple(x.badWash, x.bad, Color.Transparent)
        Tone.Ok -> Triple(x.okWash, x.ok, Color.Transparent)
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(bg).border(1.dp, edge, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A 9 dp status dot. */
@Composable
fun StatusDot(tone: Tone) {
    val x = PocketTheme.extra
    val c = when (tone) { Tone.Ok -> x.ok; Tone.Warn -> x.warn; Tone.Bad -> x.bad; Tone.Accent -> x.brand; Tone.Plain -> x.faint }
    Box(Modifier.size(9.dp).clip(CircleShape).background(c))
}

// The mockups' app colours: each app keeps the same one, picked from its id.
private val MARKS = listOf(0xFF0F8F7A, 0xFF3350E8, 0xFF8A4FD6, 0xFFD1344B, 0xFFC97A00, 0xFF4A5578).map { Color(it) }

/** An app's mark: its initials on a 40 dp tile in a colour of its own. */
@Composable
fun AppMark(id: String, name: String, size: Int = 40) {
    val initials = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }
    val color = MARKS[Math.floorMod(id.hashCode(), MARKS.size)]
    Box(Modifier.size(size.dp).clip(TileShape).background(color), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontFamily = bricolage(15f), fontSize = 15.sp))
    }
}

/** A file-type tile: a folder on the accent wash for folders ([name] null), the extension in mono for files. */
@Composable
fun FileTile(name: String?) {
    val cs = MaterialTheme.colorScheme
    if (name == null) {
        Box(Modifier.size(40.dp).clip(TileShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(NavIcons.Files, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
    } else {
        val ext = name.substringAfterLast('.', "").take(4).uppercase().ifBlank { "FILE" }
        Box(Modifier.size(40.dp).clip(TileShape).background(cs.surfaceContainer).border(1.dp, cs.outlineVariant, TileShape),
            contentAlignment = Alignment.Center) {
            Text(ext, style = PocketMono.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium), color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A label and its value, one under the other at phone width. */
@Composable
fun KeyValue(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (mono) PocketMono else MaterialTheme.typography.bodyLarge)
    }
}

/** Code, keys and logs: mono on the sunk colour, 12 dp corners, scrolls sideways. */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
        Text(text, style = PocketMono, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

/** A warning note (warn wash) or an error note (bad wash), 16 dp corners. */
@Composable
fun Note(text: String, bad: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val x = PocketTheme.extra
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (bad) x.badWash else x.warnWash)
        .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** The mockups' segmented control: a sunk well, the chosen item raised on the surface colour. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer)
        .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight(if (on) 600 else 500)),
                color = if (on) cs.onSurface else cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (on) cs.surfaceContainerLow else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp, horizontal = 6.dp))
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String = "", confirm: String = "Save", password: Boolean = false,
                    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text))
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A small "Save" button row at the end of a form. */
@Composable
fun SaveRow(saving: Boolean = false, label: String = "Save", onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
        Button(onClick = onSave, enabled = !saving) { Text(label) }
    }
}

fun Modifier.gap() = this.width(8.dp)

/** Launch a coroutine from a click. */
@Composable
fun rememberLauncher(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block -> scope.launch { block() } }
}
