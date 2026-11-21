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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.GB
import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.data.charging
import cloud.pocket.panel.data.clock
import cloud.pocket.panel.data.count
import cloud.pocket.panel.data.dateTime
import cloud.pocket.panel.data.duration
import cloud.pocket.panel.data.gb
import cloud.pocket.panel.data.pct
import cloud.pocket.panel.ui.components.AppMark
import cloud.pocket.panel.ui.components.ConfirmDialog
import cloud.pocket.panel.ui.components.KeyValue
import cloud.pocket.panel.ui.components.LineChart
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.OutlinedButton
import cloud.pocket.panel.ui.components.Part
import cloud.pocket.panel.ui.components.PhoneDrawing
import cloud.pocket.panel.ui.components.PillBadge
import cloud.pocket.panel.ui.components.PocketListItem
import cloud.pocket.panel.ui.components.PocketScreen
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.Series
import cloud.pocket.panel.ui.components.StatCard
import cloud.pocket.panel.ui.components.StatRow
import cloud.pocket.panel.ui.components.StatusDot
import cloud.pocket.panel.ui.components.StorageSplitBar
import cloud.pocket.panel.ui.components.Tone
import cloud.pocket.panel.ui.components.UsageBar
import cloud.pocket.panel.ui.components.rememberLoad
import cloud.pocket.panel.ui.components.seriesColors
import cloud.pocket.panel.ui.theme.PocketTheme
import cloud.pocket.panel.ui.theme.tabular
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AlertRow(a: J) {
    val x = PocketTheme.extra
    val (icon, tint) = when (a.s("level")) {
        "bad" -> Icons.Outlined.ErrorOutline to x.bad
        "warn" -> Icons.Outlined.WarningAmber to x.warn
        else -> Icons.Outlined.Info to x.brand
    }
    PocketListItem(
        headlineContent = { Text(a.s("text")) },
        supportingContent = { Text("since " + dateTime(a.d("raised"))) },
        leadingContent = { Icon(icon, null, tint = tint) },
    )
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

/** A service row: a status dot, its name and detail, its state. */
@Composable
private fun ServiceRow(s: J, onClick: (() -> Unit)?) {
    PocketListItem(
        headlineContent = { Text(s.s("name")) },
        supportingContent = { Text(s.s("detail")) },
        leadingContent = { StatusDot(if (!s.b("ok")) Tone.Bad else if (s.b("warn")) Tone.Warn else Tone.Ok) },
        trailingContent = { Text(s.s("state")) },
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    )
}

/** Home: the phone at a glance, what needs you, then the overview's numbers, chart, services and allocations. */
@Composable
fun HomeScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad { get("/api/overview") }
    LaunchedEffect(load) { while (true) { delay(30_000); load.reload() } }
    val colors = seriesColors()
    PocketScreen("Home", load, big = true) { o ->
        val p = o.obj("pool")
        val t = o.obj("traffic")
        val phone = o.obj("phone")
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PhoneDrawing(p.d("int_used"), p.d("int_limit"), p.d("sd_limit") > 0, p.d("sd_used"), p.d("sd_limit"))
                Text("Your pocket is holding ${bytes(o.d("apps_used"))} for ${o.i("app_count")} app${if (o.i("app_count") == 1) "" else "s"}.",
                    style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Text("${bytes((p.d("total") - p.d("used")).coerceAtLeast(0.0))} still free in the pool" +
                    (if (p.d("sd_limit") > 0) " across the phone and its SD card." else " on the phone.") +
                    " ${count(o.l("files"))} files on ${o.i("device_count")} device${if (o.i("device_count") == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                KeyValue("Running for", duration(o.d("now") - o.d("started_at")) + " · since " + dateTime(o.d("started_at")))
                if (phone.has("battery")) KeyValue("Phone", "${phone.i("battery")}% · ${charging(phone.s("plugged"))} · ${"%.1f".format(Locale.US, phone.d("temp_c"))} °C")
                if (o.s("version").isNotBlank()) KeyValue("Server version", o.s("version"), mono = true)
            }
        }
        item {
            SectionCard("Needs you", action = { TextButton(onClick = { panel.open("alerts") }) { Text("All alerts") } }) {
                val alerts = o.arr("alerts")
                val filling = o.arr("allocation").filter { it.d("alloc") > 0 && pct(it.d("used"), it.d("alloc")) >= it.d("warn_pct") }
                if (alerts.isEmpty() && filling.isEmpty()) PocketListItem(headlineContent = { Text("Nothing needs you") },
                    supportingContent = { Text("No active alerts, and every app is below its warning level.") },
                    leadingContent = { StatusDot(Tone.Ok) })
                alerts.forEach { AlertRow(it) }
                filling.forEach { a ->
                    val pc = pct(a.d("used"), a.d("alloc"))
                    PocketListItem(
                        headlineContent = { Text(a.s("name")) },
                        supportingContent = { Text("At ${pc.roundToInt()}% of its ${gb(a.d("alloc"))}") },
                        leadingContent = { AppMark(a.s("id"), a.s("name")) },
                        trailingContent = { OutlinedButton(onClick = { panel.open("apps/" + a.s("id")) }, small = true) { Text("Open") } },
                    )
                }
            }
        }
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
                    if (phone.has("battery")) "${charging(phone.s("plugged"))} · ${"%.1f".format(Locale.US, phone.d("temp_c"))} °C" else "no phone readings", it) },
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
                        xLabel = { i -> hours.getOrNull(i)?.let { clock(it) } ?: "" }, wholeNumbers = true)
                }
            }
        }
        item {
            SectionCard("Services") {
                o.arr("services").forEach { s -> ServiceRow(s, if (s.b("warn")) ({ panel.open("alerts") }) else null) }
            }
        }
        item {
            SectionCard("Allocation use") {
                val apps = o.arr("allocation").sortedByDescending { pct(it.d("used"), it.d("alloc")) }
                if (apps.isEmpty()) EmptyRow("No apps yet. They appear when they first connect.")
                apps.forEach { a ->
                    val pc = pct(a.d("used"), a.d("alloc"))
                    PocketListItem(
                        headlineContent = { Text(a.s("name")) },
                        leadingContent = { AppMark(a.s("id"), a.s("name")) },
                        supportingContent = {
                            Column {
                                Text("${bytes(a.d("used"))} of ${gb(a.d("alloc"))}", style = MaterialTheme.typography.bodyMedium.tabular())
                                UsageBar(pc, a.d("warn_pct"), Modifier.padding(top = 6.dp))
                            }
                        },
                        trailingContent = { Text("${pc.roundToInt()}%", style = MaterialTheme.typography.bodyMedium.tabular()) },
                        modifier = Modifier.clickable { panel.open("apps/" + a.s("id")) },
                    )
                }
            }
        }
    }
}

