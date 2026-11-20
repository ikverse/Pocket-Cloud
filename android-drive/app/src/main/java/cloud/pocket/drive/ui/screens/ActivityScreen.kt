package cloud.pocket.drive.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.CANCELED
import cloud.pocket.drive.data.DOWN
import cloud.pocket.drive.data.FAILED
import cloud.pocket.drive.data.PAUSED
import cloud.pocket.drive.data.QUEUED
import cloud.pocket.drive.data.RUNNING
import cloud.pocket.drive.data.Runner
import cloud.pocket.drive.data.STATUS_ERROR
import cloud.pocket.drive.data.TransferEngine
import cloud.pocket.drive.data.TransferEntity
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.sync.SyncScheduler
import cloud.pocket.drive.ui.components.Divided
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.FileTile
import cloud.pocket.drive.ui.components.ItemRow
import cloud.pocket.drive.ui.components.Meter
import cloud.pocket.drive.ui.components.Mono
import cloud.pocket.drive.ui.components.PCard
import cloud.pocket.drive.ui.components.PlainButton
import cloud.pocket.drive.ui.components.ScreenPadding
import cloud.pocket.drive.ui.components.ScreenTitle
import cloud.pocket.drive.ui.components.Segmented
import cloud.pocket.drive.ui.components.TwoLine
import cloud.pocket.drive.ui.theme.PocketTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Activity: what is moving now, what failed (transfers and sync folders), and one log of everything. */
@Composable
fun ActivityScreen(onSync: () -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    val items by g.db.transfers().observeAll().collectAsState(initial = emptyList())
    val pairs by g.db.sync().observePairs().collectAsState(initial = emptyList())
    val log by g.db.sync().observeAllLog().collectAsState(initial = emptyList())
    var view by rememberSaveable { mutableStateOf(0) }

    val now = items.filter { it.state == RUNNING || it.state == QUEUED || it.state == PAUSED }
    val failed = items.filter { it.state == FAILED || it.state == CANCELED }
    val brokenPairs = pairs.filter { it.status == STATUS_ERROR && it.enabled }
    val failedCount = failed.size + brokenPairs.size
    val names = pairs.associate { it.id to it.name }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Activity")
        Segmented(listOf("Now · ${now.size}", "Failed · $failedCount", "Log"), view, { view = it })
        when (view) {
            0 -> {
                if (now.isEmpty()) PCard { EmptyState("Nothing is moving", "Uploads and downloads you start, and sync's transfers, show up here.") }
                else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (now.any { it.state == PAUSED }) PlainButton("Resume all", { scope.launch { g.engine.resumeAll(); Runner.kick(context, true) } }, small = true)
                        else PlainButton("Pause all", { scope.launch { g.engine.pauseAll() } }, small = true)
                        if (now.any { it.state == QUEUED && it.error != null }) PlainButton("Try now", {
                            scope.launch {
                                val dao = g.db.transfers()
                                now.filter { it.state == QUEUED }.forEach { dao.update(it.copy(notBefore = 0, error = null)) }
                                Runner.kick(context, true)
                            }
                        }, small = true)
                    }
                    PCard { Divided(now.take(200)) { t -> NowRow(t, onCancel = { scope.launch { g.engine.cancel(t.id) } }) } }
                    if (now.size > 200) Text("And ${now.size - 200} more waiting.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val waitingWhy = now.firstOrNull { it.state == QUEUED && it.error != null }?.error
                if (waitingWhy != null) Text("Waiting: $waitingWhy", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            1 -> {
                if (failedCount == 0) PCard { EmptyState("Nothing failed", "Failed transfers stay here until you retry or dismiss them.") }
                if (brokenPairs.isNotEmpty()) PCard {
                    Divided(brokenPairs) { p ->
                        ItemRow(trailing = { PlainButton("Try again", { SyncScheduler.runNow(context, p.id) }, small = true) }) {
                            TwoLine(p.name, p.lastResult.ifBlank { "The last sync failed" }, subColor = PocketTheme.extra.bad)
                        }
                    }
                }
                if (failed.isNotEmpty()) {
                    PCard {
                        Divided(failed) { t ->
                            ItemRow(leading = { FileTile(t.name, false) }, trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlainButton("Retry", { scope.launch { g.db.transfers().retry(t.id); Runner.kick(context, true) } }, small = true)
                                    PlainButton("Dismiss", { scope.launch { g.db.transfers().delete(t.id) } }, small = true)
                                }
                            }) {
                                TwoLine(t.name, (if (t.state == CANCELED) "Canceled" else (if (t.kind == DOWN) "Not downloaded: " else "Not uploaded: ") + (t.error ?: "it failed")),
                                    subColor = if (t.state == CANCELED) MaterialTheme.colorScheme.onSurfaceVariant else PocketTheme.extra.bad)
                            }
                        }
                    }
                    Text("Failed transfers stay here until you retry or dismiss them.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                val lines = Timeline.merge(log, names, items)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlainButton("Clear finished transfers", { scope.launch { g.db.transfers().clearFinished() } }, small = true)
                    if (pairs.isNotEmpty()) PlainButton("Sync folders", onSync, small = true)
                }
                if (lines.isEmpty()) PCard { EmptyState("Nothing logged yet") }
                else PCard {
                    Divided(lines) { l ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono(Timeline.shortWhen(l.ts), Modifier.width(72.dp).padding(top = 1.dp), color = PocketTheme.extra.faint)
                            Column(Modifier.weight(1f)) {
                                Text(l.who, style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize))
                                Text(l.text, style = MaterialTheme.typography.bodyMedium, color = when (l.level) {
                                    "error" -> PocketTheme.extra.bad
                                    "warn", "conflict" -> PocketTheme.extra.warn
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowRow(t: TransferEntity, onCancel: () -> Unit) {
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ItemRow(leading = { FileTile(t.name, false) }, trailing = { PlainButton("Cancel", onCancel, small = true) }) {
            TwoLine(t.name, statusLine(t, if (t.state == RUNNING) speed else 0))
        }
        if (t.state == RUNNING || t.state == QUEUED) Meter(if (t.size > 0 && t.done > 0) t.done.toFloat() / t.size else 0f)
    }
}

private fun statusLine(t: TransferEntity, speed: Long = 0): String {
    val verb = if (t.kind == DOWN) "Downloading" else "Uploading"
    val where = (if (t.kind == DOWN) "from " else "to ") + bucketLabel(t.bucket)
    return when (t.state) {
        RUNNING -> buildString {
            append("$verb · ${bytes(t.done)} of ${bytes(t.size)}")
            if (speed > 0) append(" · ${bytes(speed)}/s")
        }
        QUEUED -> when {
            t.error == null -> "$verb · waiting its turn · ${bytes(t.size)} $where"
            t.attempts == 0 -> "Waiting to try again: ${t.error}"
            else -> "Attempt ${t.attempts} of ${TransferEngine.MAX_ATTEMPTS} failed, will try again: ${t.error}"
        }
        PAUSED -> "Paused · ${bytes(t.size)}"
        else -> ""
    }
}
