package cloud.pocket.drive.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.ui.components.LineIcons
import cloud.pocket.drive.ui.components.TransferBar
import cloud.pocket.drive.ui.screens.ActivityScreen
import cloud.pocket.drive.ui.screens.BrowseModel
import cloud.pocket.drive.ui.screens.FilesScreen
import cloud.pocket.drive.ui.screens.HomeScreen
import cloud.pocket.drive.ui.screens.SettingsScreen
import cloud.pocket.drive.ui.screens.SetupScreen
import cloud.pocket.drive.ui.screens.SyncTab
import cloud.pocket.drive.ui.screens.VaultScreen
import cloud.pocket.drive.ui.theme.PocketTheme
import cloud.pocket.drive.update.Updates

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", LineIcons.Home), FILES("Files", LineIcons.Files), SYNC("Sync", LineIcons.Sync),
    ACTIVITY("Activity", LineIcons.Activity), SETTINGS("Settings", LineIcons.Settings),
}

@Composable
fun DriveApp(open: String?) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    var enrolled by remember { mutableStateOf(g.settings.enrolled) }
    var tab by rememberSaveable { mutableStateOf(when (open) { "transfers" -> Tab.ACTIVITY; "sync" -> Tab.SYNC; else -> Tab.HOME }) }
    /** The Vault screen, opened from Home (the Home tab stays selected). */
    var vaultOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // News from the updater: a newer build found at launch, or an install that could not start.
    LaunchedEffect(Unit) {
        for (message in Updates.of(context).messages) snackbar.showSnackbar(message)
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(enrolled) {
        if (enrolled && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (!enrolled) {
        SetupScreen(g) { enrolled = true; tab = Tab.HOME }
        return
    }

    val browse: BrowseModel = viewModel()
    // Files' news and the apps it opens, on whichever screen asked (Files, Home, Vault).
    LaunchedEffect(browse) { browse.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(browse) {
        browse.intents.collect { runCatching { context.startActivity(it) }.onFailure { snackbar.showSnackbar("No app can open that.") } }
    }
    val active by g.db.transfers().activeCount().collectAsState(initial = 0)
    val failed by g.db.transfers().failedCount().collectAsState(initial = 0)
    val waiting by g.db.transfers().waitingReason().collectAsState(initial = null)
    val running by g.engine.current.collectAsState()

    BackHandler(enabled = tab != Tab.HOME || vaultOpen) { if (vaultOpen && tab == Tab.HOME) vaultOpen = false else tab = Tab.HOME }

    fun go(t: Tab) { if (t == Tab.HOME && tab == Tab.HOME) vaultOpen = false; tab = t }
    val showFab = tab == Tab.FILES && !(browse.selectMode || browse.selected.isNotEmpty()) && !browse.trashMode && !browse.offlineMode && !browse.globalSearch

    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (tab != Tab.ACTIVITY) TransferBar(running, active, failed, waiting) { tab = Tab.ACTIVITY }
                BottomTabs(tab, failed, ::go)
            }
        },
        floatingActionButton = {
            if (showFab) FloatingActionButton(
                onClick = { browse.addSheet = true },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(6.dp),
            ) { Icon(Icons.Filled.Add, "Upload or add", Modifier.size(28.dp)) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.statusBars,
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> if (vaultOpen) VaultScreen(browse, onBack = { vaultOpen = false }, onOpen = { v -> browse.openFolder(v.bucket, v.prefix); tab = Tab.FILES })
                else HomeScreen(browse, onTab = { tab = it }, onVault = { vaultOpen = true }, onOpenFolder = { b, p -> browse.openFolder(b, p); tab = Tab.FILES })
                Tab.FILES -> FilesScreen(snackbar, browse)
                Tab.SYNC -> SyncTab(snackbar)
                Tab.ACTIVITY -> ActivityScreen(onSync = { tab = Tab.SYNC })
                Tab.SETTINGS -> SettingsScreen(onSignedOut = { enrolled = false })
            }
        }
    }
}

/** Five tabs: 22 dp line icons, the selected one in the brand colour with its label in the accent. */
@Composable
private fun BottomTabs(current: Tab, failed: Int, onSelect: (Tab) -> Unit) {
    val c = MaterialTheme.colorScheme
    val x = PocketTheme.extra
    Column(Modifier.fillMaxWidth().background(c.surfaceContainerHigh)) {
        HorizontalDivider(color = c.outlineVariant, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)) {
            Tab.entries.forEach { t ->
                val on = t == current
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .semantics { selected = on }
                        .clickable(role = Role.Tab) { onSelect(t) }
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box {
                        Icon(t.icon, null, Modifier.size(22.dp), tint = if (on) x.brand else x.faint)
                        if (t == Tab.ACTIVITY && failed > 0) Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-3).dp).clip(RoundedCornerShape(50)).background(x.bad)
                                .padding(horizontal = 4.dp),
                        ) { Text("$failed", style = MaterialTheme.typography.labelSmall, color = c.onError) }
                    }
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) c.primary else x.faint)
                }
            }
        }
    }
}
