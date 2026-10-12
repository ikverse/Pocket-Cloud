package cloud.pocket.drive.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.ui.components.TransferBar
import cloud.pocket.drive.ui.screens.BrowseScreen
import cloud.pocket.drive.ui.screens.SettingsScreen
import cloud.pocket.drive.ui.screens.SetupScreen
import cloud.pocket.drive.ui.screens.SyncTab
import cloud.pocket.drive.ui.screens.TransfersScreen
import cloud.pocket.drive.update.Updates

private enum class Tab(val label: String) { BROWSE("Files"), SYNC("Sync"), TRANSFERS("Transfers"), SETTINGS("Settings") }

@Composable
fun DriveApp(open: String?) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    var enrolled by remember { mutableStateOf(g.settings.enrolled) }
    var tab by rememberSaveable { mutableStateOf(when (open) { "transfers" -> Tab.TRANSFERS; "sync" -> Tab.SYNC; else -> Tab.BROWSE }) }
    val snackbar = remember { SnackbarHostState() }

    // News from the updater: a newer build found at launch, or an install that could not start.
    // Its own host on this scaffold, so it shows whichever tab is open.
    val updateSnackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        for (message in Updates.of(context).messages) updateSnackbar.showSnackbar(message)
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(enrolled) {
        if (enrolled && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (!enrolled) {
        SetupScreen(g) { enrolled = true }
        return
    }

    val active by g.db.transfers().activeCount().collectAsState(initial = 0)
    val failed by g.db.transfers().failedCount().collectAsState(initial = 0)
    val waiting by g.db.transfers().waitingReason().collectAsState(initial = null)
    val running by g.engine.current.collectAsState()

    Scaffold(
        bottomBar = {
            Column {
                if (tab != Tab.TRANSFERS) TransferBar(running, active, failed, waiting) { tab = Tab.TRANSFERS }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                val icon = when (t) { Tab.BROWSE -> Icons.Filled.Cloud; Tab.SYNC -> Icons.Filled.Sync; Tab.TRANSFERS -> Icons.Filled.SwapVert; Tab.SETTINGS -> Icons.Filled.Settings }
                                if (t == Tab.TRANSFERS && active > 0) BadgedBox(badge = { Badge { Text("$active") } }) { Icon(icon, t.label) } else Icon(icon, t.label)
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(updateSnackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        androidx.compose.foundation.layout.Box(Modifier.padding(inner)) {
            when (tab) {
                Tab.BROWSE -> BrowseScreen(snackbar)
                Tab.SYNC -> SyncTab(snackbar)
                Tab.TRANSFERS -> TransfersScreen()
                Tab.SETTINGS -> SettingsScreen(onSignedOut = { enrolled = false })
            }
        }
    }
}
