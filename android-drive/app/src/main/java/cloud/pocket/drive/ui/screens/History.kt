package cloud.pocket.drive.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.OldVersion
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.bucketLabel
import cloud.pocket.drive.data.bytes
import cloud.pocket.drive.data.dateTime
import cloud.pocket.drive.ui.components.EmptyState
import cloud.pocket.drive.ui.components.ErrorBox
import cloud.pocket.drive.ui.components.LoadingBox
import kotlinx.coroutines.launch

private fun describe(v: OldVersion): String = buildString {
    append(bytes(v.size))
    append("  ·  ")
    append(if (v.reason == "deleted") "deleted " else "replaced ")
    append(dateTime(v.time))
    if (v.by.isNotBlank()) append(" by ${v.by}")
    v.daysLeft?.let { append("  ·  $it days left") }
}

/** The files deleted from the drive that the server still keeps, with a button to bring each back. */
@Composable
fun TrashList(bucket: String, onRestored: (String) -> Unit) {
    val g = (LocalContext.current.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<OldVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(bucket, reload) {
        items = null
        error = null
        try { items = g.server.trash(bucket, "").filter { it.reason != "overwritten" } } catch (e: Exception) { error = (e as? cloud.pocket.drive.data.ServerApi.Failure)?.message ?: Remote.explain(e) }
    }
    val list = items
    when {
        error != null -> ErrorBox(error!!, retry = { reload += 1 })
        list == null -> LoadingBox()
        list.isEmpty() -> EmptyState("The trash is empty", "Deleted files stay here for a while before the server removes them for good.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.key + it.version }) { v ->
                ListItem(
                    headlineContent = { Text(Keys.name(v.key), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(bucketLabel(v.bucket) + " / " + Keys.parent(v.key) + "\n" + describe(v)) },
                    trailingContent = {
                        TextButton(onClick = {
                            scope.launch {
                                try { g.server.restore(v); items = list - v; onRestored(Keys.name(v.key)) }
                                catch (e: Exception) { error = (e as? cloud.pocket.drive.data.ServerApi.Failure)?.message ?: Remote.explain(e) }
                            }
                        }) { Text("Restore") }
                    })
            }
        }
    }
}

/** Earlier versions of one file. */
@Composable
fun VersionsDialog(bucket: String, key: String, name: String, onRestored: (String) -> Unit, onDismiss: () -> Unit) {
    val g = (LocalContext.current.applicationContext as DriveApplication).graph
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<OldVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        try { items = g.server.versions(bucket, key) } catch (e: Exception) { error = (e as? cloud.pocket.drive.data.ServerApi.Failure)?.message ?: Remote.explain(e) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Earlier versions of $name") },
        text = {
            val list = items
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                list == null -> Text("Loading…")
                list.isEmpty() -> Text("There are no earlier versions. The server keeps the old copy when a file is replaced or deleted.")
                else -> LazyColumn(Modifier.height(300.dp)) {
                    items(list, key = { it.version }) { v ->
                        ListItem(
                            headlineContent = { Text(dateTime(v.time)) },
                            supportingContent = { Text(describe(v)) },
                            trailingContent = {
                                TextButton(onClick = {
                                    scope.launch {
                                        try { g.server.restore(v); onRestored(name); onDismiss() }
                                        catch (e: Exception) { error = (e as? cloud.pocket.drive.data.ServerApi.Failure)?.message ?: Remote.explain(e) }
                                    }
                                }) { Text("Restore") }
                            })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
