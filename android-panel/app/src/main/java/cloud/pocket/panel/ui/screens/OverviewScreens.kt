package cloud.pocket.panel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.GB
import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.data.clock
import cloud.pocket.panel.data.count
import cloud.pocket.panel.data.dateTime
import cloud.pocket.panel.data.duration
import cloud.pocket.panel.data.gb
import cloud.pocket.panel.data.pct
import cloud.pocket.panel.ui.components.LineChart
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.Part
import cloud.pocket.panel.ui.components.PocketScreen
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.Series
import cloud.pocket.panel.ui.components.StatCard
import cloud.pocket.panel.ui.components.StatRow
import cloud.pocket.panel.ui.components.StorageSplitBar
import cloud.pocket.panel.ui.components.UsageBar
import cloud.pocket.panel.ui.components.rememberLoad
import cloud.pocket.panel.ui.components.seriesColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AlertRow(a: J) {
    val (icon, tint) = when (a.s("level")) {
        "bad" -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        "warn" -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
        else -> Icons.Outlined.Info to MaterialTheme.colorScheme.primary
    }
    ListItem(
        headlineContent = { Text(a.s("text")) },
        supportingContent = { Text("since " + dateTime(a.d("raised"))) },
        leadingContent = { Icon(icon, null, tint = tint) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f)),
    )
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

private val clear = @Composable { ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) }

@Composable
fun OverviewScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad { get("/api/overview") }
    LaunchedEffect(load) { while (true) { delay(30_000); load.reload() } }
    val colors = seriesColors()
    PocketScreen("Overview", load) { o ->
        val p = o.obj("pool")
        val t = o.obj("traffic")
        val phone = o.obj("phone")
        item {
            StatRow({ StatCard("Pocket data", bytes(o.d("apps_used")), "${count(o.l("files"))} files in ${o.i("app_count")} apps", it) },
                { StatCard("Storage pool", "${pct(p.d("used"), p.d("total")).roundToInt()}% used", "${bytes(p.d("used"))} of ${gb(p.d("total"))}", it) })
        }
        item {
            StatRow({ StatCard("Promised to apps", gb(p.d("promised")), "${gb(p.d("unpromised"))} unpromised", it) },
                { StatCard("Requests, 24 h", count(t.l("requests")), "${count(t.l("errors"))} errors", it) })
        }
        item {
            StatRow({ StatCard("Traffic, 24 h", bytes(t.d("bytes_in") + t.d("bytes_out")), "${bytes(t.d("bytes_in"))} in · ${bytes(t.d("bytes_out"))} out", it) },
                { StatCard("Apps · devices", "${o.i("app_count")} · ${o.i("device_count")}",
                    if (o.i("apps_over_warning") > 0) "${o.i("apps_over_warning")} above warning level" else "all below warning level", it) })
        }
        item {
            StatRow({ StatCard("Battery", if (phone.has("battery")) "${phone.i("battery")}%" else "—",
                    if (phone.has("battery")) "${phone.s("plugged")} · ${"%.1f".format(Locale.US, phone.d("temp_c"))} °C" else "no phone readings", it) },
                { StatCard("Uptime", duration(o.d("now") - o.d("started_at")), "since " + dateTime(o.d("started_at")), it) })
        }
        item {
            SectionCard("Requests per hour, by app") {
                val names = o.arr("allocation").associate { it.s("id") to it.s("name") }
                val hourly = o.obj("hourly")
                val apps = hourly.keys()
                if (apps.isEmpty()) EmptyRow("No requests in the last 24 hours.")
                else {
                    val hours = o.nums("hours")
                    LineChart(apps.mapIndexed { i, a -> Series(names[a] ?: a, hourly.nums(a), colors[i % colors.size]) },
                        xLabel = { i -> hours.getOrNull(i)?.let { clock(it) } ?: "" })
                }
            }
        }
        item {
            SectionCard("Services") {
                o.arr("services").forEach { s ->
                    val warn = s.b("warn")
                    ListItem(
                        headlineContent = { Text(s.s("name")) },
                        supportingContent = { Text(s.s("detail")) },
                        leadingContent = {
                            when {
                                !s.b("ok") -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                                warn -> Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary)
                                else -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        trailingContent = { Text(s.s("state")) },
                        modifier = if (warn) Modifier.clickable { panel.open("alerts") } else Modifier,
                        colors = clear(),
                    )
                }
            }
        }
        item {
            SectionCard("Allocation use") {
                val apps = o.arr("allocation").sortedByDescending { pct(it.d("used"), it.d("alloc")) }
                if (apps.isEmpty()) EmptyRow("No apps yet. They appear when they first connect.")
                apps.forEach { a ->
                    val pc = pct(a.d("used"), a.d("alloc"))
                    ListItem(
                        headlineContent = { Text(a.s("name")) },
                        supportingContent = {
                            Column {
                                Text("${bytes(a.d("used"))} of ${gb(a.d("alloc"))}")
                                UsageBar(pc, a.d("warn_pct"), Modifier.padding(top = 6.dp))
                            }
                        },
                        trailingContent = { Text("${pc.roundToInt()}%") },
                        modifier = Modifier.clickable { panel.open("apps/" + a.s("id")) },
                        colors = clear(),
                    )
                }
            }
        }
        item {
            SectionCard("Active alerts") {
                val alerts = o.arr("alerts")
                if (alerts.isEmpty()) EmptyRow("No active alerts.")
                alerts.forEach { AlertRow(it) }
            }
        }
    }
}

