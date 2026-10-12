package cloud.pocket.panel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.GB
import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.Session
import cloud.pocket.panel.data.body
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.data.gb
import cloud.pocket.panel.ui.components.ConfirmDialog
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.PocketScreen
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.SectionTitle
import cloud.pocket.panel.ui.components.SliderSetting
import cloud.pocket.panel.ui.components.SliderMark
import cloud.pocket.panel.ui.components.SliderMarks
import cloud.pocket.panel.ui.components.rememberLoad
import cloud.pocket.panel.update.UpdateControls
import cloud.pocket.panel.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

@Composable
fun SettingsScreen() {
    val panel = LocalPanel.current
    val context = LocalContext.current
    val load = rememberLoad { get("/api/settings") }
    val changes = remember { mutableStateMapOf<String, Any>() }
    var secret by remember { mutableStateOf<J?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var timeFor by remember { mutableStateOf<String?>(null) }
    var passwordDialog by remember { mutableStateOf(false) }

    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        panel.act("Backup saved.") {
            val b = panel.api.get("/api/settings/backup")
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(b.o.toString(2).toByteArray()) } }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        val data = runCatching { JSONObject(text ?: "") }.getOrNull()
        if (data == null) {
            panel.act(null) { error("That file is not a Pocket CloudServer backup.") }
        } else ask = Ask("Restore settings", "Replace every setting, app and device key with the ones in this backup? Files are not touched.", "Restore", true) {
            panel.act("Settings restored.", onDone = { load.reload() }) { panel.api.post("/api/settings/restore", data) }
        }
    }

    PocketScreen("Settings", load, fab = {
        if (changes.isNotEmpty()) ExtendedFloatingActionButton(onClick = {
            val payload = JSONObject()
            changes.forEach { (key, v) ->
                val (section, name) = key.split(".")
                val obj = payload.optJSONObject(section) ?: JSONObject().also { payload.put(section, it) }
                obj.put(name, v)
            }
            panel.act("Settings saved.", onDone = { changes.clear(); load.reload() }) { panel.api.patch("/api/settings", payload) }
        }, icon = { Icon(Icons.Outlined.Save, null) }, text = { Text("Save changes") })
    }) { d ->
        val s = d.obj("settings")
        val pool = d.obj("pool")
        fun value(key: String): Any? = changes[key] ?: key.split(".").let { (a, b) -> s.obj(a).o.opt(b) }
        fun num(key: String) = (value(key) as? Number)?.toFloat() ?: 0f
        fun bool(key: String) = value(key) as? Boolean ?: false
        fun str(key: String) = value(key)?.toString() ?: ""

        @Composable
        fun slider(key: String, label: String, min: Float, max: Float, unit: String, step: Float = 1f, hint: String? = null) =
            SliderSetting(label, num(key), min, max, unit, step, hint) { changes[key] = if (step >= 1f) it.roundToInt() else it.toDouble() }

        @Composable
        fun switch(key: String, label: String, hint: String) = ListItem(
            headlineContent = { Text(label) }, supportingContent = { Text(hint) },
            trailingContent = { Switch(bool(key), { changes[key] = it }) }, colors = clearItem())

        @Composable
        fun timeRow(key: String, label: String) = ListItem(headlineContent = { Text(label) },
            trailingContent = { Text(str(key), style = MaterialTheme.typography.titleMedium) },
            modifier = Modifier.clickable { timeFor = key }, colors = clearItem())

        item { SectionTitle("Connection") }
        item {
            SectionCard {
                val addr = d.obj("addresses")
                ListItem(headlineContent = { Text("Storage address") }, supportingContent = { Text("What apps connect to") },
                    trailingContent = { Text(addr.s("storage")) }, colors = clearItem())
                ListItem(headlineContent = { Text("Control panel address") }, trailingContent = { Text(addr.s("panel")) }, colors = clearItem())
                ListItem(headlineContent = { Text("Enrollment secret") },
                    supportingContent = { Text(secret?.s("enroll_secret") ?: "Saved once on each machine; apps use it to enroll themselves") },
                    colors = clearItem())
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { panel.act(null) { secret = panel.api.get("/api/settings/enroll-secret") } }) { Text("Show") }
                    OutlinedButton(onClick = {
                        panel.act("Machine setup file copied.") {
                            val x = secret ?: panel.api.get("/api/settings/enroll-secret").also { secret = it }
                            copy("Pocket CloudServer machine setup", x.s("machine_config"))
                        }
                    }) { Text("Copy setup file") }
                    OutlinedButton(onClick = {
                        panel.act("CI secrets copied.") {
                            val x = secret ?: panel.api.get("/api/settings/enroll-secret").also { secret = it }
                            val ci = x.obj("ci")
                            copy("Pocket CloudServer CI secrets", ci.keys().joinToString("\n") { "$it=${ci.s(it)}" })
                        }
                    }) { Text("Copy as CI secrets") }
                    TextButton(onClick = {
                        ask = Ask("Replace enrollment secret", "New machines then need the new secret. Apps already enrolled keep working.", "Replace", true) {
                            panel.act("Enrollment secret replaced.") { secret = panel.api.post("/api/settings/enroll-secret/rotate") }
                        }
                    }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
                }
                Text((secret?.s("machine_config") ?: d.s("machine_config")).trim(), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp).horizontalScroll(rememberScrollState()))
            }
        }

        item { SectionTitle("Storage pool") }
        item {
            SectionCard {
                val intUsed = pool.d("int_used") / GB
                val sdUsed = pool.d("sd_used") / GB
                val reserve = num("pool.reserve_gb")
                val intNow = (pool.d("int_limit") / GB).roundToInt().toFloat()
                val sdNow = (pool.d("sd_limit") / GB).roundToInt().toFloat()
                // A pool set earlier can be larger than the disk allows today; the slider still reaches it so it shows the real value.
                val intMax = floor((pool.d("int_max") + pool.d("reserve")) / GB - reserve).toFloat().coerceAtLeast(ceil(intUsed).toFloat()).coerceAtLeast(intNow)
                val sdMax = floor(pool.d("sd_max") / GB).toFloat().coerceAtLeast(ceil(sdUsed).toFloat()).coerceAtLeast(sdNow)
                val internal = (changes["pool.internal_gb"] as? Number)?.toFloat() ?: intNow
                val sd = (changes["pool.sd_gb"] as? Number)?.toFloat() ?: sdNow
                SliderSetting("Internal storage for Pocket", internal.coerceAtMost(intMax), ceil(intUsed).toFloat(), intMax, "GB",
                    hint = "Recent files, logs and statistics",
                    below = { SliderMarks(listOf(SliderMark(intUsed.toFloat(), "in use ${bytes(pool.d("int_used"))}", MaterialTheme.colorScheme.onSurfaceVariant)),
                        ceil(intUsed).toFloat(), intMax) }) { changes["pool.internal_gb"] = it.roundToInt() }
                SliderSetting("SD card for Pocket", sd, ceil(sdUsed).toFloat(), sdMax, "GB", hint = "Older files, the nightly copy and the undo",
                    below = { SliderMarks(listOf(SliderMark(sdUsed.toFloat(), "in use ${bytes(pool.d("sd_used"))}", MaterialTheme.colorScheme.onSurfaceVariant)),
                        ceil(sdUsed).toFloat(), sdMax) }) { changes["pool.sd_gb"] = it.roundToInt() }
                slider("pool.reserve_gb", "Always leave free for Android", 2f, 20f, "GB", hint = "Keeps the phone itself from running out of space")
                val total = (internal + sd) * GB
                val promised = pool.d("promised")
                Text(if (total < promised) "The pool (${gb(total)}) is smaller than the ${gb(promised)} already promised to apps. Lower app allocations first."
                    else "Pool: ${gb(total)} · ${gb(promised)} promised to apps · ${gb(total - promised)} unpromised · ${bytes(pool.d("used"))} in use. " +
                        "When the pool is full, new uploads are refused for every app and you are alerted.",
                    color = if (total < promised) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 4.dp))
            }
        }

        item { SectionTitle("New apps") }
        item {
            SectionCard {
                switch("new_apps.auto_create", "Create apps automatically", "Otherwise new apps wait for you on the Apps page")
                slider("new_apps.alloc_gb", "Default allocation", 0.5f, 20f, "GB", step = 0.5f, hint = "If the pool has less unpromised, a new app gets what is left")
                slider("new_apps.warn_pct", "Warn at", 50f, 95f, "%", step = 5f)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("At 100%", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        listOf("refuse" to "Refuse uploads", "warn" to "Warn only").forEachIndexed { i, (v, label) ->
                            SegmentedButton(selected = str("new_apps.at_limit") == v, onClick = { changes["new_apps.at_limit"] = v },
                                shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                        }
                    }
                }
                slider("new_apps.move_days", "Default move to SD card after", 1f, 60f, "days")
            }
        }

        item { SectionTitle("Storage and nightly jobs") }
        item {
            SectionCard {
                timeRow("jobs.nightly_time", "Nightly jobs start at")
                slider("pool.early_move_pct", "Move early when the internal pool is", 50f, 100f, "% full", step = 5f, hint = "Oldest files first")
                slider("jobs.skip_changed_hours", "Never move files changed in the last", 1f, 72f, "hours", hint = "Protects files still being written")
                slider("jobs.undo_days", "Keep deleted and overwritten files for", 1f, 60f, "days", hint = "The undo period")
                ListItem(headlineContent = { Text("Integrity check day") }, trailingContent = {
                    Chooser(WEEKDAYS[num("jobs.integrity_weekday").roundToInt().coerceIn(0, 6)], WEEKDAYS.mapIndexed { i, w -> "$i" to w }) {
                        changes["jobs.integrity_weekday"] = it.toInt()
                    }
                }, colors = clearItem())
                timeRow("jobs.integrity_time", "Integrity check time")
                ListItem(headlineContent = { Text("SD card folder") }, supportingContent = { Text(s.s("sd_root")) }, colors = clearItem())
            }
        }

        item { SectionTitle("Logs and statistics") }
        item {
            SectionCard {
                slider("logs.request_days", "Keep detailed request logs for", 7f, 365f, "days")
                ListItem(headlineContent = { Text("Keep daily totals") }, trailingContent = { Text("Forever") }, colors = clearItem())
                switch("logs.record_names", "Record file names in logs", "Off keeps only app, device, size and result")
            }
        }

        item { SectionTitle("Health checks") }
        item {
            SectionCard {
                slider("health.every_min", "Run every", 1f, 30f, "minutes")
                ListItem(headlineContent = { Text("Alert thresholds") }, supportingContent = { Text("Per alert, on the Alerts page") },
                    modifier = Modifier.clickable { panel.open("alerts") }, colors = clearItem())
            }
        }

        item { SectionTitle("Server") }
        item {
            SectionCard {
                switch("server.read_only", "Read-only mode", "Apps can read but not write or delete")
                switch("share.enabled", "Share links", "Apps can make links to single files for people outside Tailscale, through a Cloudflare tunnel. Only the shared file is reachable that way.")
                switch("push.enabled", "Push notices to apps", "The phone's built-in push server (port 2586, Tailscale only, ntfy-style): apps learn of changes at once instead of polling.")
                switch("lan.enabled", "Also listen on the home Wi-Fi", "Faster for devices at home. Anyone on the Wi-Fi can reach the storage port; every request still needs a device key. The control panel stays on Tailscale only.")
                slider("server.timeout_s", "Request timeout", 10f, 300f, "s", step = 10f)
                slider("server.upload_buffer_mb", "Large upload buffer", 16f, 256f, "MB", step = 16f, hint = "Memory for uploads sent in parts")
                slider("server.multipart_expiry_h", "Unfinished uploads expire after", 12f, 336f, "hours", step = 12f)
                slider("server.dir_cache_s", "Directory cache", 5f, 300f, "s", step = 5f, hint = "Refreshed automatically after each nightly move")
                ListItem(headlineContent = { Text("Restart the storage server") }, supportingContent = { Text("Running requests finish first") },
                    trailingContent = { TextButton(onClick = {
                        ask = Ask("Restart the storage server", "Running requests finish first; new ones wait a moment.", "Restart") {
                            panel.act("Storage server restarted.") { panel.api.post("/api/server/restart") }
                        }
                    }) { Text("Restart") } }, colors = clearItem())
            }
        }

        item { SectionTitle("Control panel") }
        item {
            SectionCard {
                ListItem(headlineContent = { Text("Admin password") }, supportingContent = { Text("Changing it signs every browser and phone out") },
                    trailingContent = { TextButton(onClick = { passwordDialog = true }) { Text("Change") } }, colors = clearItem())
                ListItem(headlineContent = { Text("Settings backup") }, supportingContent = { Text("All settings, apps and device keys in one file") }, colors = clearItem())
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { backupLauncher.launch("pocket-cloud-backup.json") }) { Text("Save backup") }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Restore from file") }
                }
                ListItem(headlineContent = { Text("Sign out of this phone") },
                    trailingContent = { TextButton(onClick = {
                        panel.act(null, onDone = { panel.signedOut() }) { runCatching { panel.api.post("/api/logout") } }
                    }) { Text("Sign out") } }, colors = clearItem())
            }
        }

        item { SectionTitle("About") }
        item { SectionCard { UpdateControls(Updates.of(context)) } }
    }

    ask?.let { c -> cloud.pocket.panel.ui.components.ConfirmDialog(c.title, c.text, c.button, c.danger, onDismiss = { ask = null }, onConfirm = c.action) }
    timeFor?.let { key -> TimeDialog(load.data?.let { d -> (changes[key] ?: key.split(".").let { (a, b) -> d.obj("settings").obj(a).s(b) }).toString() } ?: "03:00",
        onDismiss = { timeFor = null }) { changes[key] = it } }
    if (passwordDialog) PasswordDialog(onDismiss = { passwordDialog = false }) { old, new ->
        panel.act("Password changed. Other browsers and phones are signed out.") {
            val r = panel.api.post("/api/password", body("old" to old, "new" to new))
            panel.api.token = r.s("token")
            Session(context).token = r.s("token")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val (h, m) = initial.split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 3 }) to (it.getOrElse(1) { 0 }) }
    val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDismiss(); onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) })
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onChange: (String, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Change admin password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(old, { old = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(new, { new = it }, label = { Text("New password (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(again, { again = it }, label = { Text("New password again") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    isError = again.isNotEmpty() && again != new, supportingText = { if (again.isNotEmpty() && again != new) Text("The two are different") })
            }
        },
        confirmButton = { TextButton(enabled = old.isNotBlank() && new.length >= 8 && new == again, onClick = { onDismiss(); onChange(old, new) }) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
