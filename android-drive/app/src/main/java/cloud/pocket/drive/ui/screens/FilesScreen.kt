package cloud.pocket.drive.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.data.DEVICE
import cloud.pocket.drive.data.Entry
import cloud.pocket.drive.data.IndexEntry
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.Local
import cloud.pocket.drive.data.OfflineFile
import cloud.pocket.drive.data.OfflineHelper
import cloud.pocket.drive.data.SHARED
import cloud.pocket.drive.data.Vault
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.count
import cloud.pocket.drive.data.dateTime
import cloud.pocket.drive.ui.components.Badge
import cloud.pocket.drive.ui.components.Chevron
import cloud.pocket.drive.ui.components.ConfirmDialog
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.ErrorBox
import cloud.pocket.drive.ui.components.FileTile
import cloud.pocket.drive.ui.components.ItemRow
import cloud.pocket.drive.ui.components.Meter
import cloud.pocket.drive.ui.components.Mono
import cloud.pocket.drive.ui.components.PCard
import cloud.pocket.drive.ui.components.PSwitch
import cloud.pocket.drive.ui.components.PlainButton
import cloud.pocket.drive.ui.components.SearchField
import cloud.pocket.drive.ui.components.Segmented
import cloud.pocket.drive.ui.components.SheetAction
import cloud.pocket.drive.ui.components.TextInputDialog
import cloud.pocket.drive.ui.components.Tone
import cloud.pocket.drive.ui.components.TwoLine
import cloud.pocket.drive.ui.components.isImage
import cloud.pocket.drive.ui.theme.PocketTheme
import coil3.compose.AsyncImage

