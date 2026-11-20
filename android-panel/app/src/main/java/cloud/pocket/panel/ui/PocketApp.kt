package cloud.pocket.panel.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import cloud.pocket.panel.data.AlertWorker
import cloud.pocket.panel.data.ApiException
import cloud.pocket.panel.data.PocketApi
import cloud.pocket.panel.data.Session
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.NavIcons
import cloud.pocket.panel.ui.components.Panel
import cloud.pocket.panel.ui.screens.AlertsScreen
import cloud.pocket.panel.ui.screens.AppDetailScreen
import cloud.pocket.panel.ui.screens.AppsScreen
import cloud.pocket.panel.ui.screens.DevicesScreen
import cloud.pocket.panel.ui.screens.FilesScreen
import cloud.pocket.panel.ui.screens.HomeScreen
import cloud.pocket.panel.ui.screens.JobsScreen
import cloud.pocket.panel.ui.screens.LogsScreen
import cloud.pocket.panel.ui.screens.PhoneScreen
import cloud.pocket.panel.ui.screens.SettingsScreen
import cloud.pocket.panel.ui.screens.SignInScreen
import cloud.pocket.panel.ui.theme.PocketTheme
import cloud.pocket.panel.update.Updates
import kotlinx.coroutines.delay

/** A bottom-bar tab. */
data class Tab(val route: String, val label: String, val icon: ImageVector)

val TABS = listOf(
    Tab("home", "Home", NavIcons.Home),
    Tab("apps", "Apps", NavIcons.Apps),
    Tab("files", "Files", NavIcons.Files),
    Tab("phone", "Phone", NavIcons.Phone),
    Tab("settings", "Settings", NavIcons.Settings),
)

/** The tab a page belongs to: Alerts under Home, Devices under Apps, Jobs and Logs under Phone. */
fun tabOf(route: String): String = when (route.substringBefore('/')) {
    "home", "alerts" -> "home"
    "apps", "devices" -> "apps"
    "files" -> "files"
    "phone", "jobs", "logs" -> "phone"
    "settings" -> "settings"
    else -> "home"
}

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
    // A sub-page opened from outside (an alert notification) sits on top of its tab, so back leads there.
    val stack = remember {
        val start = startRoute ?: "home"
        val tab = tabOf(start)
        if (start == tab) mutableStateListOf(tab) else mutableStateListOf(tab, start)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var alertCount by remember { mutableIntStateOf(0) }
    val panel = remember(api) {
        Panel(api, snackbar, scope,
            open = { route -> if (stack.lastOrNull() != route) stack.add(route) },
            back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
            signedOut = signedOut,
            depth = { stack.size },
            tabLabel = { TABS.first { it.route == tabOf(stack.last()) }.label })
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
    val selected = tabOf(route)

    fun go(tab: String) {
        stack.clear()
        stack.add(tab)
    }

    CompositionLocalProvider(LocalPanel provides panel) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { BottomBar(selected, alertCount, ::go) },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
                val arg = route.substringAfter('/', "").ifBlank { null }
                key(route) {
                    when (route.substringBefore('/')) {
                        "home" -> HomeScreen()
                        "alerts" -> AlertsScreen()
                        "apps" -> if (arg != null) AppDetailScreen(arg) else AppsScreen()
                        "devices" -> DevicesScreen()
                        "files" -> FilesScreen(arg)
                        "phone" -> PhoneScreen()
                        "jobs" -> JobsScreen()
                        "logs" -> LogsScreen(arg)
                        "settings" -> SettingsScreen()
                        else -> HomeScreen()
                    }
                }
            }
        }
    }
}

/** Home / Apps / Files / Phone / Settings: brand icon and accent label on the chosen tab, over an accent-wash pill. */
@Composable
private fun BottomBar(selected: String, alertCount: Int, go: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = PocketTheme.extra
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = cs.outlineVariant)
        NavigationBar(containerColor = cs.surfaceContainerHigh, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
            TABS.forEach { t ->
                NavigationBarItem(
                    selected = selected == t.route,
                    onClick = { go(t.route) },
                    icon = {
                        if (t.route == "home" && alertCount > 0) {
                            BadgedBox(badge = { Badge(containerColor = x.warn, contentColor = cs.onTertiary) { Text("$alertCount") } }) { Icon(t.icon, null) }
                        } else Icon(t.icon, null)
                    },
                    label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = x.brand, selectedTextColor = cs.primary, indicatorColor = cs.secondaryContainer,
                        unselectedIconColor = x.faint, unselectedTextColor = x.faint,
                    ),
                )
            }
        }
    }
}
