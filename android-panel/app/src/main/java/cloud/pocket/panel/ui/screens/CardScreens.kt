package cloud.pocket.panel.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.ago
import cloud.pocket.panel.data.body
import cloud.pocket.panel.data.bytes
import cloud.pocket.panel.data.count
import cloud.pocket.panel.data.dateTime
import cloud.pocket.panel.data.gb
import cloud.pocket.panel.ui.components.ConfirmDialog
import cloud.pocket.panel.ui.components.LocalPanel
import cloud.pocket.panel.ui.components.SectionCard
import cloud.pocket.panel.ui.components.TextInputDialog
import cloud.pocket.panel.ui.components.rememberLoad
import org.json.JSONArray

private val KIND = mapOf("video" to "Video", "photo" to "Photo", "audio" to "Audio", "archive" to "Archive", "other" to "Other")

fun spaceLabel(space: String) = if (space == "shared") "shared" else "device " + space.removePrefix("device:")

private fun speed(c: J, slow: Int) = if (!c.has("write_mbs")) "speed not measured"
    else "write ${c.d("write_mbs")} MB/s · read ${c.d("read_mbs")} MB/s" + if (c.d("write_mbs") < slow) " (slow: under $slow MB/s)" else ""

/** Storage › SD cards: the card in use, the card in the slot, the shelf, and any running card job. */
@Composable
fun SdCardsCard(cd: J, reload: () -> Unit) {
    if (cd.s("mode") != "cards") return
    val panel = LocalPanel.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val job = cd.obj("job")
    val running = job.b("running")
    val slow = cd.i("slow_mbs")
    var swap by remember { mutableStateOf(false) }
    var putBack by remember { mutableStateOf<String?>(null) }
    var useNew by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<J?>(null) }
    var forget by remember { mutableStateOf<J?>(null) }
    val note = @Composable { text: String, color: androidx.compose.ui.graphics.Color ->
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
    SectionCard("SD cards") {
        if (running) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(when (job.s("kind")) { "swap" -> "Swapping the SD card"; "use" -> "Setting up the new card"; else -> "Putting the card back to work" },
                    style = MaterialTheme.typography.titleSmall)
                if (job.i("total") > 0) LinearProgressIndicator(progress = { job.i("done").toFloat() / job.i("total") }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text(job.s("step") + if (job.i("total") > 0) " · ${count(job.i("done"))} of ${count(job.i("total"))}" else "",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        } else if (job.has("error") && System.currentTimeMillis() / 1000.0 - job.d("finished") < 3600) {
            note("The last SD card job failed: ${job.s("error")}", cs.error)
        }
        val a = cd.obj("active")
        val slot = cd.obj("slot")
        when {
            cd.has("active") && a.b("present") -> ListItem(
                headlineContent = { Text(a.s("name")) },
                overlineContent = { Text("In use · ${a.s("volume")} · ${a.s("fs")}") },
                supportingContent = { Text("${bytes(a.d("total"))}, ${bytes(a.d("free"))} free · ${speed(a, slow)}") },
                colors = clearItem(),
            )
            cd.has("active") -> note("${a.s("name")} was removed without a swap. Its ${count(a.l("files"))} files (${gb(a.d("bytes"))}, as of the last " +
                "nightly copy) are not available until it is back. Everything else works on internal storage.", cs.error)
            !cd.has("slot") -> note("No SD card in the phone: everything is on internal storage. Put a card in to add room and a nightly copy.", cs.onSurfaceVariant)
        }
        if (cd.has("active") && a.b("present")) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { swap = true }, enabled = !running) { Text("Swap SD card") }
                OutlinedButton(onClick = { rename = a }) { Text("Rename") }
            }
        }
        if (cd.has("slot")) {
            val card = slot.obj("card")
            when (slot.s("state")) {
                "new" -> {
                    ListItem(headlineContent = { Text("A new SD card is in the phone") },
                        supportingContent = { Text("${slot.s("volume")} · ${slot.s("fs")} · ${bytes(slot.d("total"))}, ${bytes(slot.d("free"))} free. " +
                            "Setting it up checks its speed, sets the SD pool from its size and moves older files onto it.") }, colors = clearItem())
                    Button(onClick = { useNew = true }, enabled = !running, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Use this card") }
                }
                "needs_setup" -> note("A card is in the phone (${slot.s("volume")}), but Termux has no folder on it yet. On the phone running " +
                    "Pocket CloudServer, open Termux, type termux-setup-storage, press Enter and answer y. The card then shows here as a new card " +
                    "within a few seconds.", cs.tertiary)
                "archive", "shelved_here" -> {
                    if (slot.s("state") == "shelved_here") {
                        ListItem(headlineContent = { Text("${card.s("name")} is ready to remove") },
                            supportingContent = { Text("On the phone running Pocket CloudServer, unmount it (Settings › Device care › Storage › SD card › Unmount), " +
                                "then take it out. Then put in the next card: a new one or an archive card.") }, colors = clearItem())
                    } else {
                        ListItem(headlineContent = { Text("${card.s("name")}, an archive card, is in the phone") },
                            supportingContent = { Text("Shelved ${dateTime(card.d("shelved"))} · ${count(card.l("files"))} files, ${gb(card.d("bytes"))}. " +
                                "Browse it and restore single files, or put it back to work.") }, colors = clearItem())
                    }
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (slot.s("state") == "shelved_here") {
                            Button(onClick = {
                                context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Open storage settings") }
                        }
                        OutlinedButton(onClick = { panel.open("files/card:" + card.s("id")) }) { Text("Browse") }
                        OutlinedButton(onClick = { putBack = card.s("id") }, enabled = !running) { Text("Put back to work") }
                    }
                }
            }
        }
        val shelf = cd.arr("shelf")
        if (shelf.isNotEmpty()) {
            Text("On the shelf", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            shelf.forEach { c ->
                ListItem(
                    headlineContent = { Text(c.s("name")) },
                    supportingContent = { Text("Shelved ${dateTime(c.d("shelved"))} · ${count(c.l("files"))} files, ${gb(c.d("bytes"))}" +
                        if (c.s("note").isNotBlank()) " · ${c.s("note")}" else "") },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { panel.open("files/card:" + c.s("id")) }) { Text("Browse") }
                            TextButton(onClick = { forget = c }) { Text("Forget", color = cs.error) }
                        }
                    },
                    modifier = Modifier.clickable { rename = c },
                    colors = clearItem(),
                )
            }
        }
    }
    if (swap) SwapSheet(onDismiss = { swap = false }, onStarted = reload)
    putBack?.let { id -> PutBackDialog(id, onDismiss = { putBack = null }, onStarted = reload) }
    if (useNew) {
        TextInputDialog("Use this card", "Name for the card", "Card ${cd.arr("shelf").size + if (cd.has("active")) 2 else 1}", confirm = "Use this card",
            onDismiss = { useNew = false }) { name ->
            panel.act("Setting up the card…", onDone = reload) { panel.api.post("/api/cards/use", body("name" to name.trim())) }
        }
    }
    forget?.let { c ->
        ConfirmDialog("Forget card", "Remove ${c.s("name")} from the shelf? Use this when the card was formatted, lost or thrown away. " +
            "Nothing on any card changes; if it turns up again, it is recognised from the marker it carries.", "Forget", danger = true,
            onDismiss = { forget = null }) {
            panel.act("Card forgotten.", onDone = reload) { panel.api.delete("/api/cards/${c.s("id")}") }
        }
    }
    rename?.let { c ->
        TextInputDialog("Rename card", "Card name", c.s("name"), onDismiss = { rename = null }) { name ->
            panel.act("Card renamed.", onDone = reload) { panel.api.patch("/api/cards/${c.s("id")}", body("name" to name.trim())) }
        }
    }
}

