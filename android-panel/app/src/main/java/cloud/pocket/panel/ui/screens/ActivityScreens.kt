package cloud.pocket.panel.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.PocketApi
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.data.clock
import cloud.pocket.panel.data.dateTime
import cloud.pocket.panel.data.duration
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.PocketScreen
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.SliderSetting
import cloud.pocket.panel.ui.components.rememberLoad
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private fun resultText(r: String) = when (r) { "ok" -> "OK"; "warn" -> "Warning"; "fail" -> "Failed"; "" -> "running"; else -> r }

@Composable
fun JobsScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad { get("/api/jobs") }
    val scope = rememberCoroutineScope()
    PocketScreen("Jobs", load) { d ->
        item {
            Text("Scheduled work the phone does by itself. Times are set in Settings.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard {
                d.arr("jobs").forEach { j ->
                    val last = j.obj("last")
                    val ran = j.has("last")
                    ListItem(
                        headlineContent = { Text(j.s("name")) },
                        supportingContent = {
                            Column {
                                Text(j.s("what"))
                                Text("${j.s("schedule")} · next ${dateTime(j.d("next"))}")
                                Text(when {
                                    j.b("running") -> "Running now…"
                                    ran -> "Last ${dateTime(last.d("started"))}: ${resultText(last.s("result"))} · ${last.s("detail")}"
                                    else -> "Not run yet"
                                }, color = when (last.s("result")) {
                                    "fail" -> MaterialTheme.colorScheme.error
                                    "warn" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                            }
                        },
                        trailingContent = {
                            TextButton(enabled = !j.b("running"), onClick = {
                                panel.act("Started.", onDone = { load.reload(); scope.launch { delay(2500); load.reload() } }) {
                                    panel.api.post("/api/jobs/${j.s("key")}/run")
                                }
                            }) { Text("Run now") }
                        },
                        colors = clearItem(),
                    )
                }
            }
        }
        item {
            SectionCard("History") {
                val h = d.arr("history")
                if (h.isEmpty()) Text("No jobs have run yet.", modifier = Modifier.padding(16.dp))
                h.forEach { j ->
                    val took = j.d("finished") - j.d("started")
                    ListItem(
                        headlineContent = { Text(j.s("job")) },
                        supportingContent = { Text("${resultText(j.s("result"))} · ${j.s("detail")}") },
                        overlineContent = { Text(dateTime(j.d("started")) + if (j.d("finished") > 0) " · took " + (if (took < 60) "${took.roundToInt()} s" else duration(took)) else "") },
                        colors = clearItem(),
                    )
                }
            }
        }
    }
}

@Composable
fun AlertsScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad { get("/api/alerts") }
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    val thresholds = remember { mutableStateMapOf<String, Float>() }
    PocketScreen("Alerts", load, fab = {
        if (enabled.isNotEmpty() || thresholds.isNotEmpty()) ExtendedFloatingActionButton(onClick = {
            panel.act("Alert rules saved.", onDone = { enabled.clear(); thresholds.clear(); load.reload() }) {
                panel.api.patch("/api/alerts", JSONObject().put("enabled", JSONObject(enabled.toMap())).put("thresholds", JSONObject(thresholds.toMap())))
            }
        }, icon = { Icon(Icons.Outlined.Save, null) }, text = { Text("Save rules") })
    }) { d ->
        item {
            Text("Alerts arrive as notifications on the phone running Pocket CloudServer and on this phone, and are listed here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard("Active") {
                val active = d.arr("active")
                if (active.isEmpty()) Text("No active alerts.", modifier = Modifier.padding(16.dp))
                active.forEach { AlertRow(it) }
            }
        }
        item {
            SectionCard("Rules") {
                d.arr("rules").forEach { r ->
                    val kind = r.s("kind")
                    val on = enabled[kind] ?: r.b("enabled")
                    ListItem(
                        headlineContent = { Text(r.s("label")) },
                        supportingContent = { if (!r.has("threshold")) Text(r.s("text")) },
                        trailingContent = { Switch(checked = on, onCheckedChange = { enabled[kind] = it }) },
                        colors = clearItem(),
                    )
                    if (r.has("threshold")) {
                        val t = r.obj("threshold")
                        val key = t.s("key")
                        val word = if ("low" in key || "free" in key) "Alert below" else "Alert above"
                        SliderSetting(word, thresholds[key] ?: t.d("value").toFloat(), t.d("min").toFloat(), t.d("max").toFloat(),
                            t.s("unit"), step = t.d("step").toFloat()) { thresholds[key] = it }
                    }
                }
            }
        }
        item {
            SectionCard("History") {
                val h = d.arr("history")
                if (h.isEmpty()) Text("No alerts yet.", modifier = Modifier.padding(16.dp))
                h.forEach { a ->
                    ListItem(
                        headlineContent = { Text(a.s("text")) },
                        overlineContent = { Text(dateTime(a.d("raised")) + when {
                            !a.has("cleared") -> " · active"
                            a.d("cleared") - a.d("raised") >= 1 -> " · cleared " + clock(a.d("cleared"))
                            else -> ""
                        }) },
                        colors = clearItem(),
                    )
                }
            }
        }
    }
}

