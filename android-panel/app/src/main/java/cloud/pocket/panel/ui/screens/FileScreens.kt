package cloud.pocket.panel.ui.screens

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.PocketApi
import cloud.pocket.panel.data.ago
import cloud.pocket.panel.data.body
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.data.count
import cloud.pocket.panel.data.dateTime
import java.time.LocalDateTime
import java.time.ZoneId
import cloud.pocket.panel.ui.components.ConfirmDialog
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.PocketScreen
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.TextInputDialog
import cloud.pocket.panel.ui.components.rememberLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DevicesScreen() {
    val panel = LocalPanel.current
    val load = rememberLoad { get("/api/devices") }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var rename by remember { mutableStateOf<J?>(null) }
    PocketScreen("Devices", load) { d ->
        item {
            Text("Every device that has connected, identified by its address on your private network. A device's keys only work from that address.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val devices = d.arr("devices")
        if (devices.isEmpty()) item { Text("No devices yet.") }
        item {
            SectionCard {
                devices.forEach { dv ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(dv.s("name") + if (dv.b("blocked")) " · blocked" else "") },
                        overlineContent = { Text(dv.s("ip") + (dv.s("ip_alt").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")) },
                        supportingContent = {
                            Text(dv.arr("apps").joinToString(", ") { it.s("name") } +
                                " · ${count(dv.l("requests"))} requests today · ${bytes(dv.d("bytes_in"))} in / ${bytes(dv.d("bytes_out"))} out · ${ago(dv.d("last_seen"))}")
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Device actions") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = dv })
                                    val blocked = dv.b("blocked")
                                    DropdownMenuItem(text = { Text(if (blocked) "Unblock" else "Block") }, onClick = {
                                        menu = false
                                        val go = { panel.act(if (blocked) "Device unblocked." else "Device blocked.", onDone = { load.reload() }) {
                                            panel.api.post("/api/devices/${dv.s("uid")}/block", body("blocked" to !blocked)) } }
                                        if (blocked) go() else ask = Ask("Block device", "Every app on ${dv.s("name")} is refused until you unblock it.", "Block", true, go)
                                    })
                                    DropdownMenuItem(text = { Text("Remove", color = MaterialTheme.colorScheme.error) }, onClick = {
                                        menu = false
                                        ask = Ask("Remove device", "Remove ${dv.s("name")} from every app? Its private files go into the undo.", "Remove", true) {
                                            panel.act("Device removed.", onDone = { load.reload() }) { panel.api.delete("/api/devices/${dv.s("uid")}") }
                                        }
                                    })
                                }
                            }
                        },
                        colors = clearItem(),
                    )
                }
            }
        }
    }
    ask?.let { c -> ConfirmDialog(c.title, c.text, c.button, c.danger, onDismiss = { ask = null }, onConfirm = c.action) }
    rename?.let { dv ->
        TextInputDialog("Rename device", "Name shown in the control panel", dv.s("name"), onDismiss = { rename = null }) { name ->
            panel.act("Device renamed.", onDone = { load.reload() }) { panel.api.post("/api/devices/${dv.s("uid")}/rename", body("name" to name.trim())) }
        }
    }
}

/** A small dropdown chooser built from a stock outlined button and menu. */
@Composable
fun Chooser(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(label)
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) }) }
        }
    }
}