/** Files: one folder of My Drive or This device, search, select, the + sheet and each file's action sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(snackbar: SnackbarHostState, model: BrowseModel) {
    val context = LocalContext.current
    val g = (context.applicationContext as cloud.pocket.drive.DriveApplication).graph
    val shown = model.visible()
    val selecting = model.selectMode || model.selected.isNotEmpty()
    val pins by g.db.offline().observePins().collectAsState(initial = emptyList())
    val offlineFiles by g.db.offline().observeFiles().collectAsState(initial = emptyList())

    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<List<Entry>?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var moving by remember { mutableStateOf<List<Entry>?>(null) }
    var sheet by remember { mutableStateOf<Entry?>(null) }
    var viewing by remember { mutableStateOf<Entry?>(null) }
    var newVault by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<Entry?>(null) }
    var history by remember { mutableStateOf<Entry?>(null) }
    var useVault by remember { mutableStateOf<Entry?>(null) }
    var unlockVault by remember { mutableStateOf(false) }
    var removeVault by remember { mutableStateOf<Vault?>(null) }
    var searchFocused by remember { mutableStateOf(false) }

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

    BackHandler(enabled = selecting || model.query.isNotEmpty() || model.prefix.isNotEmpty() || model.offlineMode || model.globalSearch || model.trashMode) {
        when {
            model.trashMode -> model.trashMode = false
            model.offlineMode -> model.offlineMode = false
            selecting -> model.clearSelection()
            model.query.isNotEmpty() || model.globalSearch -> { model.query = ""; model.globalSearch = false; model.searching = false }
            else -> model.up()
        }
    }

    fun act(e: Entry, a: EntryAction) {
        val pinned = OfflineHelper.covered(pins, model.bucket, e.key)
        when (a) {
            EntryAction.VIEW -> if (Viewer.kind(e.name, e.size) != null) viewing = e else model.openWith(e)
            EntryAction.OPEN_WITH -> model.openWith(e)
            EntryAction.DOWNLOAD -> model.download(listOf(e))
            EntryAction.SHARE -> model.share(listOf(e))
            EntryAction.RENAME -> renaming = e
            EntryAction.MOVE -> moving = listOf(e)
            EntryAction.DELETE -> deleting = listOf(e)
            EntryAction.OFFLINE -> model.togglePin(e, pinned)
            EntryAction.LINK -> linking = e
            EntryAction.HISTORY -> history = e
            EntryAction.VAULT_ON -> useVault = e
            EntryAction.VAULT_OFF -> removeVault = model.vaults.firstOrNull { it.bucket == model.bucket && it.prefix == e.key }
        }
    }

    val title = when {
        model.trashMode -> "Trash"
        model.offlineMode -> "Kept offline"
        model.prefix.isEmpty() -> "Files"
        else -> Keys.name(model.prefix)
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- header
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!model.trashMode && !model.offlineMode && !model.globalSearch)
                    PlainButton(if (selecting) "Done" else "Select", { if (selecting) model.clearSelection() else model.selectMode = true }, small = true)
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(moreMenu, { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Refresh") }, onClick = { moreMenu = false; model.reload() })
                        DropdownMenuItem(text = { Text(if (model.offlineMode) "Back to the drive" else "Files kept offline") },
                            onClick = { moreMenu = false; model.trashMode = false; model.offlineMode = !model.offlineMode })
                        if (g.settings.has("versions")) DropdownMenuItem(text = { Text(if (model.trashMode) "Back to the drive" else "Trash") },
                            onClick = { moreMenu = false; model.offlineMode = false; model.trashMode = !model.trashMode })
                        if (selecting) DropdownMenuItem(text = { Text("Select all") }, onClick = { moreMenu = false; model.selectAll() })
                        DropdownMenuItem(text = { Text("Update the search list") }, onClick = { moreMenu = false; model.refreshIndex() })
                    }
                }
            }
            if (!model.trashMode && !model.offlineMode) {
                SearchField(model.query, { model.query = it; if (it.isEmpty()) model.globalSearch = false },
                    if (model.globalSearch) "Search the whole drive" else "Search the whole drive or this folder", onFocus = { searchFocused = it })
                if (model.query.isNotEmpty() || searchFocused) Segmented(listOf("Whole drive", "This folder"), if (model.globalSearch) 0 else 1,
                    { model.globalSearch = it == 0 })
                else Segmented(listOf("My Drive", "This device"), if (model.bucket == SHARED) 0 else 1, { model.showBucket(if (it == 0) SHARED else DEVICE) })
            }
            if (!model.globalSearch && !model.offlineMode && !model.trashMode) PathLine(model) {
                Box {
                    PlainButton(model.sort.short + if (model.ascending) " ↑" else " ↓", { sortMenu = true }, small = true)
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
            }
            model.busy?.let { b ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(b, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Meter(null, height = 4.dp)
                }
            }
        }

        // ---------------------------------------------------------------- body
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                model.trashMode -> Column(Modifier.padding(top = 8.dp)) { TrashList(model.bucket, onRestored = { n -> model.restored(n) }) }
                model.offlineMode -> OfflineList(offlineFiles, onOpen = { model.openLocal(it.path, Keys.name(it.key)) })
                model.globalSearch && model.query.isNotBlank() -> GlobalSearch(model, onOpen = { b, k -> model.goTo(b, k); model.query = "" })
                else -> PullToRefreshBox(isRefreshing = model.loading, onRefresh = { model.reload() }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 96.dp)) {
                        when {
                            model.locked != null -> item {
                                PCard { ErrorBox("This vault's password isn't saved on this phone.", { unlockVault = true }, "Offline files" to { model.offlineMode = true }) }
                            }
                            model.error != null && model.entries.isEmpty() -> item {
                                PCard { ErrorBox(model.error!!, { model.reload() }, "Offline files" to { model.offlineMode = true }) }
                            }
                            shown.isEmpty() && !model.loading -> item {
                                PCard {
                                    EmptyState(if (model.query.isNotBlank()) "Nothing here matches" else "This folder is empty",
                                        if (model.query.isBlank()) "Tap + to add files." else "")
                                }
                            }
                            else -> {
                                // One card holding all rows: each row draws its part of the card.
                                items(shown, key = { it.key }) { e ->
                                    val first = e === shown.first()
                                    val last = e === shown.last()
                                    CardSlice(first, last) {
                                        EntryRow(e, selected = e.key in model.selected, selecting = selecting,
                                            pinned = OfflineHelper.covered(pins, model.bucket, e.key),
                                            isVault = model.vaults.any { it.bucket == model.bucket && it.prefix == e.key },
                                            onClick = {
                                                when {
                                                    selecting -> model.toggle(e.key)
                                                    e.isDir -> model.open(e.path)
                                                    else -> { sheet = e; model.loadPreview(e) }
                                                }
                                            },
                                            onLong = { model.selectMode = true; model.toggle(e.key) },
                                            onMore = { sheet = e; if (!e.isDir) model.loadPreview(e) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selecting) {
            val sel = model.selectedEntries()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (sel.isEmpty()) "Tap files to pick them" else "${count(sel.size)} picked", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlainButton("Download", { model.download(sel) }, Modifier.weight(1f), small = true, enabled = sel.isNotEmpty())
                    PlainButton("Share", { model.share(sel) }, Modifier.weight(1f), small = true, enabled = sel.isNotEmpty())
                    PlainButton("Move", { moving = sel }, Modifier.weight(1f), small = true, enabled = sel.isNotEmpty())
                    PlainButton("Delete", { deleting = sel }, Modifier.weight(1f), small = true, danger = true, enabled = sel.isNotEmpty())
                }
            }
        }
    }

    viewing?.let { e ->
        val kind = Viewer.kind(e.name, e.size)
        if (kind != null) ViewerScreen(model, e, kind, onClose = { viewing = null }, onShare = { model.share(listOf(e)) }, onDownload = { model.download(listOf(e)) })
    }

    if (model.addSheet) AddSheet(model, onDismiss = { model.addSheet = false },
        onFiles = { pickFiles.launch(arrayOf("*/*")) }, onFolder = { pickFolder.launch(null) }, onNewFolder = { newFolder = true }, onNewVault = { newVault = true })

    sheet?.let { e ->
        FileSheet(model, e, pinned = OfflineHelper.covered(pins, model.bucket, e.key),
            isVault = model.vaults.any { it.bucket == model.bucket && it.prefix == e.key },
            canLink = g.settings.has("share"), canVersions = g.settings.has("versions"),
            onDismiss = { sheet = null; model.clearPreview() },
            onAction = { a -> sheet = null; model.clearPreview(); act(e, a) })
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
}

