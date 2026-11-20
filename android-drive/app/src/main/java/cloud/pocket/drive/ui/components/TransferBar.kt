package cloud.pocket.drive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.data.Status
import cloud.pocket.drive.ui.theme.PocketTheme
import cloud.pocket.drive.ui.theme.TNUM

/**
 * The persistent strip above the tabs: what is moving and how far it has got, that transfers are waiting (and why), or
 * that some have failed. In the ink colour (red wash for failures); tapping it opens Activity.
 */
@Composable
fun TransferBar(status: Status?, active: Int, failed: Int, waiting: String?, onOpen: () -> Unit) {
    if (status == null && active == 0 && failed == 0) return
    val problem = status == null && failed > 0
    val c = MaterialTheme.colorScheme
    val x = PocketTheme.extra
    val bg = if (problem) x.badWash else c.inverseSurface
    val fg = if (problem) x.bad else c.inverseOnSurface
    Column(
        Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(onClick = onOpen).semantics { contentDescription = "Open activity" }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val (title, end, sub) = when {
            status != null -> {
                val verb = if (status.upload) "Uploading" else "Downloading"
                val pct = if (status.total > 0 && status.done > 0) "${(status.done * 100 / status.total).coerceIn(0, 100)}%" else ""
                val more = buildList {
                    if (status.count > 1) add("${status.index} of ${status.count}")
                    if (failed > 0) add("$failed failed")
                }.joinToString(" · ")
                Triple("$verb ${status.name}", pct, more)
            }
            failed > 0 -> Triple(if (failed == 1) "1 transfer failed" else "$failed transfers failed", "", "Tap to see why and try again")
            waiting != null -> Triple("Waiting to try again", "", waiting)
            else -> Triple(if (active == 1) "1 transfer waiting" else "$active transfers waiting", "", "")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (end.isNotEmpty()) Text(end, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = fg)
        }
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (status != null) {
            val f = if (status.total > 0 && status.done > 0) status.done.toFloat() / status.total else 0f
            Meter(f, Modifier.padding(top = 7.dp), height = 4.dp, track = fg.copy(alpha = 0.22f), fill = fg)
        }
    }
}