@Composable
fun FilesScreen(startApp: String?) {
    val panel = LocalPanel.current
    val context = LocalContext.current
    val appsLoad = rememberLoad { get("/api/apps") }
    val startCard = startApp?.takeIf { it.startsWith("card:") }?.removePrefix("card:")     // "files/card:<id>" from Storage
    var app by remember { mutableStateOf(startApp?.takeUnless { startCard != null }) }
    var space by remember { mutableStateOf("shared") }
    var path by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(if (startCard != null) 2 else 0) }
    var card by remember { mutableStateOf(startCard) }
    var cardApp by remember { mutableStateOf<String?>(null) }
    var cardSpace by remember { mutableStateOf("shared") }
    var cardPath by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    val apps = appsLoad.data?.arr("apps").orEmpty()
    LaunchedEffect(apps) { if (app == null || apps.none { it.s("id") == app }) app = apps.firstOrNull()?.s("id") }
    val load = rememberLoad(app, space, path, tab, card, cardApp, cardSpace, cardPath) {
        when {
            tab == 1 -> get("/api/undo")
            tab == 2 -> {
                val st = get("/api/cards")
                val un = get("/api/cards/unassigned")
                val shelf = st.arr("shelf")
                val cid = card?.takeIf { id -> shelf.any { it.s("id") == id } } ?: shelf.firstOrNull()?.s("id")
                val files = if (cid == null) org.json.JSONObject() else get("/api/cards/$cid/files?" +
                    PocketApi.query("app" to (cardApp ?: ""), "space" to cardSpace, "path" to cardPath)).o
                J(org.json.JSONObject().put("status", st.o).put("unassigned", un.o).put("files", files).put("card", cid ?: ""))
            }
            app == null -> J(org.json.JSONObject())
            else -> get("/api/files?" + PocketApi.query("app" to app!!, "space" to space, "path" to path))
        }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val a = app ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        val cr = context.contentResolver
        val (name, size) = cr.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst()
            c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
        } ?: ("upload.bin" to 0L)
        val key = (path.split("/").filter { it.isNotBlank() } + name).joinToString("/")
        panel.act("Uploaded $name.", onDone = { load.reload() }) {
            val input = withContext(Dispatchers.IO) { cr.openInputStream(uri) } ?: error("can't open $name")
            panel.api.upload(a, space, key, input, size)
        }
    }
    val current = apps.firstOrNull { it.s("id") == app }
    PocketScreen("Files", load,
        actions = { if (tab == 0 && app != null) IconButton(onClick = { newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "New folder") } },
        fab = {
            if (tab == 0 && app != null) ExtendedFloatingActionButton(onClick = { upload.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Outlined.Upload, null) }, text = { Text("Upload") })
        }) { d ->
        item {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Browse") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Undo") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("SD cards") })
            }
        }
        if (tab == 2) {
            cardFilesItems(d, apps, cardApp, cardPath,
                onCard = { card = it; cardApp = null; cardPath = "" },
                onOpen = { a, sp, p -> cardApp = a; cardSpace = sp; cardPath = p },
                reload = { load.reload() })
            return@PocketScreen
        }
        if (tab == 1) {
            val items = d.arr("items")
            item { Text("Deleted and overwritten files are kept for ${d.i("undo_days")} days.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                SectionCard {
                    if (items.isEmpty()) Text("Nothing in the undo.", modifier = Modifier.padding(16.dp))
                    items.forEach { e ->
                        val names = apps.associate { it.s("id") to it.s("name") }
                        ListItem(
                            headlineContent = { Text(e.s("key")) },
                            overlineContent = { Text("${names[e.s("app")] ?: e.s("app")} · ${if (e.s("space") == "shared") "shared" else "device"}") },
                            supportingContent = { Text("${e.s("reason")} ${dateTime(LocalDateTime.parse(e.s("time")).atZone(ZoneId.systemDefault()).toEpochSecond().toDouble())} by ${e.s("device_name")} · ${bytes(e.d("size"))} · kept ${e.i("days_left")} more days") },
                            trailingContent = {
                                TextButton(onClick = {
                                    panel.act("Restored ${e.s("key")}.", onDone = { load.reload() }) {
                                        panel.api.post("/api/undo/restore", body("storage" to e.s("storage"), "path" to e.s("path")))
                                    }
                                }) { Text("Restore") }
                            },
                            colors = clearItem(),
                        )
                    }
                }
            }
            return@PocketScreen
        }
        if (current == null) {
            item { Text("No apps yet.") }
            return@PocketScreen
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chooser(current.s("name"), apps.map { it.s("id") to it.s("name") }) { app = it; space = "shared"; path = "" }
                val spaces = listOf("shared" to "shared · all devices") +
                    current.arr("devices").map { "device:" + it.s("uid") to "device · " + it.s("name") }
                Chooser(spaces.firstOrNull { it.first == space }?.second ?: "shared", spaces) { space = it; path = "" }
            }
        }
        item {
            val parts = path.split("/").filter { it.isNotBlank() }
            FlowRow {
                TextButton(onClick = { path = "" }) { Text(if (space == "shared") "shared" else "device") }
                parts.forEachIndexed { i, p ->
                    Text("/", modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { path = parts.take(i + 1).joinToString("/") }) { Text(p) }
                }
            }
        }
        item {
            SectionCard {
                val items = d.arr("items")
                if (items.isEmpty()) Text("This folder is empty.", modifier = Modifier.padding(16.dp))
                items.forEach { x ->
                    if (x.s("type") == "dir") {
                        val where = x.strs("storages")
                        ListItem(
                            headlineContent = { Text(x.s("name")) },
                            supportingContent = { Text("${count(x.l("files"))} files · ${bytes(x.d("size"))} · " +
                                if (where.size > 1) "Internal + SD card" else if (where.firstOrNull() == "sd") "SD card" else "Internal") },
                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            modifier = Modifier.clickable { path = (path.split("/").filter { it.isNotBlank() } + x.s("name")).joinToString("/") },
                            colors = clearItem(),
                        )
                    } else {
                        var menu by remember(x.s("key")) { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(x.s("name")) },
                            supportingContent = { Text("${bytes(x.d("size"))} · ${dateTime(x.d("modified"))} · ${if (x.s("storage") == "sd") "SD card" else "Internal"}") },
                            leadingContent = { Icon(Icons.Outlined.Description, null) },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "File actions") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("Download") }, onClick = {
                                            menu = false
                                            panel.act("Saved ${x.s("name")} to Downloads/Pocket CloudServer.") { saveToDownloads(context, panel.api, app!!, space, x.s("key"), x.s("name")) }
                                        })
                                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = {
                                            menu = false
                                            ask = Ask("Delete file", "Delete ${x.s("key")}? It stays in the undo for a while.", "Delete", true) {
                                                panel.act("File deleted.", onDone = { load.reload() }) {
                                                    panel.api.delete("/api/files?" + PocketApi.query("app" to app!!, "space" to space, "key" to x.s("key")))
                                                }
                                            }
                                        })
                                    }
                                }
                            },
                            colors = clearItem(),
                        )
                    }
                }
            }
        }
    }
    ask?.let { c -> ConfirmDialog(c.title, c.text, c.button, c.danger, onDismiss = { ask = null }, onConfirm = c.action) }
    if (newFolder) TextInputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { newFolder = false }) { name ->
        panel.act("Folder created.", onDone = { load.reload() }) {
            panel.api.post("/api/files/folder", body("app" to app, "space" to space,
                "path" to (path.split("/").filter { it.isNotBlank() } + name.trim()).joinToString("/")))
        }
    }
}

/** Save a file into the phone's Downloads/Pocket CloudServer folder (no storage permission needed on Android 10+). */
private suspend fun saveToDownloads(context: android.content.Context, api: PocketApi, app: String, space: String, key: String, name: String) {
    val cr = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Pocket CloudServer")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = withContext(Dispatchers.IO) { cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } ?: error("Can't create the file in Downloads.")
    try {
        withContext(Dispatchers.IO) { cr.openOutputStream(uri)?.use { out -> api.download(app, space, key, out) } }
        withContext(Dispatchers.IO) { cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
    } catch (e: Exception) {
        withContext(Dispatchers.IO) { cr.delete(uri, null, null) }
        throw e
    }
}