enum class EntryAction { VIEW, OPEN_WITH, DOWNLOAD, SHARE, RENAME, MOVE, DELETE, OFFLINE, VAULT_ON, VAULT_OFF, LINK, HISTORY }

/** The line under the search: back and the path (each part tappable), or what this space is; the sort button at its end. */
@Composable
private fun PathLine(model: BrowseModel, trailing: @Composable () -> Unit) {
    val parts = model.prefix.trimEnd('/').split('/').filter { it.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (parts.isEmpty()) Text(if (model.bucket == SHARED) "Shared by all your devices" else "Only this phone sees these",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text("‹ Back", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.up() }.padding(vertical = 6.dp, horizontal = 2.dp))
                Text(" · ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())) {
                    Text(bucketLabel(model.bucket), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { model.open("") })
                    parts.forEachIndexed { i, p ->
                        Text(" / ", style = MaterialTheme.typography.bodyMedium, color = PocketTheme.extra.faint)
                        val target = parts.take(i + 1).joinToString("/") + "/"
                        Text(p, style = MaterialTheme.typography.bodyMedium,
                            color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, modifier = Modifier.clickable { model.open(target) })
                    }
                }
            }
        }
        trailing()
    }
}


/** One row's slice of a card: the card's border, rounded at the first and last row, a divider between rows. */
@Composable
private fun CardSlice(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val r = 18.dp
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp, bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surfaceContainerLow)
            .sliceBorder(c.outlineVariant, first, last, r),
    ) {
        if (!first) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = c.outlineVariant, thickness = 1.dp)
        Box(Modifier.padding(start = 16.dp, end = 8.dp, top = if (first) 12.dp else 9.dp, bottom = if (last) 12.dp else 9.dp)) { content() }
    }
}


