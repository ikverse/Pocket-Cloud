package cloud.pocket.drive.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.DOWNLOAD_ONLY
import cloud.pocket.drive.data.KIND_MEDIA
import cloud.pocket.drive.data.STATUS_CONFIRM
import cloud.pocket.drive.data.STATUS_ERROR
import cloud.pocket.drive.data.SyncPair
import cloud.pocket.drive.data.TWO_WAY
import cloud.pocket.drive.data.UPLOAD_ONLY
import cloud.pocket.drive.data.ago
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.sync.CameraBackup
import cloud.pocket.drive.data.sync.SyncScheduler
import cloud.pocket.drive.ui.components.EmptyState
import kotlinx.coroutines.launch

fun modeLabel(mode: String) = when (mode) {
    UPLOAD_ONLY -> "Upload only (backup)"
    DOWNLOAD_ONLY -> "Download only"
    else -> "Two-way"
}

/** The Sync tab: its list, and the screens opened from it. */
@Composable
fun SyncTab(snackbar: androidx.compose.material3.SnackbarHostState) {
    var route by rememberSaveable { mutableStateOf("list") }
    when {
        route == "list" -> SyncList(snackbar, onEdit = { route = "edit:$it" }, onLog = { route = "log:$it" }, onPermissions = { route = "perm" })
        route.startsWith("edit:") -> SyncPairEditor(route.removePrefix("edit:").toLong(), onClose = { route = "list" })
        route.startsWith("log:") -> SyncLogScreen(route.removePrefix("log:").toLong(), onBack = { route = "list" })
        route == "perm" -> PermissionsScreen(onBack = { route = "list" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncList(snackbar: androidx.compose.material3.SnackbarHostState, onEdit: (Long) -> Unit, onLog: (Long) -> Unit, onPermissions: () -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    val pairs by g.db.sync().observePairs().collectAsState(initial = emptyList())
    var addMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sync") }, actions = {
                IconButton(onClick = onPermissions) { Icon(Icons.Filled.Security, "Permissions") }
                IconButton(onClick = {
                    SyncScheduler.runNow(context)
                    scope.launch { snackbar.showSnackbar("Syncing now") }
                }, enabled = pairs.isNotEmpty()) { Icon(Icons.Filled.Sync, "Sync now") }
            })
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") })
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Back up the camera") }, leadingIcon = { Icon(Icons.Filled.CameraAlt, null) }, onClick = {
                        addMenu = false
                        scope.launch { val p = CameraBackup.ensure(g); SyncScheduler.ensure(g); onEdit(p.id) }
                    })
                    DropdownMenuItem(text = { Text("Photo or video folder") }, leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) }, onClick = { addMenu = false; onEdit(-1) })
                    DropdownMenuItem(text = { Text("Folder on this phone") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) }, onClick = { addMenu = false; onEdit(-2) })
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        if (pairs.isEmpty()) {
            Column(Modifier.padding(inner).fillMaxSize()) {
                EmptyState("Nothing is synced yet", "Pick a folder on this phone and a folder of your drive, and keep them in step.")
            }
        } else LazyColumn(Modifier.padding(inner).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pairs, key = { it.id }) { p ->
                PairCard(p,
                    onToggle = { on -> scope.launch { g.db.sync().updatePair(p.copy(enabled = on)); SyncScheduler.ensure(g) } },
                    onSyncNow = { SyncScheduler.runNow(context, p.id); scope.launch { snackbar.showSnackbar("Syncing ${p.name}") } },
                    onEdit = { onEdit(p.id) }, onLog = { onLog(p.id) },
                    onDecide = { d ->
                        scope.launch {
                            g.db.sync().updatePair(p.copy(decision = d, status = ""))
                            SyncScheduler.runNow(context, p.id)
                        }
                    })
            }
        }
    }
}

@Composable
private fun PairCard(p: SyncPair, onToggle: (Boolean) -> Unit, onSyncNow: () -> Unit, onEdit: () -> Unit, onLog: () -> Unit, onDecide: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = if (p.status == STATUS_CONFIRM) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    val where = (if (p.kind == KIND_MEDIA) "Photos in ${p.localPath}" else p.localPath) + "  →  " + bucketLabel(p.bucket) + " / " + p.remotePrefix
                    Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(modeLabel(p.mode) + (if (p.wifiOnly) " · Wi-Fi only" else "") + (if (p.chargingOnly) " · charging" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(p.enabled, onToggle)
            }
            Text(
                if (p.lastRun > 0) "${p.lastResult.ifBlank { "Done" }} · ${ago(p.lastRun / 1000.0)}" else p.lastResult.ifBlank { "Not run yet" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (p.status == STATUS_ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp))
            if (p.status == STATUS_CONFIRM) {
                val what = when (p.pendingFrom) {
                    "local" -> "${p.pendingDeletes} files were deleted on this phone."
                    "remote" -> "${p.pendingDeletes} files were deleted on the server."
                    else -> "${p.pendingDeletes} files were deleted."
                }
                Text("$what Nothing has been deleted yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDecide("delete") }) { Text(if (p.pendingFrom == "remote") "Delete here too" else "Delete there too") }
                    OutlinedButton(onClick = { onDecide("restore") }) { Text("Bring them back") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSyncNow) { Icon(Icons.Filled.Sync, null, Modifier.padding(end = 4.dp)); Text("Sync now") }
                TextButton(onClick = onLog) { Icon(Icons.Filled.History, null, Modifier.padding(end = 4.dp)); Text("Log") }
                TextButton(onClick = onEdit) { Icon(Icons.Filled.Edit, null, Modifier.padding(end = 4.dp)); Text("Edit") }
            }
        }
    }
}

@Suppress("unused")
private val keepTwoWay = TWO_WAY
@Suppress("unused")
private val keepBack = Icons.AutoMirrored.Filled.ArrowBack
