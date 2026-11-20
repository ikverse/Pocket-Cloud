package cloud.pocket.drive.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.DONE
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.KIND_MEDIA
import cloud.pocket.drive.data.Quota
import cloud.pocket.drive.data.STATUS_CONFIRM
import cloud.pocket.drive.data.STATUS_ERROR
import cloud.pocket.drive.data.Vault
import cloud.pocket.drive.data.ago
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.count
import cloud.pocket.drive.data.sync.CameraBackup
import cloud.pocket.drive.data.sync.SyncScheduler
import cloud.pocket.drive.ui.Tab
import cloud.pocket.drive.ui.components.Badge
import cloud.pocket.drive.ui.components.Chevron
import cloud.pocket.drive.ui.components.ConfirmDialog
import cloud.pocket.drive.ui.components.Divided
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.FileTile
import cloud.pocket.drive.ui.components.IconTile
import cloud.pocket.drive.ui.components.ItemRow
import cloud.pocket.drive.ui.components.Meter
import cloud.pocket.drive.ui.components.Note
import cloud.pocket.drive.ui.components.PCard
import cloud.pocket.drive.ui.components.PlainButton
import cloud.pocket.drive.ui.components.PrimaryButton
import cloud.pocket.drive.ui.components.ScreenPadding
import cloud.pocket.drive.ui.components.ScreenTitle
import cloud.pocket.drive.ui.components.SectionHeader
import cloud.pocket.drive.ui.components.Tone
import cloud.pocket.drive.ui.components.TwoLine
import cloud.pocket.drive.ui.theme.TNUM
import kotlinx.coroutines.launch