/** The card's outline drawn per slice: sides always, top on the first, bottom on the last, corners rounded there. */
private fun Modifier.sliceBorder(color: androidx.compose.ui.graphics.Color, first: Boolean, last: Boolean, radius: androidx.compose.ui.unit.Dp) =
    this.drawWithContent {
        drawContent()
        val w = 1.dp.toPx()
        val rr = radius.toPx()
        val path = androidx.compose.ui.graphics.Path()
        val h = size.height
        val x1 = size.width - w / 2
        val x0 = w / 2
        // left side
        path.moveTo(x0, if (first) rr else 0f)
        path.lineTo(x0, if (last) h - rr else h)
        if (last) {
            path.arcTo(androidx.compose.ui.geometry.Rect(x0, h - 2 * rr + w / 2, x0 + 2 * rr, h - w / 2), 180f, -90f, false)
            path.lineTo(x1 - rr, h - w / 2)
            path.arcTo(androidx.compose.ui.geometry.Rect(x1 - 2 * rr, h - 2 * rr + w / 2, x1, h - w / 2), 90f, -90f, false)
        } else path.moveTo(x1, h)
        path.lineTo(x1, if (first) rr else 0f)
        if (first) {
            path.arcTo(androidx.compose.ui.geometry.Rect(x1 - 2 * rr, w / 2, x1, 2 * rr + w / 2), 0f, -90f, false)
            path.lineTo(x0 + rr, w / 2)
            path.arcTo(androidx.compose.ui.geometry.Rect(x0, w / 2, x0 + 2 * rr, 2 * rr + w / 2), 270f, -90f, false)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w))
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: Entry, selected: Boolean, selecting: Boolean, pinned: Boolean, isVault: Boolean,
                     onClick: () -> Unit, onLong: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLong),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting && !e.isDir) Checkbox(selected, { onClick() }, Modifier.size(24.dp),
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
        FileTile(e.name, e.isDir, vault = isVault)
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                isVault -> "Vault · encrypted on this phone"
                e.isDir -> if (pinned) "Folder · kept offline" else "Folder"
                else -> bytes(e.size) + if (e.modified > 0) " · " + Timeline.fileWhen(e.modified) else ""
            }
            Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = cloud.pocket.drive.ui.theme.TNUM),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (pinned && !e.isDir) Badge("Offline", Tone.ACCENT)
        if (selecting && e.isDir) Checkbox(selected, { onClick() }, Modifier.size(24.dp))
        else if (!selecting) {
            if (e.isDir) {
                IconButton(onClick = onMore, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Actions for ${e.name}", tint = PocketTheme.extra.faint) }
            }
        }
    }
}

