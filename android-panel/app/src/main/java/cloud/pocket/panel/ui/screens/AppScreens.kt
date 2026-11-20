package cloud.pocket.panel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import cloud.pocket.panel.ui.components.PocketListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import cloud.pocket.panel.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cloud.pocket.panel.data.appRules
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.GB
import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.ago
import cloud.pocket.panel.data.body
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.data.count
import cloud.pocket.panel.data.dateTime
import cloud.pocket.panel.data.gb
import cloud.pocket.panel.data.pct
import cloud.pocket.panel.data.weekday
import cloud.pocket.panel.ui.components.AllocationSlider
import cloud.pocket.panel.ui.components.ConfirmDialog
import cloud.pocket.panel.ui.components.LineChart
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.PocketScreen
import cloud.pocket.panel.ui.components.SaveRow
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.Series
import cloud.pocket.panel.ui.components.SliderSetting
import cloud.pocket.panel.ui.components.StatCard
import cloud.pocket.panel.ui.components.StatRow
import cloud.pocket.panel.ui.components.TextInputDialog
import cloud.pocket.panel.ui.components.UsageBar
import cloud.pocket.panel.ui.components.AppMark
import cloud.pocket.panel.ui.components.Button
import cloud.pocket.panel.ui.components.Note
import cloud.pocket.panel.ui.components.PillBadge
import cloud.pocket.panel.ui.components.PocketCard
import cloud.pocket.panel.ui.components.Segmented
import cloud.pocket.panel.ui.components.StatusDot
import cloud.pocket.panel.ui.components.Tone
import cloud.pocket.panel.ui.theme.PocketMono
import cloud.pocket.panel.ui.theme.PocketTheme
import cloud.pocket.panel.ui.theme.tabular
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import cloud.pocket.panel.ui.components.rememberLoad
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun clearItem() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f))

