package cloud.pocket.drive.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.DEVICE
import cloud.pocket.drive.data.DOWNLOAD_ONLY
import cloud.pocket.drive.data.Entry
import cloud.pocket.drive.data.KIND_FOLDER
import cloud.pocket.drive.data.KIND_MEDIA
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.SHARED
import cloud.pocket.drive.data.SyncPair
import cloud.pocket.drive.data.TWO_WAY
import cloud.pocket.drive.data.UPLOAD_ONLY
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.count
import cloud.pocket.drive.data.sync.FileScanner
import cloud.pocket.drive.data.sync.MediaFolder
import cloud.pocket.drive.data.sync.MediaScanner
import cloud.pocket.drive.data.sync.Net
import cloud.pocket.drive.data.sync.SyncScheduler
import cloud.pocket.drive.ui.components.ConfirmDialog
import cloud.pocket.drive.ui.components.SectionTitle
import cloud.pocket.drive.ui.components.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Make or change a sync pair. [id] is a pair's id, -1 for a new photo folder, -2 for a new phone folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPairEditor(id: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }

    var loaded by remember { mutableStateOf(id < 0) }
    var existing by remember { mutableStateOf<SyncPair?>(null) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(if (id == -1L) KIND_MEDIA else KIND_FOLDER) }
    var localPath by remember { mutableStateOf("") }
    var scope_ by remember { mutableStateOf("both") }
    var byDate by remember { mutableStateOf(false) }
    var bucket by remember { mutableStateOf(SHARED) }
    var remotePrefix by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(if (id == -1L) UPLOAD_ONLY else TWO_WAY) }
    var wifiOnly by remember { mutableStateOf(true) }
    var chargingOnly by remember { mutableStateOf(false) }
    var newestWins by remember { mutableStateOf(false) }
    var encrypt by remember { mutableStateOf(false) }
    var vaultPw by remember { mutableStateOf("") }
    var vaultPw2 by remember { mutableStateOf("") }
    var coveringVault by remember { mutableStateOf<cloud.pocket.drive.data.Vault?>(null) }
    var pickRemote by remember { mutableStateOf(false) }
    var pickLocal by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mediaOk by remember { mutableStateOf(MediaScanner.hasPermission(context)) }
    var mediaFolders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var allFiles by remember { mutableStateOf(Net.allFilesAccess()) }

    LaunchedEffect(id) {
        if (id > 0) {
            g.db.sync().pair(id)?.let { p ->
                existing = p; name = p.name; kind = p.kind; localPath = p.localPath; scope_ = p.mediaScope; byDate = p.byDate; bucket = p.bucket
                remotePrefix = p.remotePrefix; mode = p.mode; wifiOnly = p.wifiOnly; chargingOnly = p.chargingOnly; newestWins = p.newestWins; encrypt = p.vault
            }
            loaded = true
        }
    }
    LaunchedEffect(bucket, remotePrefix, encrypt) { coveringVault = if (encrypt) g.vaults.containing(bucket, remotePrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }) else null }
    LaunchedEffect(mediaOk) { if (mediaOk) mediaFolders = withContext(Dispatchers.IO) { MediaScanner.folders(context) } }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { mediaOk = MediaScanner.hasPermission(context) }

    // how much is in the chosen phone folder
    val folderSize by produceState<Pair<Int, Long>?>(null, kind, localPath, allFiles) {
        value = null
        if (kind == KIND_FOLDER && localPath.isNotBlank() && allFiles) {
            value = withContext(Dispatchers.IO) {
                runCatching { FileScanner.scan(File(localPath)).values.let { it.size to it.sumOf { e -> e.size } } }.getOrNull()
            }
        }
    }

    if (!loaded) return

    fun save() {
        if (localPath.isBlank()) { error = if (kind == KIND_MEDIA) "Choose which photos to sync." else "Choose a folder on this phone."; return }
        val prefix = remotePrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        if (encrypt && kind == KIND_FOLDER) {
            if (prefix.isEmpty()) { error = "Choose a folder in your drive: the whole drive can't be a vault."; return }
            if (coveringVault == null && (vaultPw.length < 8 || vaultPw != vaultPw2)) { error = "Choose a vault password of at least 8 characters, and type it twice."; return }
        }
        val finalName = name.ifBlank { if (kind == KIND_MEDIA) "Photos in " + localPath.trim('/').substringAfterLast('/') else File(localPath).name }
        val pair = (existing ?: SyncPair(name = finalName, kind = kind, localPath = localPath)).copy(
            name = finalName, kind = kind, localPath = localPath, mediaScope = scope_, byDate = byDate, bucket = bucket, remotePrefix = prefix,
            mode = if (kind == KIND_MEDIA || cloud.pocket.drive.shizuku.ShizukuAccess.isProtected(localPath)) UPLOAD_ONLY else mode, wifiOnly = wifiOnly, chargingOnly = chargingOnly, newestWins = newestWins, vault = encrypt && kind == KIND_FOLDER)
        scope.launch {
            if (encrypt && kind == KIND_FOLDER && coveringVault == null) g.vaults.add(bucket, prefix, prefix.trimEnd('/').substringAfterLast('/'), vaultPw)
            val pid = if (existing == null) g.db.sync().insertPair(pair) else { g.db.sync().updatePair(pair); pair.id }
            SyncScheduler.ensure(g)
            SyncScheduler.runNow(context, pid)
            onClose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (existing == null) "New sync" else "Edit sync") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = ::save) { Text("Save") } })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))

            // ---------------- this phone
            SectionTitle("On this phone")
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == KIND_MEDIA, { kind = KIND_MEDIA; localPath = ""; mode = UPLOAD_ONLY }, label = { Text("Photos and videos") })
                FilterChip(kind == KIND_FOLDER, { kind = KIND_FOLDER; localPath = ""; if (mode == UPLOAD_ONLY && existing == null) mode = TWO_WAY }, label = { Text("A folder") })
            }
            if (kind == KIND_MEDIA) {
                if (!mediaOk) {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Pocket Drive needs permission to read your photos and videos.")
                            Button(onClick = { askMedia.launch(MediaScanner.permissions()) }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow") }
                        }
                    }
                } else {
                    Text("Choose a folder", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
                    mediaFolders.forEach { f ->
                        ListItem(
                            headlineContent = { Text(f.path.trimEnd('/')) },
                            supportingContent = { Text("${count(f.images)} photos · ${count(f.videos)} videos") },
                            leadingContent = { RadioButton(localPath == f.path, { localPath = f.path }) },
                            modifier = Modifier.clickable { localPath = f.path },
                        )
                    }
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(scope_ == "both", { scope_ = "both" }, label = { Text("Photos and videos") })
                        FilterChip(scope_ == "images", { scope_ = "images" }, label = { Text("Photos") })
                        FilterChip(scope_ == "videos", { scope_ = "videos" }, label = { Text("Videos") })
                    }
                    ListItem(headlineContent = { Text("File by date") }, supportingContent = { Text("Put files in year and month folders, by the date taken") },
                        trailingContent = { Switch(byDate, { byDate = it }) })
                }
            } else {
                if (!Net.folderSyncSupported()) {
                    Text("Syncing a folder needs Android 11 or newer. On this phone you can back up photos and videos.", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp))
                } else if (!allFiles) {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("To sync a folder, Pocket Drive needs \"All files access\". Android asks you to switch it on in Settings.")
                            Button(onClick = {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)))
                            }, modifier = Modifier.padding(top = 8.dp)) { Text("Open settings") }
                            TextButton(onClick = { allFiles = Net.allFilesAccess() }) { Text("I allowed it") }
                        }
                    }
                }
                if (cloud.pocket.drive.shizuku.ShizukuAccess.isProtected(localPath)) Text("This folder is closed to apps. It will be backed up (upload only) through Shizuku; see Settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(headlineContent = { Text(localPath.ifBlank { "No folder chosen" }) },
                    supportingContent = {
                        val s = folderSize
                        if (s != null) Text("${count(s.first)} files, ${bytes(s.second)}")
                    },
                    trailingContent = { OutlinedButton(onClick = { pickLocal = true }, enabled = Net.folderSyncSupported() && allFiles) { Text("Choose") } })
            }

            // ---------------- the drive
            SectionTitle("In your drive")
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(bucket == SHARED, { bucket = SHARED }, label = { Text("My Drive") })
                FilterChip(bucket == DEVICE, { bucket = DEVICE }, label = { Text("This device") })
            }
            ListItem(headlineContent = { Text(bucketLabel(bucket) + " / " + remotePrefix) }, supportingContent = { Text("Folder in the drive") },
                trailingContent = { OutlinedButton(onClick = { pickRemote = true }) { Text("Choose") } })

            // ---------------- how
            if (kind == KIND_FOLDER) {
                SectionTitle("How")
                listOf(TWO_WAY to "Keep both in step", UPLOAD_ONLY to "Upload only: the drive gets a copy, nothing is deleted there",
                    DOWNLOAD_ONLY to "Download only: this phone gets a copy of the drive").forEach { (m, text) ->
                    ListItem(headlineContent = { Text(modeLabel(m)) }, supportingContent = { Text(text) },
                        leadingContent = { RadioButton(mode == m, { mode = m }) }, modifier = Modifier.clickable { mode = m })
                }
                if (mode == TWO_WAY) ListItem(headlineContent = { Text("Newest file wins") },
                    supportingContent = { Text("When both sides changed a file, keep the newer one. Otherwise both are kept.") },
                    trailingContent = { Switch(newestWins, { newestWins = it }) })
            }
            if (kind == KIND_FOLDER) {
                SectionTitle("Privacy")
                ListItem(headlineContent = { Text("Encrypt in a vault") },
                    supportingContent = { Text("Files are encrypted on this phone, with their names, before they go up. Anyone with the server's storage can't read them.") },
                    trailingContent = { Switch(encrypt, { encrypt = it }) })
                if (encrypt && coveringVault != null) Text("Uses the vault \"${coveringVault!!.name}\" (its password is saved on this phone).",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                if (encrypt && coveringVault == null) {
                    OutlinedTextField(vaultPw, { vaultPw = it }, label = { Text("Vault password") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        supportingText = { Text("If you lose it, the files can't be recovered. Plain rclone can open the vault with it.") })
                    OutlinedTextField(vaultPw2, { vaultPw2 = it }, label = { Text("Vault password again") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            }
            SectionTitle("When")
            ListItem(headlineContent = { Text("Only on Wi-Fi") }, trailingContent = { Switch(wifiOnly, { wifiOnly = it }) })
            ListItem(headlineContent = { Text("Only while charging") }, trailingContent = { Switch(chargingOnly, { chargingOnly = it }) })

            if (existing != null) OutlinedButton(onClick = { deleting = true }, modifier = Modifier.padding(16.dp)) {
                Text("Stop syncing and remove", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (pickRemote) RemoteFolderPicker(bucket, remotePrefix, onDismiss = { pickRemote = false }, onPick = { remotePrefix = it })
    if (pickLocal) LocalFolderPicker(localPath, onDismiss = { pickLocal = false }, onPick = { localPath = it })
    if (deleting) ConfirmDialog("Remove this sync", "The files on this phone and in your drive stay. Only the link between them is removed.", "Remove", danger = true,
        onDismiss = { deleting = false }, onConfirm = {
            scope.launch {
                existing?.let { g.db.sync().forgetAll(it.id); g.db.sync().clearLog(it.id); g.db.sync().deletePair(it.id) }
                SyncScheduler.ensure(g)
                onClose()
            }
        })
}

/** Pick (or make) a folder in a bucket of the drive. */
@Composable
fun RemoteFolderPicker(bucket: String, start: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val g = (LocalContext.current.applicationContext as DriveApplication).graph
    var current by remember { mutableStateOf(start) }
    var folders by remember { mutableStateOf<List<Entry>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        folders = null
        folders = runCatching { g.remote.list(bucket, current).filter { it.isDir }.sortedBy { it.name.lowercase() } }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder in the drive") },
        text = {
            Column {
                Text(bucketLabel(bucket) + " / " + current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.height(280.dp)) {
                    if (current.isNotEmpty()) item {
                        ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                            modifier = Modifier.clickable { current = Keys.parent(current) })
                    }
                    val list = folders
                    if (list == null) item { Text("Loading…", Modifier.padding(16.dp)) }
                    else items(list, key = { it.key }) { f ->
                        ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(Icons.Filled.Folder, null) }, modifier = Modifier.clickable { current = f.key })
                    }
                }
                TextButton(onClick = { newFolder = true }) { Icon(Icons.Filled.CreateNewFolder, null, Modifier.padding(end = 4.dp)); Text("New folder here") }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onPick(current) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (newFolder) TextInputDialog("New folder", "Folder name", confirm = "Create", validate = { Keys.problem(it) }, onDismiss = { newFolder = false },
        onConfirm = { current = current + it + "/" })     // it appears in the drive with the first file
}

/** Pick a folder on this phone (needs "All files access"). */
@Composable
fun LocalFolderPicker(start: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val top = Environment.getExternalStorageDirectory()
    var current by remember { mutableStateOf(File(start.ifBlank { top.path }).takeIf { it.isDirectory } ?: top) }
    val folders = remember(current) { current.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList() }
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder on this phone") },
        text = {
            Column {
                Text(current.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(typed, { typed = it }, label = { Text("Or type a path (for Android/data)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(300.dp)) {
                    if (current != top && current.parentFile != null) item {
                        ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                            modifier = Modifier.clickable { current = current.parentFile ?: top })
                    }
                    items(folders, key = { it.path }) { f ->
                        ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(Icons.Filled.Folder, null) }, modifier = Modifier.clickable { current = f })
                    }
                    if (folders.isEmpty()) item { Text("No folders here", Modifier.padding(16.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onPick(typed.trim().ifBlank { current.path }) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
