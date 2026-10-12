package cloud.pocket.panel.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import cloud.pocket.panel.update.Updates
import cloud.pocket.panel.data.AlertWorker
import cloud.pocket.panel.data.ApiException
import cloud.pocket.panel.data.PocketApi
import cloud.pocket.panel.data.Session
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.Panel
import cloud.pocket.panel.ui.screens.AlertsScreen
import cloud.pocket.panel.ui.screens.AppDetailScreen
import cloud.pocket.panel.ui.screens.AppsScreen
import cloud.pocket.panel.ui.screens.DevicesScreen
import cloud.pocket.panel.ui.screens.FilesScreen
import cloud.pocket.panel.ui.screens.JobsScreen
import cloud.pocket.panel.ui.screens.LogsScreen
import cloud.pocket.panel.ui.screens.MoreScreen
import cloud.pocket.panel.ui.screens.OverviewScreen
import cloud.pocket.panel.ui.screens.SettingsScreen
import cloud.pocket.panel.ui.screens.SignInScreen
import cloud.pocket.panel.ui.screens.StorageScreen
import kotlinx.coroutines.delay

/** A top-level destination. */
data class Dest(val route: String, val label: String, val icon: ImageVector)

val ALL = listOf(
    Dest("overview", "Overview", Icons.Outlined.Dashboard),
    Dest("storage", "Storage", Icons.Outlined.Storage),
    Dest("apps", "Apps", Icons.Outlined.Apps),
    Dest("devices", "Devices", Icons.Outlined.Devices),
    Dest("files", "Files", Icons.Outlined.Folder),
    Dest("jobs", "Jobs", Icons.Outlined.Schedule),
    Dest("alerts", "Alerts", Icons.Outlined.NotificationsNone),
    Dest("settings", "Settings", Icons.Outlined.Settings),
    Dest("logs", "Logs", Icons.Outlined.ReceiptLong),
)
private val MORE = Dest("more", "More", Icons.Outlined.MoreHoriz)
private val ON_BAR = listOf("overview", "apps", "files", "alerts")

@Composable
fun PocketApp(startRoute: String? = null) {
    val context = LocalContext.current
    val session = remember { Session(context) }
    var api by remember { mutableStateOf(session.api()) }
    val current = api
    if (current == null) {
        SignInScreen(session) {
            api = session.api()
            AlertWorker.schedule(context)
        }
    } else {
        key(current) {
            Main(current, startRoute) {
                session.token = null
                AlertWorker.cancel(context)
                api = null
            }
        }
    }
}

@Composable
private fun Main(api: PocketApi, startRoute: String?, signedOut: () -> Unit) {
    val stack = remember { mutableStateListOf(startRoute ?: "overview") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var alertCount by remember { mutableIntStateOf(0) }
    val panel = remember(api) {
        Panel(api, snackbar, scope,
            open = { route -> if (stack.lastOrNull() != route) stack.add(route) },
            back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
            signedOut = signedOut)
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    // News from the updater: a newer build found at launch, or an install that could not start.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        for (message in Updates.of(context).messages) snackbar.showSnackbar(message)
    }

    // Ask once for permission to show alert notifications (Android 13+).
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // Keep the alerts badge current while the app is open.
    LaunchedEffect(api) {
        while (true) {
            try {
                alertCount = api.get("/api/alerts").arr("active").count { it.s("level") != "info" }
            } catch (e: ApiException) {
                if (e.status == 401) signedOut()
            }
            delay(60_000)
        }
    }

    val route = stack.last()
    val top = route.substringBefore('/')
    val layout = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val compact = layout == NavigationSuiteType.NavigationBar
    val items = if (compact) ALL.filter { it.route in ON_BAR } + MORE else ALL
    val selected = if (compact && top !in ON_BAR) "more" else top

    fun go(dest: String) {
        stack.clear()
        stack.add(dest)
    }

    CompositionLocalProvider(LocalPanel provides panel) {
        NavigationSuiteScaffold(
            layoutType = layout,
            navigationSuiteItems = {
                items.forEach { d ->
                    item(
                        selected = selected == d.route,
                        onClick = { go(d.route) },
                        icon = {
                            if (d.route == "alerts" && alertCount > 0) {
                                BadgedBox(badge = { Badge { Text("$alertCount") } }) { Icon(d.icon, null) }
                            } else Icon(d.icon, null)
                        },
                        label = { Text(d.label) },
                    )
                }
            },
        ) {
            val arg = route.substringAfter('/', "").ifBlank { null }
            key(route) {
                when (top) {
                    "overview" -> OverviewScreen()
                    "storage" -> StorageScreen()
                    "apps" -> if (arg != null) AppDetailScreen(arg) else AppsScreen()
                    "devices" -> DevicesScreen()
                    "files" -> FilesScreen(arg)
                    "jobs" -> JobsScreen()
                    "alerts" -> AlertsScreen()
                    "settings" -> SettingsScreen()
                    "logs" -> LogsScreen(arg)
                    "more" -> MoreScreen(ALL.filter { it.route !in ON_BAR })
                    else -> OverviewScreen()
                }
            }
        }
    }
}