@Composable
fun StorageScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad { get("/api/storage").also { it.o.put("cards", get("/api/cards").o) } }
    val cs = MaterialTheme.colorScheme
    // follow a running SD card job
    val running = load.data?.obj("cards")?.obj("job")?.b("running") == true
    LaunchedEffect(running, load.version) { if (running) { delay(1500); load.reload() } }
    PocketScreen("Storage", load) { d ->
        val p = d.obj("pool")
        val i = d.obj("internal")
        val s = d.obj("sd")
        if (d.obj("cards").s("mode") == "cards") item { SdCardsCard(d.obj("cards")) { load.reload() } }
        item {
            SectionCard("Storage pool") {
                (listOf(Triple("Internal storage", p.d("int_used"), p.d("int_limit"))) +
                    (if (s.b("present")) listOf(Triple("SD card", p.d("sd_used"), p.d("sd_limit"))) else emptyList()) +
                    listOf(Triple("Total", p.d("used"), p.d("total")))).forEach { (name, used, limit) ->
                    val pc = pct(used, limit)
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Column { Text("${bytes(used)} of ${gb(limit)} · ${bytes((limit - used).coerceAtLeast(0.0))} left"); UsageBar(pc, 80.0, Modifier.padding(top = 6.dp)) } },
                        trailingContent = { Text("${pc.roundToInt()}%") },
                        colors = clear(),
                    )
                }
                Text("${gb(p.d("promised"))} promised to apps · ${gb(p.d("unpromised"))} unpromised · ${gb(p.d("reserve"))} of internal storage always left free for Android." +
                    if (p.b("internal_full")) " Internal pool is full: new files go to the SD card." else "",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp))
                Text("Pool settings", color = cs.primary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { panel.open("settings") }.padding(16.dp, 8.dp))
            }
        }
        item {
            SectionCard("Internal storage · ${bytes(i.d("total"))}") {
                val parts = listOf(Part("Android and other apps", i.d("other"), cs.outline), Part("Pocket data (recent files)", i.d("data"), cs.primary),
                    Part("Pocket logs, statistics and undo", i.d("system"), cs.tertiary))
                StorageSplitBar(parts, i.d("total"), i.d("other") + p.d("int_limit"), Modifier.padding(horizontal = 16.dp))
                PartList(parts + Part("Free", i.d("free"), cs.surfaceVariant), "Pool limit (line)", gb(p.d("int_limit")))
                Text("Early move starts when the internal pool is ${d.i("early_move_pct")}% full.", style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp))
            }
        }
        if (!s.b("present")) item {
            SectionCard("SD card") { EmptyRow("No SD card in use. See SD cards above.") }
        } else item {
            SectionCard("SD card · ${bytes(s.d("total"))}") {
                val parts = listOf(Part("Other files on the card", s.d("other"), cs.outline), Part("Pocket data (moved files)", s.d("data"), cs.primary),
                    Part("Nightly copy of recent files", s.d("copy"), cs.secondary), Part("Undo", s.d("undo"), cs.tertiary))
                StorageSplitBar(parts, s.d("total"), s.d("other") + p.d("sd_limit"), Modifier.padding(horizontal = 16.dp))
                PartList(parts + Part("Free", s.d("free"), cs.surfaceVariant), "Pool limit (line)", gb(p.d("sd_limit")))
                val perDay = d.d("growth_per_day")
                Text(if (perDay > 0) "At the recent growth (${bytes(perDay)} a day), Pocket's SD card pool fills in about " +
                    ((p.d("sd_limit") - p.d("sd_used")) / perDay).let { if (it > 730) "${(it / 365).roundToInt()} years" else "${it.roundToInt()} days" } + "."
                else "Not enough history yet to estimate when the SD card pool fills.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp))
            }
        }
        item {
            SectionCard("Pocket data over time · last 30 days") {
                val g = d.arr("growth")
                val days = g.map { it.s("day") }
                val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
                // A line needs two points, so the first day gets a sentence instead of an empty chart.
                if (g.size < 2) Text(g.firstOrNull()?.let { "One day of history so far: ${bytes(it.d("sd"))} on the SD card, " +
                        "${bytes(it.d("int"))} on internal storage. The chart starts after the second day." } ?: "No history yet.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp))
                else LineChart(listOf(Series("SD card", g.map { it.d("sd") / GB }, cs.primary), Series("Internal", g.map { it.d("int") / GB }, cs.tertiary)),
                    xLabel = { idx -> days.getOrNull(idx)?.let { fmt.format(LocalDate.parse(it)) } ?: "" },
                    yLabel = { "%.2f GB".format(Locale.US, it) })
            }
        }
        item {
            SectionCard("Where each app's data is") {
                d.arr("apps").forEach { a ->
                    val total = a.d("int") + a.d("sd")
                    ListItem(
                        headlineContent = { Text(a.s("name")) },
                        supportingContent = { Text("Internal ${bytes(a.d("int"))} · SD card ${bytes(a.d("sd"))} · ${count(a.l("files"))} files · moves after ${a.i("move_days")} days") },
                        trailingContent = { Text(bytes(total)) },
                        modifier = Modifier.clickable { panel.open("apps/" + a.s("id")) },
                        colors = clear(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PartList(parts: List<Part>, limitLabel: String, limitValue: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        parts.forEach { p -> LegendLine(p.label, bytes(p.bytes), p.color) }
        LegendLine(limitLabel, limitValue, MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LegendLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 8.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun MoreScreen(dests: List<cloud.pocket.panel.ui.Dest>) {
    val panel = LocalPanel.current
    val load = rememberLoad { Unit }
    PocketScreen("More", load) {
        item {
            SectionCard {
                dests.forEach { d ->
                    ListItem(headlineContent = { Text(d.label) }, leadingContent = { Icon(d.icon, null) },
                        modifier = Modifier.clickable { panel.open(d.route) }, colors = clear())
                }
            }
        }
    }
}