/** Swap SD card: tick what apps keep using (copied to internal storage, then onto the next card). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwapSheet(onDismiss: () -> Unit, onStarted: () -> Unit) {
    val panel = LocalPanel.current
    val plan = rememberLoad { get("/api/cards/swap") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val p = plan.data
        if (p == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                if (plan.error != null) Text(plan.error!!) else CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }
        val groups = p.arr("groups")
        val carry = remember(p) { mutableStateListOf(*groups.map { it.b("carry") }.toTypedArray()) }
        val need = groups.indices.filter { carry[it] }.sumOf { groups[it].d("bytes") }
        val stay = groups.indices.filterNot { carry[it] }
        val room = p.d("room")
        val mirror = p.obj("mirror")
        val mirrorOk = mirror.b("ok")
        var override by remember(p) { mutableStateOf(false) }
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Swap SD card", style = MaterialTheme.typography.titleLarge)
                    Text("Ticked rows are files apps keep using: they are copied to internal storage, checked byte for byte, and go onto the next card. " +
                        "The rest stays on this card, which goes on the shelf; its files are hidden from apps until it is back. Your choices are remembered. " +
                        "Files that stay on the card have only that one copy.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            if (groups.isEmpty()) item { Text("The card holds no app files.", Modifier.padding(16.dp)) }
            itemsIndexed(groups) { i, g ->
                ListItem(
                    leadingContent = { Checkbox(carry[i], { carry[i] = it }) },
                    headlineContent = { Text("${g.s("app_name")} · ${g.s("folder").ifBlank { "(top level)" }}") },
                    supportingContent = { Text("${g.s("type").ifBlank { "(no type)" }} ${KIND[g.s("kind")]} · ${count(g.l("files"))} files · ${bytes(g.d("bytes"))} · " +
                        spaceLabel(g.s("space")) + if (carry[i] != g.b("default")) " · your choice" else "") },
                    modifier = Modifier.clickable { carry[i] = !carry[i] },
                    colors = clearItem(),
                )
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Keep using: ${count(groups.indices.filter { carry[it] }.sumOf { groups[it].l("files") })} files, ${bytes(need)} · " +
                        "stay on the card: ${count(stay.sumOf { groups[it].l("files") })} files, ${bytes(stay.sumOf { groups[it].d("bytes") })}")
                    Text(if (need > room) "Internal storage has room for ${bytes(room)} only. Leave more on the card, or make room first."
                        else "Internal storage has room for ${bytes(room)}.",
                        color = if (need > room) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    if (mirrorOk) Text("A mirror reported a copy ${ago(mirror.d("reported"))}, after the card's last change.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    else {
                        Text((if (mirror.has("reported") && mirror.d("reported") > 0) "The last mirror report (${ago(mirror.d("reported"))}) is older than the card's last change"
                            else "No mirror has ever reported a copy") + ": files that stay on the card would have no second copy.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { override = !override }) {
                            Checkbox(override, { override = it }); Text("Swap anyway")
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(enabled = need <= room && (mirrorOk || override), onClick = {
                            val arr = JSONArray()
                            groups.forEachIndexed { i, g -> arr.put(org.json.JSONObject(g.o.toString()).put("carry", carry[i])) }
                            onDismiss()
                            panel.act("Swap started.", onDone = onStarted) { panel.api.post("/api/cards/swap", body("groups" to arr, "override" to !mirrorOk)) }
                        }) { Text("Start the swap") }
                    }
                }
            }
        }
    }
}

/** Put a shelved card back to work: what rejoins, which version wins, what is put aside, which allocations to raise. */
@Composable
private fun PutBackDialog(cardId: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val panel = LocalPanel.current
    val cmp = rememberLoad(cardId) { get("/api/cards/$cardId/compare") }
    val c = cmp.data
    val raise = remember(c) { mutableStateListOf(*(c?.arr("overruns")?.map { true } ?: emptyList()).toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c?.let { "Put ${it.obj("card").s("name")} back to work" } ?: "Put back to work") },
        text = {
            if (c == null) {
                if (cmp.error != null) Text(cmp.error!!) else CircularProgressIndicator()
            } else Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${count(c.l("rejoin_files"))} files (${bytes(c.d("rejoin_bytes"))}) rejoin their apps.")
                if (c.i("conflict_count") > 0) {
                    Text("Same file on the card and in use: ${count(c.i("conflict_count"))}. The newer one stays; the other goes to the undo.",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    c.arr("conflicts").take(20).forEach { f ->
                        Text("${f.s("app")}/${f.s("key")} · kept: ${if (f.s("keep") == "card") "the card's" else "the one in use"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (c.i("unassigned_count") > 0) {
                    Text("No app or device for them any more: ${count(c.i("unassigned_count"))} files, ${bytes(c.d("unassigned_bytes"))}. " +
                        "They stay on the card, hidden from apps, under Files › SD cards, where you can give them to an app or delete them.",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    c.arr("unassigned").take(20).forEach { f -> Text("${f.s("app")}/${f.s("key")} · ${f.s("why")}", style = MaterialTheme.typography.bodySmall) }
                }
                c.arr("overruns").forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).clickable { raise[i] = !raise[i] }) {
                        Checkbox(raise[i], { raise[i] = it })
                        Text("Raise ${o.s("name")} from ${gb(o.d("alloc"))} to ${gb(o.d("raise_to"))} (it would use ${bytes(o.d("would_use"))})")
                    }
                }
                Text("The SD pool will be ${c.i("sd_pool_gb")} GB, from this card's size.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = c != null, onClick = {
                val ids = JSONArray()
                c?.arr("overruns")?.forEachIndexed { i, o -> if (raise[i]) ids.put(o.s("app")) }
                onDismiss()
                panel.act("Putting the card back to work…", onDone = onStarted) { panel.api.post("/api/cards/$cardId/activate", body("raise" to ids)) }
            }) { Text("Put back to work") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Files › SD cards: what is on shelved cards, restoring single files while that card is in the phone, and
 *  unassigned files on the card in use. d holds "status", "unassigned", "files" and "card" (see FilesScreen). */
fun LazyListScope.cardFilesItems(d: J, apps: List<J>, cardApp: String?, cardPath: String,
                                 onCard: (String) -> Unit, onOpen: (String?, String, String) -> Unit, reload: () -> Unit) {
    val status = d.obj("status")
    val shelf = status.arr("shelf")
    val cardId = d.s("card")
    val names = apps.associate { it.s("id") to it.s("name") }
    if (shelf.isEmpty()) {
        item { Text("No cards on the shelf yet. A card goes on the shelf when you swap it out (Storage › SD cards).",
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        val card = shelf.first { it.s("id") == cardId }
        val slot = status.obj("slot")
        val inserted = slot.obj("card").s("id") == cardId && slot.s("state") in setOf("archive", "shelved_here")
        item {
            Column {
                Chooser("${card.s("name")} · shelved ${dateTime(card.d("shelved"))}", shelf.map { it.s("id") to it.s("name") }) { onCard(it) }
                Text(if (inserted) "This card is in the phone: files can be restored." else "This card is on the shelf: put it in the phone to restore files.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val f = d.obj("files")
        item {
            SectionCard {
                if (cardApp == null) {
                    val spaces = f.arr("spaces")
                    if (spaces.isEmpty()) Text("No app files on ${card.s("name")}.", Modifier.padding(16.dp))
                    spaces.forEach { sp ->
                        ListItem(headlineContent = { Text(sp.s("name")) },
                            supportingContent = { Text("${spaceLabel(sp.s("space"))} · ${count(sp.l("files"))} files · ${bytes(sp.d("size"))}") },
                            modifier = Modifier.clickable { onOpen(sp.s("app"), sp.s("space"), "") }, colors = clearItem())
                    }
                } else {
                    val parts = cardPath.split("/").filter { it.isNotBlank() }
                    ListItem(headlineContent = { Text("${names[cardApp] ?: cardApp} · ${spaceLabel(f.s("space"))}" + if (parts.isEmpty()) "" else " / " + parts.joinToString(" / ")) },
                        supportingContent = { Text("Back") },
                        modifier = Modifier.clickable {
                            if (parts.isEmpty()) onOpen(null, "shared", "") else onOpen(cardApp, f.s("space"), parts.dropLast(1).joinToString("/"))
                        }, colors = clearItem())
                    val items = f.arr("items")
                    if (items.isEmpty()) Text("Empty.", Modifier.padding(16.dp))
                    items.forEach { x ->
                        if (x.s("type") == "dir") ListItem(
                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            headlineContent = { Text(x.s("name")) },
                            supportingContent = { Text("${count(x.l("files"))} files · ${bytes(x.d("size"))}") },
                            modifier = Modifier.clickable { onOpen(cardApp, f.s("space"), (parts + x.s("name")).joinToString("/")) }, colors = clearItem())
                        else ListItem(
                            leadingContent = { Icon(Icons.Outlined.Description, null) },
                            headlineContent = { Text(x.s("name")) },
                            supportingContent = { Text("${bytes(x.d("size"))} · ${dateTime(x.d("modified"))} · ${KIND[x.s("kind")] ?: ""}") },
                            trailingContent = {
                                if (inserted) {
                                    val panel = LocalPanel.current
                                    TextButton(onClick = {
                                        panel.act(null) {
                                            val r = panel.api.post("/api/cards/$cardId/restore", body("app" to cardApp, "space" to f.s("space"), "key" to x.s("key")))
                                            panel.snackbar.showSnackbar(if (r.b("restored")) "Restored ${x.s("key")}." else r.s("reason"))
                                        }
                                    }) { Text("Restore") }
                                }
                            },
                            colors = clearItem())
                    }
                }
            }
        }
    }
    val groups = d.obj("unassigned").arr("groups")
    if (groups.isNotEmpty()) {
        item {
            Text("Unassigned files on the card in use: their app was deleted or their device removed while the card was on the shelf. Apps don't see them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            SectionCard("Unassigned files") {
                groups.forEach { g -> UnassignedRow(g, apps, reload) }
            }
        }
    }
}

@Composable
private fun UnassignedRow(g: J, apps: List<J>, reload: () -> Unit) {
    val panel = LocalPanel.current
    var menu by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(g.s("app")) },
        supportingContent = { Text("${spaceLabel(g.s("space"))} · ${count(g.l("files"))} files · ${bytes(g.d("size"))}") },
        trailingContent = {
            Row {
                Box {
                    TextButton(onClick = { menu = true }, enabled = apps.isNotEmpty()) { Text("Give to app") }
                    DropdownMenu(menu, { menu = false }) {
                        apps.forEach { a ->
                            DropdownMenuItem(text = { Text(a.s("name")) }, onClick = {
                                menu = false
                                panel.act("Files given to ${a.s("name")}.", onDone = reload) {
                                    panel.api.post("/api/cards/unassigned/assign", body("app" to g.s("app"), "space" to g.s("space"), "to" to a.s("id")))
                                }
                            })
                        }
                    }
                }
                TextButton(onClick = { delete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        colors = clearItem(),
    )
    if (delete) ConfirmDialog("Delete unassigned files", "Delete ${count(g.l("files"))} files of ${g.s("app")}? They go into the card's undo first.",
        "Delete", danger = true, onDismiss = { delete = false }) {
        panel.act("Files deleted.", onDone = reload) { panel.api.post("/api/cards/unassigned/delete", body("app" to g.s("app"), "space" to g.s("space"))) }
    }
}