/** Home: is everything safe, what needs me, my space, camera backup, offline files, recent, the Vault. */
@Composable
fun HomeScreen(browse: BrowseModel, onTab: (Tab) -> Unit, onVault: () -> Unit, onOpenFolder: (String, String) -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    val pairs by g.db.sync().observePairs().collectAsState(initial = emptyList())
    val transfers by g.db.transfers().observeAll().collectAsState(initial = emptyList())
    val offlineFiles by g.db.offline().observeFiles().collectAsState(initial = emptyList())
    val active by g.db.transfers().activeCount().collectAsState(initial = 0)
    val failed by g.db.transfers().failedCount().collectAsState(initial = 0)
    var quota by remember { mutableStateOf<Quota?>(null) }
    LaunchedEffect(Unit) { quota = g.server.quota() }

    val host = g.settings.endpoint().substringAfter("://")
    val offline = browse.error != null && browse.entries.isEmpty() && browse.locked == null
    val deciding = pairs.filter { it.status == STATUS_CONFIRM }
    val broken = pairs.filter { it.status == STATUS_ERROR && it.enabled }
    val headline = when {
        offline -> "Can't reach your Pocket Cloud."
        deciding.isNotEmpty() -> "Sync needs your answer."
        failed > 0 -> if (failed == 1) "One transfer failed." else "$failed transfers failed."
        broken.isNotEmpty() -> "A sync has a problem."
        active > 0 -> if (active == 1) "One file on its way." else "$active files on their way."
        pairs.any { it.enabled } -> "Everything is backed up."
        else -> "Your drive is ready."
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text(if (offline) "Not connected to $host" else "Connected to $host", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScreenTitle(headline, big = true)
        }

        if (offline) Note(bad = true) {
            Text(browse.error ?: "", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlainButton("Try again", { browse.reload() }, small = true)
                if (offlineFiles.isNotEmpty()) PlainButton("Offline files", { browse.offlineMode = true; onTab(Tab.FILES) }, small = true)
            }
        }
        deciding.forEach { p ->
            Note {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TwoLine(p.name, "${count(p.pendingDeletes)} files would be deleted. Nothing is deleted until you decide.", Modifier.weight(1f),
                        subColor = MaterialTheme.colorScheme.onSurface)
                    PlainButton("Decide", { onTab(Tab.SYNC) }, small = true)
                }
            }
        }
        if (failed > 0) Note(bad = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TwoLine(if (failed == 1) "1 transfer failed" else "$failed transfers failed", "They stay in Activity until you retry or dismiss them.",
                    Modifier.weight(1f), subColor = MaterialTheme.colorScheme.onSurface)
                PlainButton("Show", { onTab(Tab.ACTIVITY) }, small = true)
            }
        }

        quota?.let { q -> SpaceCard(q) }

        val camera = pairs.firstOrNull { it.name == CameraBackup.NAME } ?: pairs.firstOrNull { it.kind == KIND_MEDIA }
        if (camera != null) {
            PCard(onClick = { onTab(Tab.SYNC) }) {
                ItemRow(leading = { IconTile(Icons.Filled.CameraAlt, Color(0xFF8A4FD6), Color.White) }, trailing = {
                    when {
                        camera.status == STATUS_ERROR -> Badge("Problem", Tone.BAD)
                        !camera.enabled -> Badge("Paused")
                    }
                    Chevron()
                }) {
                    TwoLine(camera.name, when {
                        !camera.enabled -> "Paused"
                        camera.lastRun > 0 -> camera.lastResult.ifBlank { "Up to date" } + " · " + ago(camera.lastRun / 1000.0)
                        else -> "Not run yet"
                    })
                }
            }
        } else {
            PCard(onClick = {
                scope.launch { CameraBackup.ensure(g); SyncScheduler.ensure(g); onTab(Tab.SYNC) }
            }) {
                ItemRow(leading = { IconTile(Icons.Filled.CameraAlt, Color(0xFF8A4FD6), Color.White) }, trailing = { Chevron() }) {
                    TwoLine("Back up the camera", "Every photo and video you take goes to My Drive")
                }
            }
        }

        Column {
            SectionHeader("Kept offline")
            if (offlineFiles.isEmpty()) Text("No files are kept offline yet. Open a file in Files and choose Keep offline.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offlineFiles.forEach { f ->
                    PCard(Modifier.width(128.dp), onClick = { browse.openLocal(f.path, Keys.name(f.key)) }, padding = PaddingValues(12.dp)) {
                        FileTile(Keys.name(f.key), false)
                        Text(Keys.name(f.key), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp))
                        Text("On this phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        val recent = transfers.filter { it.state == DONE && it.pairId == 0L }.sortedByDescending { it.finishedAt }.take(5)
        PCard {
            SectionHeader("Recent")
            if (recent.isEmpty()) Text("Files you upload or download show up here.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Divided(recent) { t ->
                ItemRow(onClick = {
                    scope.launch {
                        val v = if (t.vaultId != 0L) g.db.vaults().get(t.vaultId) else null
                        if (v != null) onOpenFolder(v.bucket, v.prefix) else onOpenFolder(t.bucket, Keys.parent(t.key))
                    }
                }, leading = { FileTile(t.name, false) }) {
                    TwoLine(t.name, bytes(t.size) + " · " + Timeline.fileWhen(t.finishedAt) + " · " +
                        (if (t.kind == cloud.pocket.drive.data.DOWN) "Downloaded" else "Uploaded to ${bucketLabel(t.bucket)}"))
                }
            }
        }

        val vaults = browse.vaults
        PCard(onClick = onVault) {
            ItemRow(leading = { IconTile(Icons.Filled.Lock, MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface) },
                trailing = { Chevron() }) {
                TwoLine("Vault", if (vaults.isEmpty()) "Encrypt files on this phone before they leave it"
                    else (if (vaults.size == 1) "1 vault" else "${vaults.size} vaults") + ", encrypted before they leave this phone")
            }
        }
    }
}

@Composable
private fun SpaceCard(q: Quota) {
    PCard(gap = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your space", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${bytes(q.usedApp)} of ${bytes(q.allocation)}", style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TNUM),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Meter(if (q.allocation > 0) q.usedApp.toFloat() / q.allocation else null)
        Text("${bytes(q.free)} free for this device", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Vault, opened from Home: the encrypted folders, which are open on this phone, and new ones. */
@Composable
fun VaultScreen(browse: BrowseModel, onBack: () -> Unit, onOpen: (Vault) -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    BackHandler { onBack() }
    var unlocking by remember { mutableStateOf<Vault?>(null) }
    var removing by remember { mutableStateOf<Vault?>(null) }
    var creating by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val vaults = browse.vaults

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("‹ Home", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
        ScreenTitle("Vault") { PrimaryButton("New vault", { creating = true }, small = true) }
        Text("Files in a vault are encrypted on this phone, names too. The server only ever stores scrambled data. " +
            "Without the password nobody can read them, not even you.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vaults.isEmpty()) PCard { EmptyState("No vaults yet", "Make one here, or turn a folder that rclone already encrypts into one from its menu in Files.") }
        else PCard {
            Divided(vaults) { v ->
                val open = remember(v, tick) { g.vaults.hasPassword(v) }
                ItemRow(onClick = { if (open) onOpen(v) else unlocking = v }, leading = { FileTile(v.name, true, vault = true) }, trailing = {
                    if (open) Badge("Open on this phone", Tone.ACCENT) else PlainButton("Unlock", { unlocking = v }, small = true)
                }) {
                    TwoLine(v.name, bucketLabel(v.bucket) + " / " + v.prefix.trimEnd('/'))
                }
            }
        }
        if (vaults.isNotEmpty()) Text("A vault stays open on this phone once its password is saved here. To close one, remove its link: " +
            "the files stay on the server, encrypted.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vaults.isNotEmpty()) PCard {
            SectionHeader("Remove a link")
            Divided(vaults) { v ->
                ItemRow(trailing = { PlainButton("Remove", { removing = v }, small = true, danger = true) }) { TwoLine(v.name, null) }
            }
        }
    }

    unlocking?.let { v ->
        PasswordDialog("Unlock ${v.name}", askName = false, confirmPassword = false, confirm = "Unlock",
            onDismiss = { unlocking = null }, onConfirm = { _, pw -> browse.unlock(v, pw) { tick++ } })
    }
    if (creating) PasswordDialog("New vault in My Drive", askName = true, confirmPassword = true, confirm = "Create",
        note = "Files in a vault are encrypted on this phone before they are uploaded. If you lose the password, the files can't be recovered.",
        onDismiss = { creating = false }, onConfirm = { name, pw -> browse.createVaultAtTop(cloud.pocket.drive.data.SHARED, name, pw) })
    removing?.let { v ->
        ConfirmDialog("Remove vault link", "${v.name} stays on the server, still encrypted. This phone forgets its password.", "Remove",
            danger = true, onDismiss = { removing = null }, onConfirm = { browse.removeVault(v) })
    }
}
