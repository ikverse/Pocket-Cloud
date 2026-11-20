package cloud.pocket.drive.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.DOWNLOAD_ONLY
import cloud.pocket.drive.data.KIND_MEDIA
import cloud.pocket.drive.data.STATUS_CONFIRM
import cloud.pocket.drive.data.STATUS_ERROR
import cloud.pocket.drive.data.SyncPair
import cloud.pocket.drive.data.UPLOAD_ONLY
import cloud.pocket.drive.data.ago
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.count
import cloud.pocket.drive.data.sync.CameraBackup
import cloud.pocket.drive.data.sync.SyncScheduler
import cloud.pocket.drive.ui.components.Badge
import cloud.pocket.drive.ui.components.Divided
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.ItemRow
import cloud.pocket.drive.ui.components.Note
import cloud.pocket.drive.ui.components.PCard
import cloud.pocket.drive.ui.components.PSwitch
import cloud.pocket.drive.ui.components.PlainButton
import cloud.pocket.drive.ui.components.PrimaryButton
import cloud.pocket.drive.ui.components.ScreenPadding
import cloud.pocket.drive.ui.components.ScreenTitle
import cloud.pocket.drive.ui.components.SectionHeader
import cloud.pocket.drive.ui.components.SheetAction
import cloud.pocket.drive.ui.components.Tone
import cloud.pocket.drive.ui.components.TwoLine
import cloud.pocket.drive.ui.theme.PocketTheme
import kotlinx.coroutines.launch

fun modeLabel(mode: String) = when (mode) {
    UPLOAD_ONLY -> "Upload only (backup)"
    DOWNLOAD_ONLY -> "Download only"
    else -> "Two-way"
}

private fun arrow(mode: String) = when (mode) { UPLOAD_ONLY -> "→"; DOWNLOAD_ONLY -> "←"; else -> "↔" }

/** The Sync tab: its list, and the screens opened from it. */
@Composable
fun SyncTab(snackbar: SnackbarHostState) {
    var route by rememberSaveable { mutableStateOf("list") }
    when {
        route == "list" -> SyncList(snackbar, onEdit = { route = "edit:$it" }, onLog = { route = "log:$it" })
        route.startsWith("edit:") -> SyncPairEditor(route.removePrefix("edit:").toLong(), onClose = { route = "list" }, onLog = { route = "log:$it" })
        route.startsWith("log:") -> SyncLogScreen(route.removePrefix("log:").toLong(), onBack = { route = "list" })
    }
}

