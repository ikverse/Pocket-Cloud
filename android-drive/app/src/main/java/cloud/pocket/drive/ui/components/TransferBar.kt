package cloud.pocket.drive.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.data.Status

/**
 * A strip above the content of every tab: what is being transferred and how far it has got, that transfers are waiting
 * (and why), or that some have failed. Tapping it opens the Transfers tab.
 */
@Composable
fun TransferBar(status: Status?, active: Int, failed: Int, waiting: String?, onOpen: () -> Unit) {
    if (status == null && active == 0 && failed == 0) return
    val problem = status == null && (failed > 0 || waiting != null)
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        color = if (problem) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (problem) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (status != null) {
                    val verb = if (status.upload) "Uploading" else "Downloading"
                    val title = if (status.count > 1) "$verb ${status.index} of ${status.count}" else verb
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(title + if (failed > 0) " · $failed failed" else "", style = MaterialTheme.typography.labelLarge)
                        if (status.total > 0 && status.done > 0) Text("${percent(status)}%", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(status.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (failed > 0) {
                    Text(if (failed == 1) "1 transfer failed" else "$failed transfers failed", style = MaterialTheme.typography.labelLarge)
                    Text("Tap to see why", style = MaterialTheme.typography.bodySmall)
                } else if (waiting != null) {
                    Text("Waiting to try again", style = MaterialTheme.typography.labelLarge)
                    Text(waiting, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(if (active == 1) "1 transfer waiting" else "$active transfers waiting", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (status != null) Progress(status.done, if (status.done > 0) status.total else 0)      // moving, but nothing finished yet
        }
    }
}

private fun percent(s: Status): Int = (s.done * 100 / s.total).toInt().coerceIn(0, 100)
