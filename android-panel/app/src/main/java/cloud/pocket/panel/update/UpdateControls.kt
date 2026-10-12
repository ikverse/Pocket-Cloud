package cloud.pocket.panel.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.roundToInt

/**
 * The "About" card's body: which build this is, whether there is a newer one, and the buttons
 * that fetch and install it.
 *
 * Every step is a tap: nothing is downloaded or installed because the app decided to. An app that
 * could replace itself unasked is what the permission behind this exists to prevent.
 */
@Composable
fun UpdateControls(updates: Updates, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = updates.updateState
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading

    // Re-read on the way back rather than only at composition. The permission is granted on a
    // system page, and returning from one does not recompose a card on its own.
    var canInstall by remember { mutableStateOf(updates.canInstallUpdates()) }
    LifecycleResumeEffect(Unit) {
        canInstall = updates.canInstallUpdates()
        onPauseOrDispose { }
    }

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Version ${updates.currentVersionName}", style = MaterialTheme.typography.bodyLarge)

        when (state) {
            UpdateState.Idle -> Unit

            UpdateState.Checking -> Text("Checking GitHub…", color = MaterialTheme.colorScheme.onSurfaceVariant)

            is UpdateState.UpToDate -> Text(
                "Version ${state.versionName} is the newest release.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is UpdateState.Available -> {
                Text(
                    "Version ${state.update.versionName} · ${state.update.sizeLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.update.notes.isNotBlank()) {
                    // Capped rather than scrolled: what does not fit is on the release page.
                    Text(
                        state.update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(onClick = { updates.downloadUpdate(state.update) }) { Text("Download") }
            }

            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Downloading ${state.update.versionName} · ${(state.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is UpdateState.Ready -> {
                Text(
                    "Version ${state.update.versionName} is downloaded and signed by the same key as this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canInstall) {
                    Text(
                        "Android has to allow this app to install apps first. The button opens that " +
                            "setting; come back here afterwards and press Install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = {
                    // Read at the tap as well as on resume: whichever way the permission was
                    // granted, the button must do the right thing the moment it is pressed.
                    if (updates.canInstallUpdates()) {
                        updates.installUpdate(state.file)
                        return@Button
                    }
                    runCatching { context.startActivity(updates.installPermissionIntent()) }.onFailure { error ->
                        updates.reportUpdateProblem(
                            error.message?.takeIf(String::isNotBlank) ?: "Android would not open the installer.",
                        )
                    }
                    // Two taps, and the label says which one this is. "Install" on a button that
                    // opens a settings page is how someone grants the permission, comes back, and
                    // believes the install has silently failed.
                }) { Text(if (canInstall) "Install" else "Allow installs") }
            }

            is UpdateState.Failed -> Text(state.reason, color = MaterialTheme.colorScheme.error)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = updates::checkForUpdate) {
                Text(if (state is UpdateState.Idle) "Check for updates" else "Check again")
            }
            if (state is UpdateState.Available || state is UpdateState.Ready) {
                OutlinedButton(onClick = { runCatching { context.startActivity(updates.releasesPageIntent()) } }) {
                    Text("Release page")
                }
            }
            if (state is UpdateState.Failed || state is UpdateState.UpToDate) {
                TextButton(onClick = updates::dismissUpdate) { Text("Dismiss") }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates when the app opens", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "One request to GitHub, and it says nothing unless there is a newer build. " +
                        "Nothing is downloaded or installed without you pressing for it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = updates.checkOnOpenState, onCheckedChange = { updates.checkOnOpen = it })
        }
    }
}
