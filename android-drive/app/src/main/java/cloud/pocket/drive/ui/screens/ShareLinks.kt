package cloud.pocket.drive.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.Share
import kotlinx.coroutines.launch

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pocket Cloud link", text))
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Make a link to a file: how long it works, an optional password and download limit. */
@Composable
fun ShareLinkDialog(bucket: String, key: String, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val g = (context.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    var hours by remember { mutableStateOf(24L) }
    var password by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<Share?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (made == null) "Link to $name" else "Link ready") },
        text = {
            val link = made
            if (link == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Works for", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1L to "1 hour", 24L to "1 day", 168L to "7 days", 720L to "30 days").forEach { (h, label) ->
                        FilterChip(hours == h, { hours = h }, label = { Text(label) })
                    }
                }
                OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(limit, { limit = it.filter { c -> c.isDigit() } }, label = { Text("Download limit (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(link.url, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (link.public) "Anyone with this link can download the file until it expires."
                    else "This link only works for people on your Tailscale network. To share with anyone, the server needs a public address (see the control panel).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            val link = made
            if (link == null) TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        made = g.server.createShare(bucket, key, hours * 3600, password.ifBlank { null }, limit.toIntOrNull())
                    } catch (e: Exception) {
                        error = (e as? cloud.pocket.drive.data.ServerApi.Failure)?.message ?: Remote.explain(e)
                    } finally { busy = false }
                }
            }) { Text("Create link") }
            else Row {
                TextButton(onClick = { copyToClipboard(context, link.url) }) { Text("Copy") }
                TextButton(onClick = { shareText(context, link.url) }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (made == null) "Cancel" else "Done") } },
    )
}