@Composable
fun AppsScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad { get("/api/apps") }
    var sheetFor by remember { mutableStateOf<String?>(null) }
    var newApp by remember { mutableStateOf(false) }
    PocketScreen("Apps", load,
        lede = "Each of your projects gets its own space on the phone. A project shows up here the first time it connects.",
        actions = { Button(onClick = { newApp = true }, small = true) { Text("New app") } }) { d ->
        val pool = d.obj("pool")
        val defaults = d.obj("defaults")
        item {
            SectionCard("Storage pool") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("${gb(pool.d("promised"))} promised to apps out of the ${gb(pool.d("total"))} pool · ${gb(pool.d("unpromised"))} unpromised",
                        style = MaterialTheme.typography.bodyMedium)
                    UsageBar(pct(pool.d("promised"), pool.d("total")), 101.0, Modifier.padding(vertical = 8.dp))
                    Text("New apps get ${defaults.d("alloc_gb").let { if (it % 1 == 0.0) it.roundToInt().toString() else it.toString() }} GB, " +
                        "a warning at ${defaults.i("warn_pct")}% and ${if (defaults.s("at_limit") == "refuse") "refused uploads" else "a warning only"} at 100%.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedButton(onClick = { panel.open("devices") }, modifier = Modifier.fillMaxWidth()) { Text("All devices") }
        }
        val apps = d.arr("apps")
        if (apps.isEmpty()) item { Text("No apps yet. An app appears here the first time it connects, or create one with New app.",
            modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        apps.forEach { a ->
            item(key = a.s("id")) {
                val pc = pct(a.d("used"), a.d("alloc"))
                val devices = a.arr("devices")
                PocketCard(Modifier.clickable { panel.open("apps/" + a.s("id")) }) {
                    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppMark(a.s("id"), a.s("name"))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(a.s("name"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                    if (a.s("status") != "active") PillBadge("Waiting", Tone.Warn)
                                }
                                Text(a.s("id"), style = PocketMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { sheetFor = a.s("id") }) { Icon(Icons.Outlined.Tune, "Change allocation") }
                        }
                        Text(devices.joinToString(", ") { it.s("name") }.ifBlank { "No devices yet" } +
                            if (devices.isEmpty()) "" else " · ${devices.size} device${if (devices.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            UsageBar(pc, a.d("warn_pct"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${bytes(a.d("used"))} of ${gb(a.d("alloc"))}", style = MaterialTheme.typography.bodyMedium.tabular(),
                                    modifier = Modifier.weight(1f))
                                when {
                                    pc >= 100 -> PillBadge("Full", Tone.Bad)
                                    pc >= a.d("warn_pct") -> PillBadge("Filling up", Tone.Warn)
                                    else -> Text("${count(a.l("requests"))} requests today · ${ago(a.d("last_used"))}",
                                        style = MaterialTheme.typography.bodySmall, color = PocketTheme.extra.faint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    sheetFor?.let { id -> AllocationSheet(id, onDone = { sheetFor = null; load.reload() }) }
    if (newApp) TextInputDialog("New app", "App name (lowercase letters, digits, dashes)", confirm = "Create",
        onDismiss = { newApp = false }) { name ->
        panel.act("App created.", onDone = { panel.open("apps/" + name.trim()) }) {
            panel.api.post("/api/apps", body("id" to name.trim(), "name" to name.trim()))
        }
    }
}

/** The allocation slider in a bottom sheet, opened from the Apps list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllocationSheet(id: String, onDone: () -> Unit) {
    val panel = LocalPanel.current
    val load = rememberLoad(id) { get("/api/apps/$id") }
    ModalBottomSheet(onDismissRequest = onDone) {
        val a = load.data
        if (a == null) {
            Text("Loading…", modifier = Modifier.padding(24.dp))
        } else {
            var alloc by remember(a) { mutableFloatStateOf((a.d("alloc") / GB).toFloat()) }
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Allocation · ${a.s("name")}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                AllocationSlider(a.d("used"), a.d("alloc_max"), alloc, a.d("warn_pct").toFloat()) { alloc = it }
                Text("${bytes(a.d("used"))} used · ${(pct(a.d("used"), alloc * GB)).roundToInt()}% · warning at ${gb(alloc * GB * a.d("warn_pct") / 100)}. " +
                    "Up to ${gb(a.d("alloc_max"))}: this allocation plus the pool's unpromised ${gb(a.d("unpromised"))}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
                SaveRow {
                    panel.act("Allocation saved.", onDone = onDone) {
                        panel.api.patch("/api/apps/$id", body("alloc" to (alloc * GB).roundToLong()))
                    }
                }
            }
        }
    }
}

@Composable
fun AppDetailScreen(id: String) {
    val panel = LocalPanel.current
    val load = rememberLoad(id) { get("/api/apps/$id") }
    // The rules come separately: if the server lacks the features or the request fails, their cards are simply absent.
    val rules = rememberLoad(id) { appRules(id) }
    LaunchedEffect(load.version) { if (load.version > 0) rules.reload() }     // pull to refresh and saves reload them too
    var confirm by remember { mutableStateOf<Ask?>(null) }
    PocketScreen(load.data?.s("name") ?: "App", load, showBack = true,
        titleLeading = load.data?.let { a -> { AppMark(a.s("id"), a.s("name")) } }) { a ->
        val used = a.d("used")
        val pc = pct(used, a.d("alloc"))
        item {
            val creator = a.arr("devices").firstOrNull { it.s("uid") == a.s("created_by") }?.s("name") ?: a.s("created_by")
            Text("${a.s("id")} · created ${dateTime(a.d("created_at"))} " +
                (if (a.s("created_by") == "control panel") "in the control panel" else "when it first connected from $creator") +
                (if (a.s("notes").isNotBlank()) " · " + a.s("notes") else ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (a.s("status") != "active") item {
            Note("This app is waiting for approval.") {
                Button(onClick = { panel.act("App allowed.", onDone = { load.reload() }) {
                    panel.api.patch("/api/apps/$id", body("status" to "active")) } }, small = true) { Text("Allow it") }
            }
        }
        if (pc >= a.d("warn_pct")) item {
            Note("At ${pc.roundToInt()}% of its ${gb(a.d("alloc"))} allocation. " +
                if (a.s("at_limit") == "refuse") "Uploads are refused at ${gb(a.d("alloc"))}." else "Uploads continue past it (warn only).",
                bad = pc >= 100)
        }
        item {
            StatRow({ StatCard("Used", bytes(used), "of ${gb(a.d("alloc"))} · ${count(a.l("files"))} files", it) },
                { StatCard("Shared space", bytes(a.d("shared")), a.arr("devices").size.let { n -> when (n) { 0 -> "no devices yet"; 1 -> "used by its one device"; 2 -> "used by both devices"; else -> "used by all $n devices" } }, it) })
        }
        item {
            StatRow({ StatCard("Requests, 24 h", count(a.l("requests")), "${count(a.l("errors"))} errors", it) },
                { StatCard("Traffic, 24 h", bytes(a.d("bytes_in") + a.d("bytes_out")), "${bytes(a.d("bytes_in"))} in · ${bytes(a.d("bytes_out"))} out", it) })
        }
        item {
            SectionCard("Devices") {
                val devs = a.arr("devices")
                if (devs.isEmpty()) Text("No devices yet.", modifier = Modifier.padding(16.dp))
                devs.forEach { dv -> DeviceRow(id, dv, onChanged = { load.reload() }) { confirm = it } }
            }
        }
        item {
            SectionCard("Requests per day, by kind · last 7 days") {
                val s = a.obj("series")
                val days = a.strs("days")
                val cs = MaterialTheme.colorScheme
                LineChart(listOf(Series("Reads", s.nums("reads"), cs.primary), Series("Writes", s.nums("writes"), cs.tertiary),
                    Series("Listings", s.nums("listings"), cs.secondary), Series("Deletes", s.nums("deletes"), cs.error)),
                    xLabel = { i -> days.getOrNull(i)?.let { weekday(java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()) } ?: "" })
            }
        }
        item { AppSettings(a) { load.reload() } }
        item {
            SectionCard("Spaces") {
                PocketListItem(headlineContent = { Text("shared") }, supportingContent = { Text("all devices") },
                    trailingContent = { Text(bytes(a.d("shared"))) }, colors = clearItem())
                a.arr("devices").forEach { dv ->
                    PocketListItem(headlineContent = { Text("device") }, supportingContent = { Text(dv.s("name")) },
                        trailingContent = { Text(bytes(dv.d("private"))) }, colors = clearItem())
                }
            }
        }
        rules.data?.let { r -> ruleItems(id, a.b("is_family"), r) { rules.reload() } }
        item {
            SectionCard("Actions") {
                FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { panel.open("files/$id") }) { Text("Browse files") }
                    OutlinedButton(onClick = { panel.open("logs/$id") }) { Text("Request log") }
                    OutlinedButton(onClick = {
                        confirm = Ask("New keys for all devices", "Every device of this app gets a new key; the libraries pick them up by themselves.",
                            "Make new keys") { panel.act("New keys made.") { panel.api.post("/api/apps/$id/rotate-keys") } }
                    }) { Text("New keys for all devices") }
                    OutlinedButton(danger = true, onClick = {
                        confirm = Ask("Empty app", "Delete all ${count(a.l("files"))} files of ${a.s("name")}? They go into the undo first. The app and its devices stay.",
                            "Empty app", danger = true) { panel.act("Files moved to the undo.", onDone = { load.reload() }) { panel.api.post("/api/apps/$id/empty") } }
                    }) { Text("Empty app") }
                    if (!a.b("is_family")) OutlinedButton(danger = true, onClick = {
                        confirm = Ask("Delete app", "Delete ${a.s("name")}, its devices and keys? Its files go into the undo; the app itself can't be brought back. Its ${gb(a.d("alloc"))} return to the pool.",
                            "Delete app", danger = true) { panel.act("App deleted.", onDone = { panel.back() }) { panel.api.delete("/api/apps/$id") } }
                    }) { Text("Delete app") }
                }
            }
        }
    }
    confirm?.let { c -> ConfirmDialog(c.title, c.text, c.button, c.danger, onDismiss = { confirm = null }, onConfirm = c.action) }
}

@Composable
private fun DeviceRow(app: String, dv: J, onChanged: () -> Unit, ask: (Ask) -> Unit) {
    val panel = LocalPanel.current
    var menu by remember { mutableStateOf(false) }
    PocketListItem(
        headlineContent = { Text(dv.s("name") + if (dv.b("blocked")) " · blocked" else "") },
        leadingContent = { StatusDot(if (dv.b("blocked")) Tone.Bad else Tone.Ok) },
        supportingContent = { Text("${dv.s("ip")}${dv.s("ip_alt").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""} · key ${dv.s("key")} · ${bytes(dv.d("private"))} private · ${count(dv.l("requests"))} requests today · ${ago(dv.d("last_seen"))}") },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Device actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("New key") }, onClick = {
                        menu = false
                        ask(Ask("New key", "${dv.s("name")} gets a new key; the old one stops working. The Pocket library there picks it up by itself.", "Make new key") {
                            panel.act("New key made.", onDone = onChanged) { panel.api.post("/api/apps/$app/devices/${dv.s("uid")}/rotate-key") }
                        })
                    })
                    DropdownMenuItem(text = { Text("Remove", color = MaterialTheme.colorScheme.error) }, onClick = {
                        menu = false
                        ask(Ask("Remove device", "Remove ${dv.s("name")} from this app? Its private files go into the undo. If the app runs there again, it enrolls as a new device.", "Remove", danger = true) {
                            panel.act("Device removed.", onDone = onChanged) { panel.api.delete("/api/apps/$app/devices/${dv.s("uid")}") }
                        })
                    })
                }
            }
        },
        colors = clearItem(),
    )
}

@Composable
private fun AppSettings(a: J, onSaved: () -> Unit) {
    val panel = LocalPanel.current
    var name by remember(a) { mutableStateOf(a.s("name")) }
    var notes by remember(a) { mutableStateOf(a.s("notes")) }
    var alloc by remember(a) { mutableFloatStateOf((a.d("alloc") / GB).toFloat()) }
    var warn by remember(a) { mutableFloatStateOf(a.d("warn_pct").toFloat()) }
    var atLimit by remember(a) { mutableStateOf(a.s("at_limit")) }
    var cap by remember(a) { mutableFloatStateOf((a.d("device_cap") / GB).toFloat()) }
    var reserve by remember(a) { mutableFloatStateOf((a.d("device_reserve") / GB).toFloat()) }
    val deviceCount = a.arr("devices").size.coerceAtLeast(1)
    var move by remember(a) { mutableFloatStateOf(a.d("move_days").toFloat()) }
    var family by remember(a) { mutableStateOf(a.b("family")) }
    SectionCard("Settings for this app") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
        }
        AllocationSlider(a.d("used"), a.d("alloc_max"), alloc, warn) { alloc = it }
        Text("${bytes(a.d("used"))} of ${gb(alloc * GB)} used (${pct(a.d("used"), alloc * GB).roundToInt()}%), warning at ${gb(alloc * GB * warn / 100)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        SliderSetting("Warn at", warn, 50f, 95f, "%", step = 5f) { warn = it }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("At 100%", style = MaterialTheme.typography.bodyLarge)
            val limits = listOf("refuse" to "Refuse uploads", "warn" to "Warn only")
            Segmented(limits.map { it.second }, limits.indexOfFirst { it.first == atLimit }, Modifier.fillMaxWidth().padding(top = 6.dp)) {
                atLimit = limits[it].first
            }
        }
        SliderSetting("Cap per device", cap, 0f, alloc, "GB", step = 0.5f, hint = "Limit on each private space · left end = none", none = true) { cap = it }
        SliderSetting("Reserve per device", reserve, 0f, alloc / deviceCount, "GB", step = 0.25f,
            hint = "Room each device can always use, whatever the others took · left end = none", none = true) { reserve = it }
        SliderSetting("Move to SD card after", move, 1f, 60f, "days", hint = "Days without changes") { move = it }
        if (!a.b("is_family")) PocketListItem(headlineContent = { Text("Family bucket") },
            supportingContent = { Text("A third bucket, family, shared by every app that is in the family") },
            trailingContent = { Switch(family, { family = it }) }, colors = clearItem())
        SaveRow {
            // Send only what changed: an app already over its allocation (warn only) must still save a new name.
            val changes = body("name" to name, "notes" to notes, "at_limit" to atLimit)
            // Compared in the slider's own units: GB as a Float doesn't turn back into the exact bytes for most allocations.
            if (alloc != (a.d("alloc") / GB).toFloat()) changes.put("alloc", (alloc * GB).roundToLong())
            if (warn.roundToInt() != a.i("warn_pct")) changes.put("warn_pct", warn.roundToInt())
            if (move.roundToInt() != a.i("move_days")) changes.put("move_days", move.roundToInt())
            val capBytes = if (cap <= 0f) 0L else (cap * GB).roundToLong()
            if (capBytes != a.l("device_cap")) changes.put("device_cap", if (capBytes == 0L) org.json.JSONObject.NULL else capBytes)
            val resBytes = if (reserve <= 0f) 0L else (reserve * GB).roundToLong()
            if (resBytes != a.l("device_reserve")) changes.put("device_reserve", if (resBytes == 0L) org.json.JSONObject.NULL else resBytes)
            if (family != a.b("family")) changes.put("family", family)
            panel.act("Settings saved.", onDone = onSaved) { panel.api.patch("/api/apps/${a.s("id")}", changes) }
        }
    }
}

/** A confirmation to ask before a consequential action. */
data class Ask(val title: String, val text: String, val button: String, val danger: Boolean = false, val action: () -> Unit)
