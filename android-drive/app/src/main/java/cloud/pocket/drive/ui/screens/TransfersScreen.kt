package cloud.pocket.drive.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.CANCELED
import cloud.pocket.drive.data.DONE
import cloud.pocket.drive.data.DOWN
import cloud.pocket.drive.data.FAILED
import cloud.pocket.drive.data.PAUSED
import cloud.pocket.drive.data.QUEUED
import cloud.pocket.drive.data.RUNNING
import cloud.pocket.drive.data.Runner
import cloud.pocket.drive.data.TransferEngine
import cloud.pocket.drive.data.TransferEntity
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.Progress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen() {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    val items by g.db.transfers().observeAll().collectAsState(initial = emptyList())
    val anyPaused = items.any { it.state == PAUSED }
    val anyActive = items.any { it.state == QUEUED || it.state == RUNNING }
    val anyQueued = items.any { it.state == QUEUED }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Transfers") }, actions = {
                if (anyQueued) IconButton(onClick = {
                    scope.launch {
                        val dao = g.db.transfers()
                        items.filter { it.state == QUEUED }.forEach { dao.update(it.copy(notBefore = 0, error = null)) }
                        Runner.kick(context, true)
                    }
                }) { Icon(Icons.Filled.Refresh, "Retry now") }
                if (anyPaused) IconButton(onClick = { scope.launch { g.engine.resumeAll(); Runner.kick(context, true) } }) { Icon(Icons.Filled.PlayArrow, "Resume all") }
                else if (anyActive) IconButton(onClick = { scope.launch { g.engine.pauseAll() } }) { Icon(Icons.Filled.Pause, "Pause all") }
                IconButton(onClick = { scope.launch { g.db.transfers().clearFinished() } }) { Icon(Icons.Filled.ClearAll, "Clear finished") }
            })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        if (items.isEmpty()) {
            Column(Modifier.padding(inner).fillMaxSize()) { EmptyState("No transfers", "Uploads and downloads you start show up here.") }
        } else LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            items(items, key = { it.id }) { t ->
                TransferRow(t,
                    onCancel = { scope.launch { g.engine.cancel(t.id) } },
                    onRetry = { scope.launch { g.db.transfers().retry(t.id); Runner.kick(context, true) } },
                    onRemove = { scope.launch { g.db.transfers().delete(t.id) } })
            }
        }
    }
}

@Composable
private fun TransferRow(t: TransferEntity, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    var sample by remember(t.id) { mutableStateOf<Pair<Long, Long>?>(null) }      // bytes done and when
    var speed by remember(t.id) { mutableLongStateOf(0L) }                          // bytes per second, smoothed
    LaunchedEffect(t.done) {
        val now = SystemClock.elapsedRealtime()
        sample?.let { (before, at) ->
            if (now > at && t.done > before) {
                val instant = (t.done - before) * 1000 / (now - at)
                speed = if (speed == 0L) instant else (speed * 6 + instant * 4) / 10
            }
        }
        sample = t.done to now
        delay(30_000)                  // uploads report once per 8 MB part
        speed = 0                      // nothing arrived for a while: don't keep showing an old speed
    }
    ListItem(
        headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(statusLine(t, if (t.state == RUNNING) speed else 0), style = MaterialTheme.typography.bodySmall,
                    color = if (t.state == FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (t.state == RUNNING) Progress(t.done, if (t.done > 0) t.size else 0, Modifier.padding(top = 6.dp))
            }
        },
        leadingContent = {
            when (t.state) {
                DONE -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                FAILED -> Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                else -> Icon(if (t.kind == DOWN) Icons.Filled.Download else Icons.Filled.Upload, null)
            }
        },
        trailingContent = {
            when (t.state) {
                RUNNING, QUEUED, PAUSED -> IconButton(onClick = onCancel) { Icon(Icons.Filled.Cancel, "Cancel") }
                FAILED, CANCELED -> androidx.compose.foundation.layout.Row {
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "Try again") }
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove") }
                }
                else -> IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove") }
            }
        },
    )
}

private fun statusLine(t: TransferEntity, speed: Long = 0): String {
    val where = (if (t.kind == DOWN) "from " else "to ") + bucketLabel(t.bucket)
    return when (t.state) {
        RUNNING -> buildString {
            append("${bytes(t.done)} of ${bytes(t.size)}")
            if (t.size > 0) append(" · ${(t.done * 100 / t.size).coerceIn(0, 100)}%")
            if (speed > 0) append(" · ${bytes(speed)}/s")
            append(" · $where")
        }
        QUEUED -> when {
            t.error == null -> "Waiting · ${bytes(t.size)} · $where"
            t.attempts == 0 -> "Waiting to try again: ${t.error}"
            else -> "Attempt ${t.attempts} of ${TransferEngine.MAX_ATTEMPTS} failed, will try again: ${t.error}"
        }
        PAUSED -> "Paused · ${bytes(t.size)}"
        DONE -> "Done · ${bytes(t.size)} · $where"
        CANCELED -> "Canceled"
        FAILED -> t.error ?: "Failed"
        else -> ""
    }
}