/** The + sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(model: BrowseModel, onDismiss: () -> Unit, onFiles: () -> Unit, onFolder: () -> Unit, onNewFolder: () -> Unit, onNewVault: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Add to " + (if (model.prefix.isEmpty()) bucketLabel(model.bucket) else Keys.name(model.prefix)),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SheetAction("Upload files", Icons.Filled.UploadFile, { onDismiss(); onFiles() })
            SheetAction("Upload a folder", Icons.Filled.FolderOpen, { onDismiss(); onFolder() })
            SheetAction("New folder", Icons.Filled.CreateNewFolder, { onDismiss(); onNewFolder() })
            if (model.vault == null) SheetAction("New vault", Icons.Filled.Lock, { onDismiss(); onNewVault() })
        }
    }
}

/** A file's (or folder's) action sheet: what it is, then everything that can be done with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheet(model: BrowseModel, e: Entry, pinned: Boolean, isVault: Boolean, canLink: Boolean, canVersions: Boolean,
                      onDismiss: () -> Unit, onAction: (EntryAction) -> Unit) {
    val inVault = model.vault != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            val preview = model.previewFile
            if (!e.isDir && isImage(e.name) && preview != null) {
                AsyncImage(model = preview, contentDescription = e.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).padding(bottom = 12.dp))
            }
            ItemRow(leading = { FileTile(e.name, e.isDir, vault = isVault) }) {
                TwoLine(e.name, bucketLabel(model.bucket) + (Keys.parent(e.path).trimEnd('/').let { if (it.isEmpty()) "" else " / $it" }),
                    titleStyle = MaterialTheme.typography.titleMedium)
            }
            if (!e.isDir) {
                Row(Modifier.padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Mono(bytes(e.size))
                    Mono(if (e.modified > 0) dateTime(e.modified / 1000.0) else "date unknown")
                    if (!e.etag.isNullOrBlank() && !inVault) Mono(e.etag.trim('"').take(12), Modifier.weight(1f, fill = false))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (e.isDir) {
                SheetAction("Open", Icons.Filled.FolderOpen, { onDismiss(); model.open(e.path) })
                SheetAction("Download to this phone", Icons.Filled.Download, { onAction(EntryAction.DOWNLOAD) })
                if (!inVault) SheetAction("Keep offline", Icons.Filled.OfflinePin, { onAction(EntryAction.OFFLINE) }) {
                    PSwitch(pinned, null)
                }
                if (!inVault && !isVault) SheetAction("Use as a vault", Icons.Filled.Lock, { onAction(EntryAction.VAULT_ON) })
                if (isVault) SheetAction("Remove the vault link", Icons.Filled.LockOpen, { onAction(EntryAction.VAULT_OFF) })
            } else {
                SheetAction("Open", Icons.Filled.Visibility, { onAction(EntryAction.VIEW) })
                SheetAction("Open with another app", Icons.AutoMirrored.Filled.OpenInNew, { onAction(EntryAction.OPEN_WITH) })
                SheetAction("Download to this phone", Icons.Filled.Download, { onAction(EntryAction.DOWNLOAD) })
                if (!inVault) SheetAction(if (pinned) "Kept offline" else "Keep offline", Icons.Filled.OfflinePin, { onAction(EntryAction.OFFLINE) }) {
                    PSwitch(pinned, null)
                }
                SheetAction("Share", Icons.Filled.Share, { onAction(EntryAction.SHARE) })
                if (!inVault && canLink) SheetAction("Share a link", Icons.Filled.Link, { onAction(EntryAction.LINK) })
            }
            SheetAction("Rename", Icons.Filled.DriveFileRenameOutline, { onAction(EntryAction.RENAME) })
            SheetAction("Move", Icons.Filled.DriveFileMove, { onAction(EntryAction.MOVE) })
            if (!e.isDir && !inVault && canVersions) SheetAction("Earlier versions", Icons.Filled.History, { onAction(EntryAction.HISTORY) })
            SheetAction("Delete", Icons.Filled.Delete, { onAction(EntryAction.DELETE) }, danger = true)
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (moving.size == 1) "Move ${moving[0].name}" else "Move ${moving.size} items") },
        text = {
            Column {
                Text(bucketLabel(model.bucket) + if (current.isEmpty()) "" else " / " + current.trimEnd('/').replace("/", " / "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.height(280.dp).padding(top = 8.dp)) {
                    if (current.isNotEmpty()) item {
                        ItemRow(Modifier.padding(vertical = 4.dp), onClick = { current = Keys.parent(current) }, leading = { FileTile("..", true) }) { TwoLine("Up one folder", null) }
                    }
                    val list = folders
                    if (list == null) item { Text("Loading…", Modifier.padding(16.dp)) }
                    else items(list.filter { it.key !in blocked }, key = { it.key }) { f ->
                        ItemRow(Modifier.padding(vertical = 4.dp), onClick = { current = f.key }, leading = { FileTile(f.name, true) }, trailing = { Chevron() }) {
                            TwoLine(f.name, null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onPick(current) }) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OfflineList(files: List<OfflineFile>, onOpen: (OfflineFile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 96.dp)) {
        if (files.isEmpty()) item {
            PCard { EmptyState("Nothing is kept offline", "Open a file's actions and switch on Keep offline.") }
        }
        items(files, key = { it.id }) { f ->
            CardSlice(f === files.first(), f === files.last()) {
                ItemRow(onClick = { onOpen(f) }, leading = { FileTile(Keys.name(f.key), false) }, trailing = { Badge("On this phone", Tone.ACCENT) }) {
                    TwoLine(Keys.name(f.key), bucketLabel(f.bucket) + " / " + Keys.parent(f.key) + " · " + bytes(f.size))
                }
            }
        }
    }
}

/** Search over the whole drive, from the list of names kept on the phone. */
@Composable
private fun GlobalSearch(model: BrowseModel, onOpen: (String, String) -> Unit) {
    var results by remember { mutableStateOf<List<IndexEntry>>(emptyList()) }
    LaunchedEffect(model.query) { results = model.searchAll(model.query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 96.dp)) {
        item {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Names of every file in your drive, also without a connection.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.refreshIndex() }) { Text("Update list") }
            }
        }
        if (results.isEmpty()) item { PCard { EmptyState("Nothing matches") } }
        items(results, key = { it.id }) { r ->
            CardSlice(r === results.first(), r === results.last()) {
                ItemRow(onClick = { onOpen(r.bucket, r.key) }, leading = { FileTile(r.name, false) }) {
                    TwoLine(r.name, bucketLabel(r.bucket) + " / " + Keys.parent(r.key) + " · " + bytes(r.size))
                }
            }
        }
    }
}

/** Asks for a vault password (and a name for a new vault, and the password twice when it is being chosen). */
@Composable
internal fun PasswordDialog(title: String, askName: Boolean, confirmPassword: Boolean, confirm: String, note: String = "",
                            onDismiss: () -> Unit, onConfirm: (name: String, password: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val nameProblem = if (askName && name.isNotBlank()) Keys.problem(name.trim()) else null
    val mismatch = confirmPassword && pw2.isNotEmpty() && pw != pw2
    val valid = (!askName || (name.isNotBlank() && nameProblem == null)) && pw.length >= (if (confirmPassword) 8 else 1) && (!confirmPassword || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