/** Phone: the phone's storage and SD cards, its services, and links to its nightly jobs and log. */
@Composable
fun PhoneScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad {
        get("/api/storage").also {
            it.o.put("cards", get("/api/cards").o)
            it.o.put("services", get("/api/overview").o.optJSONArray("services"))
        }
    }
    val cs = MaterialTheme.colorScheme
    val x = PocketTheme.extra
    var restart by remember { mutableStateOf(false) }
    // follow a running SD card job
    val running = load.data?.obj("cards")?.obj("job")?.b("running") == true
    LaunchedEffect(running, load.version) { if (running) { delay(1500); load.reload() } }
    PocketScreen("Phone", load, lede = "The phone itself: its storage, its SD cards and the housekeeping it does each night.") { d ->
        val p = d.obj("pool")
        val i = d.obj("internal")
        val s = d.obj("sd")
        item {
            SectionCard("Storage") {
                (listOf(Triple("Internal storage", p.d("int_used"), p.d("int_limit"))) +
                    (if (s.b("present")) listOf(Triple("SD card", p.d("sd_used"), p.d("sd_limit"))) else emptyList()) +
                    listOf(Triple("Total", p.d("used"), p.d("total")))).forEach { (name, used, limit) ->
                    val pc = pct(used, limit)
                    PocketListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Column { Text("${bytes(used)} of ${gb(limit)} · ${bytes((limit - used).coerceAtLeast(0.0))} left", style = MaterialTheme.typography.bodyMedium.tabular()); UsageBar(pc, 80.0, Modifier.padding(top = 6.dp)) } },
                        trailingContent = { Text("${pc.roundToInt()}%", style = MaterialTheme.typography.bodyMedium.tabular()) },
                    )
                }
                Text("${gb(p.d("promised"))} promised to apps · ${gb(p.d("unpromised"))} unpromised · ${gb(p.d("reserve"))} of internal storage always left free for Android." +
                    if (p.b("internal_full")) " Internal pool is full: new files go to the SD card." else "",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp))
                TextButton(onClick = { panel.open("settings") }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Pool settings") }
            }
        }
        if (d.obj("cards").s("mode") == "cards") item { SdCardsCard(d.obj("cards")) { load.reload() } }
        item {
            SectionCard("Services") {
                d.arr("services").forEach { sv -> ServiceRow(sv, if (sv.b("warn")) ({ panel.open("alerts") }) else null) }
                OutlinedButton(onClick = { restart = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Restart the storage server")
                }
            }
        }
        item {
            SectionCard("Housekeeping") {
                PocketListItem(headlineContent = { Text("Nightly jobs") },
                    supportingContent = { Text("The copy to the SD card, moves, undo clean-up and checks; run any of them now") },
                    modifier = Modifier.clickable { panel.open("jobs") })
                PocketListItem(headlineContent = { Text("Log") },
                    supportingContent = { Text("Every request by app, device and result, and the system log") },
                    modifier = Modifier.clickable { panel.open("logs") })
            }
        }
        item {
            SectionCard("Internal storage · ${bytes(i.d("total"))}") {
                val parts = listOf(Part("Android and other apps", i.d("other"), x.faint), Part("Pocket data (recent files)", i.d("data"), x.brand),
                    Part("Pocket logs, statistics and undo", i.d("system"), x.warn))
                StorageSplitBar(parts, i.d("total"), i.d("other") + p.d("int_limit"), Modifier.padding(horizontal = 16.dp))
                PartList(parts + Part("Free", i.d("free"), cs.surfaceContainer), "Pool limit (line)", gb(p.d("int_limit")))
                Text("Early move starts when the internal pool is ${d.i("early_move_pct")}% full.", style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp))
            }
        }
        if (!s.b("present")) item {
            SectionCard("SD card") { EmptyRow("No SD card in use. See SD cards above.") }
        } else item {
            SectionCard("SD card · ${bytes(s.d("total"))}") {
                val parts = listOf(Part("Other files on the card", s.d("other"), x.faint), Part("Pocket data (moved files)", s.d("data"), x.brand),
                    Part("Nightly copy of recent files", s.d("copy"), cs.onSurfaceVariant), Part("Undo", s.d("undo"), x.warn))
                StorageSplitBar(parts, s.d("total"), s.d("other") + p.d("sd_limit"), Modifier.padding(horizontal = 16.dp))
                PartList(parts + Part("Free", s.d("free"), cs.surfaceContainer), "Pool limit (line)", gb(p.d("sd_limit")))
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
                else {
                    // Points sit at their day, so days without a reading show as a gap in time, not as one step.
                    val first = LocalDate.parse(days.first())
                    val xs = days.map { java.time.temporal.ChronoUnit.DAYS.between(first, LocalDate.parse(it)).toDouble() }
                    // MB until there is a gigabyte to show; small data in GB reads as 0.00, 0.01, ...
                    val big = g.any { maxOf(it.d("sd"), it.d("int")) >= GB }
                    val unit = if (big) GB else 1024.0 * 1024.0
                    LineChart(listOf(Series("SD card", g.map { it.d("sd") / unit }, x.brand), Series("Internal", g.map { it.d("int") / unit }, x.warn)),
                        xLabel = { off -> fmt.format(first.plusDays(off.toLong())) }, xs = xs,
                        yLabel = { if (big) "%.1f GB".format(Locale.US, it) else if (it < 10) "%.1f MB".format(Locale.US, it) else "%.0f MB".format(Locale.US, it) })
                }
            }
        }
        item {
            SectionCard("Where each app's data is") {
                d.arr("apps").forEach { a ->
                    val total = a.d("int") + a.d("sd")
                    PocketListItem(
                        headlineContent = { Text(a.s("name")) },
                        leadingContent = { AppMark(a.s("id"), a.s("name")) },
                        supportingContent = { Text("Internal ${bytes(a.d("int"))} · SD card ${bytes(a.d("sd"))} · ${count(a.l("files"))} files · moves after ${a.i("move_days")} days") },
                        trailingContent = { Text(bytes(total), style = MaterialTheme.typography.bodyMedium.tabular()) },
                        modifier = Modifier.clickable { panel.open("apps/" + a.s("id")) },
                    )
                }
            }
        }
    }
    if (restart) ConfirmDialog("Restart the storage server", "Running requests finish first; new ones wait a moment.", "Restart",
        onDismiss = { restart = false }) {
        panel.act("Storage server restarted.") { panel.api.post("/api/server/restart") }
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
        Box(Modifier.padding(end = 8.dp).size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular())
    }
}
