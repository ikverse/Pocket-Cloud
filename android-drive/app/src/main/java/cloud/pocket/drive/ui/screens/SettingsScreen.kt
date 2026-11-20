package cloud.pocket.drive.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cloud.pocket.drive.R
import cloud.pocket.drive.data.Quota
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.Share
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.push.Push
import cloud.pocket.drive.data.sync.MediaScanner
import cloud.pocket.drive.data.sync.Net
import cloud.pocket.drive.shizuku.ShizukuAccess
import cloud.pocket.drive.shizuku.ShizukuState
import cloud.pocket.drive.ui.components.Badge
import cloud.pocket.drive.ui.components.ConfirmDialog
import cloud.pocket.drive.ui.components.Divided
import cloud.pocket.drive.ui.components.ItemRow
import cloud.pocket.drive.ui.components.Meter
import cloud.pocket.drive.ui.components.Mono
import cloud.pocket.drive.ui.components.PCard
import cloud.pocket.drive.ui.components.PSwitch
import cloud.pocket.drive.ui.components.PlainButton
import cloud.pocket.drive.ui.components.ScreenPadding
import cloud.pocket.drive.ui.components.ScreenTitle
import cloud.pocket.drive.ui.components.SectionHeader
import cloud.pocket.drive.ui.components.TextInputDialog
import cloud.pocket.drive.ui.components.Tone
import cloud.pocket.drive.ui.components.TwoLine
import cloud.pocket.drive.ui.theme.PocketTheme
import cloud.pocket.drive.ui.theme.TNUM
import cloud.pocket.drive.update.UpdateControls
import cloud.pocket.drive.update.Updates
import kotlinx.coroutines.launch

/** One line of a settings card: title, text, and what sits at its end. */
private class Line(val title: String, val text: String?, val mono: Boolean = false, val bad: Boolean = false, val end: @Composable () -> Unit = {})