@Composable
fun LogsScreen(startApp: String?) {
    var tab by remember { mutableStateOf(0) }
    var app by remember { mutableStateOf(startApp ?: "") }
    var device by remember { mutableStateOf("") }
    var op by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    val choices = rememberLoad {
        J(JSONObject().put("apps", get("/api/apps").o.optJSONArray("apps")).put("devices", get("/api/devices").o.optJSONArray("devices")))
    }
    val load = rememberLoad(tab, app, device, op, status, q) {
        if (tab == 1) get("/api/logs/system?lines=500")
        else get("/api/logs?" + PocketApi.query("app" to app, "device" to device, "op" to op, "status" to status, "q" to q, "limit" to "300"))
    }
    PocketScreen("Logs", load) { d ->
        item {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Requests") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("System") })
            }
        }
        if (tab == 1) {
            item {
                SectionCard {
                    Text(d.strs("lines").joinToString("\n").ifBlank { "Nothing logged yet." }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp))
                }
            }
            return@PocketScreen
        }
        val c = choices.data
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val apps = c?.arr("apps").orEmpty()
                val devs = c?.arr("devices").orEmpty()
                Chooser(apps.firstOrNull { it.s("id") == app }?.s("name") ?: "All apps",
                    listOf("" to "All apps") + apps.map { it.s("id") to it.s("name") }) { app = it }
                Chooser(devs.firstOrNull { it.s("uid") == device }?.s("name") ?: "All devices",
                    listOf("" to "All devices") + devs.map { it.s("uid") to it.s("name") }) { device = it }
                Chooser(op.ifBlank { "All operations" }, listOf("" to "All operations") + d.strs("ops").map { it to it }) { op = it }
                Chooser(when (status) { "ok" -> "Successful"; "err" -> "Errors"; else -> "All results" },
                    listOf("" to "All results", "ok" to "Successful", "err" to "Errors")) { status = it }
            }
        }
        item {
            OutlinedTextField(search, { search = it }, label = { Text("Search file name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { q = search }) { Text("Search") } })
        }
        item {
            SectionCard(if (d.arr("items").size >= 300) "Latest 300 requests" else "${d.arr("items").size} requests") {
                d.arr("items").forEach { r ->
                    val st = r.i("status")
                    ListItem(
                        headlineContent = { Text("${r.s("op")} ${r.s("key")}".trim()) },
                        overlineContent = { Text("${dateTime(r.d("ts"))} · ${r.s("app")} · ${r.s("device_name")}") },
                        supportingContent = { Text(listOfNotNull(
                            (r.d("bytes_in") + r.d("bytes_out")).takeIf { it > 0 }?.let { bytes(it) },
                            "${r.d("ms").roundToInt()} ms", r.s("note").ifBlank { null }).joinToString(" · ")) },
                        trailingContent = {
                            Text("$st", color = if (st >= 400 && st != 404) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = clearItem(),
                    )
                }
            }
        }
    }
}
