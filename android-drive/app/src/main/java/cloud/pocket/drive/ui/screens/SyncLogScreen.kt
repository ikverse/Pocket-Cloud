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
