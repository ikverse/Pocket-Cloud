package cloud.pocket.panel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.AppRules
import cloud.pocket.panel.data.RetentionCheck
import cloud.pocket.panel.data.RetentionRule
import cloud.pocket.panel.data.RuleDraft
import cloud.pocket.panel.data.changesText
import cloud.pocket.panel.data.checkDraft
import cloud.pocket.panel.data.checkRetention
import cloud.pocket.panel.data.keepText
import cloud.pocket.panel.data.lastRunText
import cloud.pocket.panel.data.newRuleDraft
import cloud.pocket.panel.data.notifyText
import cloud.pocket.panel.data.publishLatestText
import cloud.pocket.panel.data.publishText
import cloud.pocket.panel.data.retentionBody
import cloud.pocket.panel.data.ruleBuckets
import cloud.pocket.panel.data.switchBody
import cloud.pocket.panel.data.toDraft
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.PocketListItem
import cloud.pocket.panel.ui.components.SaveRow
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.Segmented
import cloud.pocket.panel.ui.theme.PocketMono
import cloud.pocket.panel.ui.theme.PocketTheme
import kotlinx.coroutines.launch

/** The app page's rule cards (docs/app-api.md section 9); a part the server doesn't report is left out. */
fun LazyListScope.ruleItems(app: String, isFamily: Boolean, rules: AppRules, onChanged: () -> Unit) {
    rules.retention?.let { list -> item(key = "rules-retention") { RetentionCard(app, ruleBuckets(isFamily), list, onChanged) } }
    if (rules.hasUpdates) item(key = "rules-updates") { UpdatesCard(app, rules, onChanged) }
}

private const val NEW_RULE = -1

@Composable
private fun RetentionCard(app: String, buckets: List<String>, saved: List<RetentionRule>, onSaved: () -> Unit) {
    val panel = LocalPanel.current
    val drafts = remember(saved) { saved.map { it.toDraft() }.toMutableStateList() }
    var editing by remember { mutableStateOf<Int?>(null) }      // a rule's index, or NEW_RULE
    SectionCard("Keep only recent files", action = { TextButton(onClick = { editing = NEW_RULE }) { Text("Add a rule") } }) {
        Text("Applied each night; removed files go into the undo.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (drafts.isEmpty()) Text("No rules. Files stay until an app deletes them.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        drafts.forEachIndexed { i, d ->
            PocketListItem(
                modifier = Modifier.clickable { editing = i },
                overlineContent = { Text(d.bucket) },
                headlineContent = { Text(d.prefix, style = PocketMono) },
                supportingContent = {
                    Column {
                        Text(d.keepText())
                        Text(lastRunText(d.saved), style = MaterialTheme.typography.bodySmall, color = PocketTheme.extra.faint)
                    }
                },
                trailingContent = { IconButton(onClick = { drafts.removeAt(i) }) { Icon(Icons.Outlined.Delete, "Remove rule") } },
                colors = clearItem(),
            )
        }
        SaveRow(label = "Save rules") {
            when (val check = checkRetention(drafts, buckets)) {
                is RetentionCheck.Bad -> panel.scope.launch { panel.snackbar.showSnackbar(check.message) }
                is RetentionCheck.Ok -> panel.act("Rules saved.", onDone = onSaved) {
                    panel.api.patch("/api/apps/$app/rules", retentionBody(check.rules))
                }
            }
        }
    }
    editing?.let { idx ->
        val start = if (idx == NEW_RULE) newRuleDraft(buckets) else drafts.getOrNull(idx)
        if (start != null) RuleDialog(start, buckets, isNew = idx == NEW_RULE, onDismiss = { editing = null }) { d ->
            if (idx == NEW_RULE) drafts.add(d) else if (idx < drafts.size) drafts[idx] = d
            editing = null
        }
    }
}

/** Edit one rule; it joins the list on Done and is sent with the rest on Save rules. */
@Composable
private fun RuleDialog(start: RuleDraft, buckets: List<String>, isNew: Boolean, onDismiss: () -> Unit, onDone: (RuleDraft) -> Unit) {
    var bucket by remember { mutableStateOf(start.bucket) }
    var prefix by remember { mutableStateOf(start.prefix) }
    var keepLast by remember { mutableStateOf(start.keepLast) }
    var keepDays by remember { mutableStateOf(start.keepDays) }
    var tried by remember { mutableStateOf(false) }
    val draft = start.copy(bucket = bucket, prefix = prefix.trim(), keepLast = keepLast.trim(), keepDays = keepDays.trim())
    val problem = checkDraft(draft, buckets)
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(if (isNew) "Add a rule" else "Edit rule", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (buckets.size > 1) Segmented(buckets, buckets.indexOf(bucket).coerceAtLeast(0), Modifier.fillMaxWidth()) { bucket = buckets[it] }
                else Text("Space: ${buckets.first()}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(prefix, { prefix = it }, label = { Text("Folder") }, placeholder = { Text("backups/") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keepLast, { keepLast = it }, label = { Text("Keep last (files)") }, placeholder = { Text("—") },
                    singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keepDays, { keepDays = it }, label = { Text("Or newer than (days)") }, placeholder = { Text("—") },
                    singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                Text("A file stays if it is among the last ones or newer than the days; leave either blank.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tried && problem != null) Text(problem.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { if (problem == null) onDone(draft) else tried = true }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UpdatesCard(app: String, rules: AppRules, onChanged: () -> Unit) {
    val panel = LocalPanel.current
    fun flip(kind: String, on: Boolean) = panel.act(if (on) "Switched on." else "Switched off.", onDone = onChanged) {
        panel.api.patch("/api/apps/$app/rules", switchBody(kind, on))
    }
    SectionCard("Updates and notifications") {
        rules.publish?.let { p ->
            PocketListItem(
                headlineContent = { Text("Publish folder for updates") },
                supportingContent = {
                    Column {
                        Text(publishText(p))
                        publishLatestText(p)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                trailingContent = { Switch(p.enabled, { flip("publish", it) }) },
                colors = clearItem(),
            )
        }
        rules.notify?.let { n ->
            PocketListItem(
                headlineContent = { Text("Let the app send its own messages") },
                supportingContent = { Text(notifyText(n)) },
                trailingContent = { Switch(n.enabled, { flip("notify", it) }) },
                colors = clearItem(),
            )
        }
        rules.changes?.let { c ->
            PocketListItem(headlineContent = { Text("Change feed") }, supportingContent = { Text(changesText(c)) }, colors = clearItem())
        }
    }
}
