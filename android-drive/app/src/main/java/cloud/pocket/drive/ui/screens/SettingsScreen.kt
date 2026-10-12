package cloud.pocket.drive.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.Quota
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.push.Push
import cloud.pocket.drive.ui.components.Progress
import androidx.compose.runtime.LaunchedEffect
import cloud.pocket.drive.ui.components.ConfirmDialog
import cloud.pocket.drive.ui.components.SectionTitle
import cloud.pocket.drive.ui.components.TextInputDialog
import cloud.pocket.drive.update.UpdateControls
import cloud.pocket.drive.update.Updates
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSignedOut: () -> Unit, extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    var signOut by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    var deviceName by remember { mutableStateOf(g.settings.deviceName) }
    var endpoint by remember { mutableStateOf(g.settings.endpointInUse) }
    var features by remember { mutableStateOf(g.settings.features) }
    var notifGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    var quota by remember { mutableStateOf<Quota?>(null) }
    LaunchedEffect(features) { quota = g.server.quota() }
    var shares by remember { mutableStateOf<List<cloud.pocket.drive.data.Share>>(emptyList()) }
    LaunchedEffect(features) { shares = if (g.settings.has("share")) runCatching { g.server.shares() }.getOrDefault(emptyList()) else emptyList() }
    var pushOn by remember { mutableStateOf(Push.enabled(g)) }
    var pushNote by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Server")
            ListItem(headlineContent = { Text("Address") }, supportingContent = { Text(g.settings.endpoint()) })
            ListItem(headlineContent = { Text("Connected through") }, supportingContent = { Text(endpoint.ifBlank { "not connected yet" }) })
            if (g.settings.serverVersion.isNotBlank()) ListItem(headlineContent = { Text("Server version") }, supportingContent = { Text(g.settings.serverVersion) })
            ListItem(headlineContent = { Text("Server features") },
                supportingContent = { Text(if (features.isEmpty()) "Basic (storage only)" else features.sorted().joinToString(", ")) })
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = {
                    test = "Testing…"
                    scope.launch {
                        test = try {
                            val ep = g.remote.connect()
                            endpoint = ep
                            features = g.settings.features
                            "Connected through $ep"
                        } catch (e: Exception) { Remote.explain(e) }
                    }
                }) { Text("Test connection") }
                if (test != null) Text(test!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            quota?.let { q ->
                SectionTitle("Storage")
                ListItem(headlineContent = { Text("${bytes(q.usedApp)} used of ${bytes(q.allocation)}") },
                    supportingContent = {
                        Column {
                            Text("${bytes(q.free)} free for this device")
                            Progress(q.usedApp, q.allocation, Modifier.padding(top = 8.dp))
                        }
                    })
            }
            if (shares.isNotEmpty()) {
                SectionTitle("Your links")
                shares.forEach { s ->
                    ListItem(
                        headlineContent = { Text(s.key.substringAfterLast('/')) },
                        supportingContent = { Text("${s.downloads} downloads" + (s.maxDownloads?.let { " of $it" } ?: "") + (if (s.hasPassword) " · password" else "") + (if (!s.public) " · Tailscale only" else "")) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { copyToClipboard(context, s.url) }) { Text("Copy") }
                                TextButton(onClick = { scope.launch { runCatching { g.server.removeShare(s.token) }; shares = shares - s } }) { Text("Revoke") }
                            }
                        })
                }
            }
            if (Push.available(g)) {
                SectionTitle("Instant sync")
                ListItem(headlineContent = { Text("Sync as soon as something changes") },
                    supportingContent = { Text(pushNote ?: "Needs the ntfy app, which keeps one quiet connection open for all apps.") },
                    trailingContent = {
                        androidx.compose.material3.Switch(pushOn, { on ->
                            if (on) Push.enable(context) { found ->
                                pushOn = found
                                pushNote = if (found) null else "Install the ntfy app (from F-Droid) and pick it as your notification service, then try again."
                            } else { Push.disable(context, g); pushOn = false }
                        })
                    })
            }

            SectionTitle("Protected folders (Shizuku)")
            val shizuku = remember { mutableStateOf(cloud.pocket.drive.shizuku.ShizukuAccess.state()) }
            ListItem(headlineContent = { Text("Back up Android/data folders") },
                supportingContent = { Text(shizuku.value.text + " Android keeps these folders closed to apps; Shizuku lends this app the rights to read them. Upload only.") },
                trailingContent = {
                    when (shizuku.value) {
                        cloud.pocket.drive.shizuku.ShizukuState.NEEDS_PERMISSION -> OutlinedButton(onClick = { cloud.pocket.drive.shizuku.ShizukuAccess.requestPermission() }) { Text("Allow") }
                        else -> OutlinedButton(onClick = { shizuku.value = cloud.pocket.drive.shizuku.ShizukuAccess.state() }) { Text("Check") }
                    }
                })

            SectionTitle("This device")
            ListItem(headlineContent = { Text("Name") }, supportingContent = { Text(deviceName) },
                trailingContent = { OutlinedButton(onClick = { rename = true }) { Text("Change") } })

            SectionTitle("Notifications")
            ListItem(headlineContent = { Text("Transfer and sync notifications") },
                supportingContent = { Text(if (notifGranted) "Allowed" else "Not allowed: progress and sync problems won't show.") },
                trailingContent = {
                    if (!notifGranted && Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                })

            extra()

            SectionTitle("Account")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { signOut = true }) { Text("Sign out of this server") }
                Text("Files on the server stay. Transfers waiting here are dropped.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            SectionTitle("About")
            UpdateControls(Updates.of(context), Modifier.padding(bottom = 8.dp))
        }
    }

    if (rename) TextInputDialog("Device name", "Name", initial = deviceName, onDismiss = { rename = false }, onConfirm = {
        g.settings.deviceName = it
        deviceName = it
    })
    if (signOut) ConfirmDialog("Sign out", "Forget this server on this phone? Your files on the server are not touched.", "Sign out", danger = true,
        onDismiss = { signOut = false }, onConfirm = {
            scope.launch {
                g.engine.cancelAll()
                g.remote.reset()
                java.io.File(g.remote.home, "apps").deleteRecursively()
                g.settings.clear()
                onSignedOut()
            }
        })
}