private const val CONFLICTS_SEEN = "conflictsSeenUntil"
private const val WEEK = 7L * 24 * 3600 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncList(snackbar: SnackbarHostState, onEdit: (Long) -> Unit, onLog: (Long) -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    val pairs by g.db.sync().observePairs().collectAsState(initial = emptyList())
    val log by g.db.sync().observeAllLog().collectAsState(initial = emptyList())
    var seen by remember { mutableLongStateOf(g.settings.long(CONFLICTS_SEEN)) }
    var adding by remember { mutableStateOf(false) }
    val names = pairs.associate { it.id to it.name }
    val conflicts = Timeline.conflicts(log, maxOf(seen, System.currentTimeMillis() - WEEK))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Sync") {
            PrimaryButton("Sync now", {
                SyncScheduler.runNow(context)
                scope.launch { snackbar.showSnackbar("Checking every folder now") }
            }, small = true, enabled = pairs.isNotEmpty())
        }

        // ------------------------------------------------ what needs you, on top
        pairs.filter { it.status == STATUS_CONFIRM }.forEach { p ->
            Note {
                val what = when (p.pendingFrom) {
                    "local" -> "${count(p.pendingDeletes)} files were deleted on this phone."
                    "remote" -> "${count(p.pendingDeletes)} files were deleted on the server."
                    else -> "${count(p.pendingDeletes)} files were deleted."
                }
                TwoLine("${p.name} stopped before deleting", "$what Nothing has been deleted yet.", subColor = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlainButton(if (p.pendingFrom == "remote") "Delete here too" else "Delete there too", {
                        scope.launch { g.db.sync().updatePair(p.copy(decision = "delete", status = "")); SyncScheduler.runNow(context, p.id) }
                    }, Modifier.weight(1f), small = true, danger = true)
                    PlainButton("Bring them back", {
                        scope.launch { g.db.sync().updatePair(p.copy(decision = "restore", status = "")); SyncScheduler.runNow(context, p.id) }
                    }, Modifier.weight(1f), small = true)
                }
            }
        }
        pairs.filter { it.status == STATUS_ERROR && it.enabled }.forEach { p ->
            Note(bad = true) {
                TwoLine("${p.name} has a problem", p.lastResult.ifBlank { "The last run failed." }, subColor = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlainButton("Try again", { SyncScheduler.runNow(context, p.id) }, small = true)
                    PlainButton("Show log", { onLog(p.id) }, small = true)
                }
            }
        }
        if (conflicts.isNotEmpty()) Note {
            TwoLine(if (conflicts.size == 1) "A file changed in two places" else "${conflicts.size} files changed in two places",
                "Both copies were kept; yours has the device name and date in its name.", subColor = MaterialTheme.colorScheme.onSurface)
            conflicts.take(3).forEach { c ->
                Text("${names[c.pairId] ?: "Sync"}: ${c.text}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlainButton("Show log", { onLog(conflicts.first().pairId) }, small = true)
                PlainButton("Dismiss", { val now = System.currentTimeMillis(); g.settings.putLong(CONFLICTS_SEEN, now); seen = now }, small = true)
            }
        }

        // ------------------------------------------------ the folders
        PCard {
            SectionHeader("Folders")
            if (pairs.isEmpty()) EmptyState("Nothing is synced yet", "Pick a folder on this phone and a folder of your drive, and keep them in step.")
            Divided(pairs) { p -> PairRow(p, onEdit = { onEdit(p.id) },
                onToggle = { on -> scope.launch { g.db.sync().updatePair(p.copy(enabled = on)); SyncScheduler.ensure(g) } }) }
        }
        PlainButton("Add a folder", { adding = true }, Modifier.fillMaxWidth())
        Text("Each folder has its own rules (Wi-Fi only, while charging, which copy wins). A run that would delete many files stops and asks first.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Add a folder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SheetAction("Back up the camera", Icons.Filled.CameraAlt, {
                adding = false
                scope.launch { val p = CameraBackup.ensure(g); SyncScheduler.ensure(g); onEdit(p.id) }
            })
            SheetAction("A photo or video folder", Icons.Filled.PhotoLibrary, { adding = false; onEdit(-1) })
            SheetAction("A folder on this phone", Icons.Filled.FolderOpen, { adding = false; onEdit(-2) })
        }
    }
}

@Composable
private fun PairRow(p: SyncPair, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val x = PocketTheme.extra
    ItemRow(onClick = onEdit, trailing = {
        when {
            p.status == STATUS_CONFIRM -> Badge("Asks you", Tone.WARN)
            p.status == STATUS_ERROR && p.enabled -> Badge("Problem", Tone.BAD)
        }
        PSwitch(p.enabled, onToggle)
    }) {
        val from = if (p.kind == KIND_MEDIA) "Photos in ${p.localPath.trimEnd('/')}" else p.localPath.substringAfterLast('/')
        val to = bucketLabel(p.bucket) + if (p.remotePrefix.isNotEmpty()) " / " + p.remotePrefix.trimEnd('/') else ""
        val state = when {
            !p.enabled -> "Paused"
            p.status == STATUS_CONFIRM -> "Waiting for your answer"
            p.lastRun > 0 -> p.lastResult.ifBlank { "Up to date" } + " · " + ago(p.lastRun / 1000.0)
            else -> "Not run yet"
        }
        Column {
            Text(p.name, style = MaterialTheme.typography.titleSmall)
            Text("$from ${arrow(p.mode)} $to" + (if (p.wifiOnly) " · Wi-Fi only" else "") + (if (p.chargingOnly) " · charging" else ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Text(state, style = MaterialTheme.typography.bodyMedium, color = if (p.status == STATUS_ERROR && p.enabled) x.bad else MaterialTheme.colorScheme.onSurface)
        }
    }
}
