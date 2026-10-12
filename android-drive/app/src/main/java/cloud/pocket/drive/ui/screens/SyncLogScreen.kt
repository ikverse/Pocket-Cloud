package cloud.pocket.drive.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.KIND_FOLDER
import cloud.pocket.drive.data.SyncPair
import cloud.pocket.drive.data.dateTime
import cloud.pocket.drive.data.sync.LocalTrash
import cloud.pocket.drive.data.sync.MediaScanner
import cloud.pocket.drive.data.sync.Net
import cloud.pocket.drive.data.sync.SyncScheduler
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a sync did, and the files it moved to this phone's trash. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(pairId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    val log by g.db.sync().observeLog(pairId).collectAsState(initial = emptyList())
    var pair by remember { mutableStateOf<SyncPair?>(null) }
    var trash by remember { mutableStateOf<List<LocalTrash.Item>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(pairId, refresh) {
        pair = g.db.sync().pair(pairId)
        val p = pair
        if (p != null && p.kind == KIND_FOLDER) trash = withContext(Dispatchers.IO) { LocalTrash.list(File(p.localPath)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(pair?.name ?: "Log") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { scope.launch { g.db.sync().clearLog(pairId) } }) { Icon(Icons.Filled.DeleteSweep, "Clear log") } })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            if (trash.isNotEmpty()) {
                item { SectionTitle("Trash on this phone (kept 30 days)") }
                items(trash, key = { it.file.path }) { t ->
                    ListItem(
                        headlineContent = { Text(t.rel) },
                        supportingContent = { Text("Removed ${dateTime(t.deletedAt / 1000.0)}") },
                        trailingContent = {
                            TextButton(onClick = {
                                val p = pair ?: return@TextButton
                                scope.launch {
                                    withContext(Dispatchers.IO) { LocalTrash.restore(File(p.localPath), t) }
                                    refresh++
                                    SyncScheduler.runNow(context, p.id)
                                }
                            }) { Text("Restore") }
                        })
                }
                item { SectionTitle("History") }
            }
            if (log.isEmpty()) item { EmptyState("Nothing logged yet") }
            items(log, key = { it.id }) { l ->
                ListItem(
                    overlineContent = { Text(dateTime(l.ts / 1000.0)) },
                    headlineContent = { Text(l.text) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        headlineColor = when (l.level) {
                            "error" -> MaterialTheme.colorScheme.error
                            "warn", "conflict" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }),
                )
            }
        }
    }
}

/** What Android must allow for sync to work well, with a button for each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val media = remember(tick) { MediaScanner.hasPermission(context) }
    val allFiles = remember(tick) { Net.allFilesAccess() }
    val notifications = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val battery = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val pkg = Uri.parse("package:" + context.packageName)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Permissions") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Sync runs in the background, so Android has to let it. Each step below is needed for one thing.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            Step("Photos and videos", "For backing up the camera and photo folders.", media) {
                Button(onClick = { askMedia.launch(MediaScanner.permissions()) }) { Text("Allow") }
            }
            Step("All files access", if (Net.folderSyncSupported()) "Only needed to sync ordinary folders (Documents, Downloads…)."
                else "Not available before Android 11: folders can't be synced on this phone.", allFiles || !Net.folderSyncSupported()) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }) { Text("Open settings") }
            }
            Step("Notifications", "So you see progress, and problems that need your answer.", notifications) {
                Button(onClick = { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            }
            Step("Run in the background", "Lets sync keep going while the screen is off.", battery) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }) { Text("Allow") }
            }
            if (Build.MANUFACTURER.equals("samsung", true)) {
                SectionTitle("Samsung phones")
                Text("Samsung also puts apps to sleep by itself. Open Settings, Battery, Background usage limits, Never sleeping apps, and add Pocket Drive.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp, 4.dp))
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }, modifier = Modifier.padding(16.dp)) {
                    Text("Open app settings")
                }
            }
        }
    }
}

@Composable
private fun Step(title: String, text: String, done: Boolean, action: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        overlineContent = { Text(if (done) "Allowed" else "Needs your OK", color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) },
        trailingContent = { if (!done) action() },
    )
}
