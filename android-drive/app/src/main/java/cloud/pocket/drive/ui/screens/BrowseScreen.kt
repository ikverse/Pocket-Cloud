package cloud.pocket.drive.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.pocket.drive.data.DEVICE
import cloud.pocket.drive.data.Entry
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.OfflineFile
import cloud.pocket.drive.data.OfflineHelper
import cloud.pocket.drive.data.IndexEntry
import cloud.pocket.drive.data.Local
import cloud.pocket.drive.data.SHARED
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.dateTime
import cloud.pocket.drive.ui.components.ConfirmDialog
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.ErrorBox
import cloud.pocket.drive.ui.components.TextInputDialog
import cloud.pocket.drive.ui.components.iconFor
import cloud.pocket.drive.ui.components.isImage
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(snackbar: SnackbarHostState, model: BrowseModel = viewModel()) {
    val context = LocalContext.current
    val shown = model.visible()
    val selecting = model.selected.isNotEmpty()
    val g = (context.applicationContext as cloud.pocket.drive.DriveApplication).graph
    val pins by g.db.offline().observePins().collectAsState(initial = emptyList())
    val offlineFiles by g.db.offline().observeFiles().collectAsState(initial = emptyList())

    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<List<Entry>?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var moving by remember { mutableStateOf<List<Entry>?>(null) }
    var details by remember { mutableStateOf<Entry?>(null) }
    var viewing by remember { mutableStateOf<Entry?>(null) }
    var newVault by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<Entry?>(null) }
    var history by remember { mutableStateOf<Entry?>(null) }
    var useVault by remember { mutableStateOf<Entry?>(null) }
    var unlockVault by remember { mutableStateOf(false) }
    var removeVault by remember { mutableStateOf<cloud.pocket.drive.data.Vault?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.mapNotNull { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Local.describe(context, uri)
        }
        if (picked.isNotEmpty()) model.uploadPicked(picked)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.uploadTree(uri)
        }
    }

    LaunchedEffect(Unit) { if (model.entries.isNotEmpty()) model.reload() }     // coming back to this tab
    LaunchedEffect(Unit) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { model.intents.collect { runCatching { context.startActivity(it) }.onFailure { snackbar.showSnackbar("No app can open that.") } } }

    BackHandler(enabled = selecting || model.searching || model.prefix.isNotEmpty() || model.offlineMode || model.globalSearch || model.trashMode) {
        when {
            model.trashMode -> model.trashMode = false
            model.globalSearch -> model.globalSearch = false
            model.offlineMode -> model.offlineMode = false
            selecting -> model.clearSelection()
            model.searching -> { model.searching = false; model.query = "" }
            else -> model.up()
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                val sel = model.selectedEntries()
                TopAppBar(
                    title = { Text("${sel.size} selected") },
                    navigationIcon = { IconButton(onClick = { model.clearSelection() }) { Icon(Icons.Filled.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { model.selectAll() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton(onClick = { model.download(sel) }) { Icon(Icons.Filled.Download, "Download") }
                        IconButton(onClick = { model.share(sel) }) { Icon(Icons.Filled.Share, "Share") }
                        IconButton(onClick = { moving = sel }) { Icon(Icons.Filled.DriveFileMove, "Move") }
                        IconButton(onClick = { deleting = sel }) { Icon(Icons.Filled.Delete, "Delete") }
                    },
                )
            } else if (model.searching) {
                TopAppBar(
                    title = {
                        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        OutlinedTextField(model.query, { model.query = it }, singleLine = true,
                            placeholder = { Text(if (model.globalSearch) "Search the whole drive" else "Search this folder") },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    },
                    navigationIcon = { IconButton(onClick = { model.searching = false; model.query = ""; model.globalSearch = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                    actions = {
                        IconButton(onClick = { model.globalSearch = !model.globalSearch }) {
                            Icon(Icons.Filled.TravelExplore, "Search the whole drive", tint = if (model.globalSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(if (model.prefix.isEmpty()) bucketLabel(model.bucket) else Keys.name(model.prefix), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (model.prefix.isNotEmpty()) IconButton(onClick = { model.up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                    },
                    actions = {
                        IconButton(onClick = { model.searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        if (g.settings.has("versions")) IconButton(onClick = { model.trashMode = !model.trashMode }) {
                            Icon(Icons.Filled.Delete, "Trash", tint = if (model.trashMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { model.offlineMode = !model.offlineMode }) {
                            Icon(Icons.Filled.OfflinePin, "Offline files", tint = if (model.offlineMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                Sort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.label + if (model.sort == s) (if (model.ascending) "  ↑" else "  ↓") else "") },
                                        onClick = {
                                            if (model.sort == s) model.ascending = !model.ascending else { model.sort = s; model.ascending = true }
                                            sortMenu = false
                                        })
                                }
                            }
                        }
                        IconButton(onClick = { model.reload() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting) Box {
                ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") })
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Upload files") }, leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                        onClick = { addMenu = false; pickFiles.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("Upload a folder") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                        onClick = { addMenu = false; pickFolder.launch(null) })
                    DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = { addMenu = false; newFolder = true })
                    if (model.vault == null) DropdownMenuItem(text = { Text("New vault") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                        onClick = { addMenu = false; newVault = true })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = if (model.bucket == SHARED) 0 else 1) {
                Tab(model.bucket == SHARED, { model.showBucket(SHARED) }, text = { Text("My Drive") })
                Tab(model.bucket == DEVICE, { model.showBucket(DEVICE) }, text = { Text("This device") })
            }
            Breadcrumbs(model)
            if (model.busy != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(model.busy!!, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (model.trashMode) {
                TrashList(model.bucket, onRestored = { n -> model.restored(n) })
                return@Column
            }
            if (model.offlineMode) {
                OfflineList(offlineFiles, onOpen = { model.openLocal(it.path, Keys.name(it.key)) })
                return@Column
            }
            if (model.globalSearch) {
                GlobalSearch(model, onOpen = { b, k -> model.goTo(b, k) })
                return@Column
            }
            PullToRefreshBox(isRefreshing = model.loading, onRefresh = { model.reload() }, modifier = Modifier.fillMaxSize()) {
                when {
                    model.locked != null -> ErrorBox("This vault's password isn't saved on this phone.", { unlockVault = true }, "Offline files" to { model.offlineMode = true })
                    model.error != null && model.entries.isEmpty() -> ErrorBox(model.error!!, { model.reload() }, "Offline files" to { model.offlineMode = true })
                    shown.isEmpty() && !model.loading -> EmptyState(
                        if (model.query.isNotBlank()) "Nothing matches" else "This folder is empty",
                        if (model.query.isBlank()) "Tap Add to upload files or make a folder." else "")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.key }) { e ->
                            EntryRow(e, model.selected.contains(e.key), selecting, pinned = OfflineHelper.covered(pins, model.bucket, e.key), isVault = model.vaults.any { it.bucket == model.bucket && it.prefix == e.key }, inVault = model.vault != null, canLink = g.settings.has("share"), canVersions = g.settings.has("versions"),
                                onClick = {
                                    when {
                                        selecting -> model.toggle(e.key)
                                        e.isDir -> model.open(e.path)
                                        Viewer.kind(e.name, e.size) != null -> viewing = e
                                        else -> { details = e; model.loadPreview(e) }
                                    }
                                },
                                onLong = { model.toggle(e.key) },
                                onAction = { action ->
                                    when (action) {
                                        EntryAction.OPEN_WITH -> model.openWith(e)
                                        EntryAction.DOWNLOAD -> model.download(listOf(e))
                                        EntryAction.SHARE -> model.share(listOf(e))
                                        EntryAction.RENAME -> renaming = e
                                        EntryAction.MOVE -> moving = listOf(e)
                                        EntryAction.DETAILS -> { details = e; model.loadPreview(e) }
                                        EntryAction.DELETE -> deleting = listOf(e)
                                        EntryAction.OFFLINE -> model.togglePin(e, OfflineHelper.covered(pins, model.bucket, e.key))
                                        EntryAction.LINK -> linking = e
                                        EntryAction.HISTORY -> history = e
                                        EntryAction.VAULT_ON -> useVault = e
                                        EntryAction.VAULT_OFF -> removeVault = model.vaults.firstOrNull { it.bucket == model.bucket && it.prefix == e.key }
                                    }
                                })
                        }
                    }
                }
            }
        }
    }

    viewing?.let { e ->
        val kind = Viewer.kind(e.name, e.size)
        if (kind != null) ViewerScreen(model, e, kind, onClose = { viewing = null }, onShare = { model.share(listOf(e)) }, onDownload = { model.download(listOf(e)) })
    }

    history?.let { e -> VersionsDialog(model.bucket, e.key, e.name, onRestored = { n -> model.restored(n) }, onDismiss = { history = null }) }
    linking?.let { e -> ShareLinkDialog(model.bucket, e.key, e.name, onDismiss = { linking = null }) }
    if (newVault) PasswordDialog("New vault", askName = true, confirmPassword = true, confirm = "Create",
        note = "Files in a vault are encrypted on this phone before they are uploaded. If you lose the password, the files can't be recovered.",
        onDismiss = { newVault = false }, onConfirm = { name, pw -> model.createVault(name, pw) })
    useVault?.let { e ->
        PasswordDialog("Use ${e.name} as a vault", askName = false, confirmPassword = false, confirm = "Use",
            note = "The folder must already be encrypted with this password (by this app or by rclone).",
            onDismiss = { useVault = null }, onConfirm = { _, pw -> model.useAsVault(e, pw) })
    }
    if (unlockVault) model.locked?.let { v ->
        PasswordDialog("Unlock ${v.name}", askName = false, confirmPassword = false, confirm = "Unlock",
            onDismiss = { unlockVault = false }, onConfirm = { _, pw -> model.unlock(v, pw) })
    }
    removeVault?.let { v ->
        ConfirmDialog("Remove vault link", "${v.name} stays on the server, still encrypted. This phone forgets its password.", "Remove",
            danger = true, onDismiss = { removeVault = null }, onConfirm = { model.removeVault(v) })
    }
    if (newFolder) TextInputDialog("New folder", "Folder name", confirm = "Create", validate = { Keys.problem(it) },
        onDismiss = { newFolder = false }, onConfirm = { model.newFolder(it) })
    renaming?.let { e ->
        TextInputDialog("Rename", "New name", initial = e.name, confirm = "Rename", validate = { Keys.problem(it) },
            onDismiss = { renaming = null }, onConfirm = { model.rename(e, it) })
    }
    deleting?.let { list ->
        val text = if (list.size == 1) "Delete ${list[0].name}${if (list[0].isDir) " and everything in it" else ""}?"
        else "Delete ${list.size} items${if (list.any { it.isDir }) ", including the contents of folders" else ""}?"
        ConfirmDialog("Delete", text, "Delete", danger = true, onDismiss = { deleting = null }, onConfirm = { model.delete(list) })
    }
    moving?.let { list ->
        FolderPickerDialog(model, list, onDismiss = { moving = null }, onPick = { model.move(list, it) })
    }
    details?.let { e ->
        DetailsSheet(model, e, onDismiss = { details = null; model.clearPreview() },
            onAction = { a ->
                details = null
                model.clearPreview()
                when (a) {
                    EntryAction.OPEN_WITH -> model.openWith(e)
                    EntryAction.DOWNLOAD -> model.download(listOf(e))
                    EntryAction.SHARE -> model.share(listOf(e))
                    EntryAction.RENAME -> renaming = e
                    EntryAction.MOVE -> moving = listOf(e)
                    EntryAction.DELETE -> deleting = listOf(e)
                    EntryAction.DETAILS -> {}
                    EntryAction.OFFLINE -> model.togglePin(e, OfflineHelper.covered(pins, model.bucket, e.key))
                    EntryAction.LINK -> linking = e
                    EntryAction.HISTORY -> history = e
                    EntryAction.VAULT_ON, EntryAction.VAULT_OFF -> {}
                }
            })
    }
}

enum class EntryAction { OPEN_WITH, DOWNLOAD, SHARE, RENAME, MOVE, DETAILS, DELETE, OFFLINE, VAULT_ON, VAULT_OFF, LINK, HISTORY }

@Composable
private fun Breadcrumbs(model: BrowseModel) {
    val parts = model.prefix.trimEnd('/').split('/').filter { it.isNotEmpty() }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        item { AssistChip(onClick = { model.open("") }, label = { Text(bucketLabel(model.bucket)) }) }
        items(parts.size) { i ->
            val target = parts.take(i + 1).joinToString("/") + "/"
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(onClick = { model.open(target) }, label = { Text(parts[i]) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: Entry, selected: Boolean, selecting: Boolean, pinned: Boolean, isVault: Boolean, inVault: Boolean, canLink: Boolean, canVersions: Boolean, onClick: () -> Unit, onLong: () -> Unit, onAction: (EntryAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (!e.isDir) Text(bytes(e.size) + if (e.modified > 0) "  ·  " + dateTime(e.modified / 1000.0) else "")
            else if (isVault) Text("Vault: encrypted on this phone")
            else if (pinned) Text("Kept offline")
        },
        leadingContent = {
            Box {
                Icon(if (isVault) Icons.Filled.Lock else iconFor(e.name, e.isDir), null, tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (pinned) Icon(Icons.Filled.OfflinePin, "Kept offline", Modifier.size(14.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.tertiary)
            }
        },
        trailingContent = {
            if (!selecting) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    if (!e.isDir) DropdownMenuItem(text = { Text("Open with") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                        onClick = { menu = false; onAction(EntryAction.OPEN_WITH) })
                    DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; onAction(EntryAction.DOWNLOAD) })
                    if (!inVault) DropdownMenuItem(text = { Text(if (pinned) "Remove offline copy" else "Make available offline") }, leadingIcon = { Icon(Icons.Filled.OfflinePin, null) },
                        onClick = { menu = false; onAction(EntryAction.OFFLINE) })
                    if (e.isDir && !inVault && !isVault) DropdownMenuItem(text = { Text("Use as vault") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                        onClick = { menu = false; onAction(EntryAction.VAULT_ON) })
                    if (isVault) DropdownMenuItem(text = { Text("Remove vault link") }, leadingIcon = { Icon(Icons.Filled.LockOpen, null) },
                        onClick = { menu = false; onAction(EntryAction.VAULT_OFF) })
                    if (!e.isDir) DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onAction(EntryAction.SHARE) })
                    if (!e.isDir && !inVault && canVersions) DropdownMenuItem(text = { Text("Version history") }, leadingIcon = { Icon(Icons.Filled.History, null) }, onClick = { menu = false; onAction(EntryAction.HISTORY) })
                    if (!e.isDir && !inVault && canLink) DropdownMenuItem(text = { Text("Create link") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onAction(EntryAction.LINK) })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { menu = false; onAction(EntryAction.RENAME) })
                    DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) }, onClick = { menu = false; onAction(EntryAction.MOVE) })
                    if (!e.isDir) DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menu = false; onAction(EntryAction.DETAILS) })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onAction(EntryAction.DELETE) })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLong),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(model: BrowseModel, e: Entry, onDismiss: () -> Unit, onAction: (EntryAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            val preview = model.previewFile
            if (isImage(e.name) && preview != null) {
                AsyncImage(model = preview, contentDescription = e.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(220.dp).padding(bottom = 12.dp))
            } else {
                Icon(iconFor(e.name, false), null, Modifier.size(48.dp).padding(bottom = 8.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(e.name, style = MaterialTheme.typography.titleMedium)
            Text(bucketLabel(model.bucket) + " / " + Keys.parent(e.path), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Fact("Size", bytes(e.size))
            Fact("Modified", if (e.modified > 0) dateTime(e.modified / 1000.0) else "unknown")
            if (!e.etag.isNullOrBlank() && model.vault == null) Fact("Checksum", e.etag.trim('"'))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAction(EntryAction.OPEN_WITH) }) { Text("Open") }
                TextButton(onClick = { onAction(EntryAction.DOWNLOAD) }) { Text("Download") }
                TextButton(onClick = { onAction(EntryAction.SHARE) }) { Text("Share") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAction(EntryAction.RENAME) }) { Text("Rename") }
                TextButton(onClick = { onAction(EntryAction.MOVE) }) { Text("Move") }
                TextButton(onClick = { onAction(EntryAction.DELETE) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.3f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
    }
}

/** Pick a folder of the current bucket to move items into. */
@Composable
private fun FolderPickerDialog(model: BrowseModel, moving: List<Entry>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<Entry>?>(null) }
    val blocked = moving.filter { it.isDir }.map { it.key }.toSet()
    LaunchedEffect(current) {
        folders = null
        folders = runCatching { model.foldersOf(model.bucket, current) }.getOrDefault(emptyList())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (moving.size == 1) "Move ${moving[0].name}" else "Move ${moving.size} items") },
        text = {
            Column {
                Text(bucketLabel(model.bucket) + if (current.isEmpty()) "" else " / " + current.trimEnd('/').replace("/", " / "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.height(280.dp)) {
                    if (current.isNotEmpty()) item {
                        ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                            modifier = Modifier.combinedClickableCompat { current = Keys.parent(current) })
                    }
                    val list = folders
                    if (list == null) item { Text("Loading…", Modifier.padding(16.dp)) }
                    else items(list.filter { it.key !in blocked }, key = { it.key }) { f ->
                        ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(iconFor(f.name, true), null) },
                            modifier = Modifier.combinedClickableCompat { current = f.key })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onPick(current) }) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

@Composable
private fun OfflineList(files: List<OfflineFile>, onOpen: (OfflineFile) -> Unit) {
    if (files.isEmpty()) {
        EmptyState("Nothing is kept offline", "Open the menu of a file or folder and choose Make available offline.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(files, key = { it.id }) { f ->
            ListItem(
                headlineContent = { Text(Keys.name(f.key), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(bucketLabel(f.bucket) + " / " + Keys.parent(f.key) + "  ·  " + bytes(f.size)) },
                leadingContent = { Icon(iconFor(f.key, false), null) },
                modifier = Modifier.combinedClickableCompat { onOpen(f) },
            )
        }
    }
}

/** Search over the whole drive, from the list kept on the phone. */
@Composable
private fun GlobalSearch(model: BrowseModel, onOpen: (String, String) -> Unit) {
    var results by remember { mutableStateOf<List<IndexEntry>>(emptyList()) }
    LaunchedEffect(model.query) { results = model.searchAll(model.query) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Searching the whole drive", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.refreshIndex() }) { Text("Update list") }
        }
        if (model.query.isBlank()) EmptyState("Type to search", "Searches the names of every file in your drive, also without a connection.")
        else if (results.isEmpty()) EmptyState("Nothing matches")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(bucketLabel(r.bucket) + " / " + Keys.parent(r.key) + "  ·  " + bytes(r.size)) },
                    leadingContent = { Icon(iconFor(r.name, false), null) },
                    modifier = Modifier.combinedClickableCompat { onOpen(r.bucket, r.key) },
                )
            }
        }
    }
}

/** Asks for a vault password (and a name for a new vault, and the password twice when it is being chosen). */
@Composable
private fun PasswordDialog(title: String, askName: Boolean, confirmPassword: Boolean, confirm: String, note: String = "",
                           onDismiss: () -> Unit, onConfirm: (name: String, password: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val nameProblem = if (askName && name.isNotBlank()) Keys.problem(name.trim()) else null
    val mismatch = confirmPassword && pw2.isNotEmpty() && pw != pw2
    val valid = (!askName || (name.isNotBlank() && nameProblem == null)) && pw.length >= (if (confirmPassword) 8 else 1) && (!confirmPassword || pw == pw2)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (askName) OutlinedTextField(name, { name = it }, label = { Text("Vault name") }, singleLine = true, isError = nameProblem != null,
                    supportingText = { if (nameProblem != null) Text(nameProblem) })
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    supportingText = { if (confirmPassword) Text("At least 8 characters") })
                if (confirmPassword) OutlinedTextField(pw2, { pw2 = it }, label = { Text("Password again") }, singleLine = true, isError = mismatch,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    supportingText = { if (mismatch) Text("The passwords differ") })
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(name.trim(), pw) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