@Composable
private fun LinesCard(title: String, lines: List<Line>) {
    PCard {
        SectionHeader(title)
        Divided(lines) { l ->
            ItemRow(Modifier.heightIn(min = 44.dp), trailing = { l.end() }) {
                Column {
                    Text(l.title, style = MaterialTheme.typography.titleSmall)
                    if (!l.text.isNullOrEmpty()) {
                        if (l.mono) Mono(l.text) else Text(l.text, style = MaterialTheme.typography.bodyMedium,
                            color = if (l.bad) PocketTheme.extra.bad else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    var signOut by remember { mutableStateOf(false) }
    var reconnect by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var licences by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var deviceName by remember { mutableStateOf(g.settings.deviceName) }
    var endpoint by remember { mutableStateOf(g.settings.endpointInUse) }
    var features by remember { mutableStateOf(g.settings.features) }
    var quota by remember { mutableStateOf<Quota?>(null) }
    LaunchedEffect(features) { quota = g.server.quota() }
    var shares by remember { mutableStateOf<List<Share>>(emptyList()) }
    LaunchedEffect(features) { shares = if (g.settings.has("share")) runCatching { g.server.shares() }.getOrDefault(emptyList()) else emptyList() }
    var pushOn by remember { mutableStateOf(Push.enabled(g)) }
    var pushNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        connected = try { endpoint = g.remote.connect(); features = g.settings.features; true } catch (e: Exception) { test = Remote.explain(e); false }
    }

    // permissions, re-read whenever the screen comes back from a system page
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val media = remember(tick) { MediaScanner.hasPermission(context) }
    val allFiles = remember(tick) { Net.allFilesAccess() }
    val notifications = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val battery = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val pkg = Uri.parse("package:" + context.packageName)
    val shizuku = remember(tick) { mutableStateOf(ShizukuAccess.state()) }

    val allowed: @Composable () -> Unit = { Badge("Allowed", Tone.OK) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Settings")

        LinesCard("Connection", buildList {
            add(Line(g.settings.endpoint().substringAfter("://").substringBefore(':'), g.settings.endpoint(), mono = true) {
                when (connected) {
                    true -> Badge("Connected", Tone.OK)
                    false -> Badge("Not connected", Tone.BAD)
                    null -> Badge("Checking")
                }
            })
            add(Line("Connected through", endpoint.ifBlank { "not connected yet" }, mono = endpoint.isNotBlank()))
            if (test != null && connected != true) add(Line("Last try", test, bad = true))
            add(Line("Test the connection", "Tailscale must be on") {
                PlainButton("Test", {
                    connected = null
                    scope.launch {
                        connected = try { endpoint = g.remote.connect(); features = g.settings.features; test = null; true }
                        catch (e: Exception) { test = Remote.explain(e); false }
                    }
                }, small = true)
            })
            add(Line("This device", deviceName) { PlainButton("Rename", { rename = true }, small = true) })
            if (g.settings.serverVersion.isNotBlank()) add(Line("Server version", g.settings.serverVersion, mono = true))
            add(Line("Server features", if (features.isEmpty()) "Basic (storage only)" else features.sorted().joinToString(", ")))
        })

        quota?.let { q ->
            PCard(gap = 8.dp) {
                SectionHeader("Space", Modifier.padding(bottom = 0.dp))
                Meter(if (q.allocation > 0) q.usedApp.toFloat() / q.allocation else null)
                Text("${bytes(q.usedApp)} of ${bytes(q.allocation)} used · ${bytes(q.free)} free for this device",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LinesCard("Permissions", buildList {
            add(Line("Photos and videos", "Needed for camera backup and photo folders") {
                if (media) allowed() else PlainButton("Allow", { askMedia.launch(MediaScanner.permissions()) }, small = true)
            })
            add(Line("All files", if (Net.folderSyncSupported()) "Only needed to sync folders that are not media"
                else "Not available before Android 11: folders can't be synced on this phone") {
                if (allFiles || !Net.folderSyncSupported()) { if (allFiles) allowed() } else PlainButton("Allow", {
                    context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
                }, small = true)
            })
            add(Line("Notifications", "Progress, failed transfers and sync questions") {
                if (notifications) allowed() else PlainButton("Allow", { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }, small = true)
            })
            add(Line("Run in the background", "Lets sync keep going while the screen is off") {
                if (battery) allowed() else PlainButton("Allow", {
                    context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                }, small = true)
            })
            if (Build.MANUFACTURER.equals("samsung", true)) add(Line("Samsung sleeping apps",
                "Samsung also puts apps to sleep by itself. In Settings, Battery, Background usage limits, add Pocket Drive to Never sleeping apps.") {
                PlainButton("Open", { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }, small = true)
            })
            add(Line("Protected folders (Shizuku)", shizuku.value.text + " Lets this app back up Android/data folders (upload only).") {
                when (shizuku.value) {
                    ShizukuState.NEEDS_PERMISSION -> PlainButton("Allow", { ShizukuAccess.requestPermission() }, small = true)
                    else -> PlainButton("Check", { shizuku.value = ShizukuAccess.state() }, small = true)
                }
            })
        })

        if (Push.available(g)) LinesCard("Instant sync", listOf(
            Line("Sync as soon as something changes", pushNote ?: "Needs the ntfy app, which keeps one quiet connection open for all apps.") {
                PSwitch(pushOn, { on ->
                    if (on) Push.enable(context) { found ->
                        pushOn = found
                        pushNote = if (found) null else "Install the ntfy app (from F-Droid) and pick it as your notification service, then try again."
                    } else { Push.disable(context, g); pushOn = false }
                })
            }))

        if (shares.isNotEmpty()) LinesCard("Your links", shares.map { s ->
            Line(s.key.substringAfterLast('/'), "${s.downloads} downloads" + (s.maxDownloads?.let { " of $it" } ?: "") +
                (if (s.hasPassword) " · password" else "") + (if (!s.public) " · Tailscale only" else "")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlainButton("Copy", { copyToClipboard(context, s.url) }, small = true)
                    PlainButton("Revoke", { scope.launch { runCatching { g.server.removeShare(s.token) }; shares = shares - s } }, small = true, danger = true)
                }
            }
        })

        PCard(padding = PaddingValues(vertical = 14.dp)) {
            SectionHeader("About", Modifier.padding(horizontal = 16.dp))
            UpdateControls(Updates.of(context))
            Divided(listOf(
                Line("Fonts", "Bricolage Grotesque, Instrument Sans and JetBrains Mono, under the SIL Open Font License") {
                    PlainButton("Licence", { licences = true }, small = true)
                },
                Line("First-time setup", "Connect to a different Pocket Cloud") { PlainButton("Open", { reconnect = true }, small = true) },
                Line("Sign out", "Files on the server stay. Transfers waiting here are dropped.") {
                    PlainButton("Sign out", { signOut = true }, small = true, danger = true)
                },
            ), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) { l ->
                ItemRow(trailing = { l.end() }) { TwoLine(l.title, l.text) }
            }
        }
    }

    fun forget() {
        scope.launch {
            g.engine.cancelAll()
            g.remote.reset()
            java.io.File(g.remote.home, "apps").deleteRecursively()
            g.settings.clear()
            onSignedOut()
        }
    }
    if (rename) TextInputDialog("Device name", "Name", initial = deviceName, onDismiss = { rename = false }, onConfirm = {
        g.settings.deviceName = it
        deviceName = it
    })
    if (signOut) ConfirmDialog("Sign out", "Forget this server on this phone? Your files on the server are not touched.", "Sign out", danger = true,
        onDismiss = { signOut = false }, onConfirm = ::forget)
    if (reconnect) ConfirmDialog("Connect to a different Pocket Cloud", "This phone signs out of ${g.settings.endpoint()} and opens the first-time setup. " +
        "Files on the server are not touched; transfers waiting here are dropped.", "Continue", danger = true,
        onDismiss = { reconnect = false }, onConfirm = ::forget)
    if (licences) {
        val text = remember { context.resources.openRawResource(R.raw.ofl).bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licences = false }, title = { Text("Font licences") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { licences = false }) { Text("Close") } })
    }
}
