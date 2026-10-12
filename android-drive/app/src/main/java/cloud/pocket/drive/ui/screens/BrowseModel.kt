package cloud.pocket.drive.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.DEVICE
import cloud.pocket.drive.data.Entry
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.Local
import cloud.pocket.drive.data.Picked
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.Runner
import cloud.pocket.drive.data.SHARED
import cloud.pocket.drive.data.Transfers
import cloud.pocket.drive.data.Vault
import cloud.pocket.drive.data.vault.Crypt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class Sort(val label: String) { NAME("Name"), DATE("Date modified"), SIZE("Size") }

/** What the Browse screen shows and does: one folder of one bucket, with the actions on its items. */
class BrowseModel(app: Application) : AndroidViewModel(app) {
    private val g get() = getApplication<DriveApplication>().graph

    var bucket by mutableStateOf(SHARED); private set
    var prefix by mutableStateOf(""); private set
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var sort by mutableStateOf(Sort.NAME)
    var ascending by mutableStateOf(true)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var busy by mutableStateOf<String?>(null); private set
    var previewFile by mutableStateOf<File?>(null); private set
    /** Showing the files kept offline, instead of the drive. */
    var offlineMode by mutableStateOf(false)
    /** Showing the server's trash. */
    var trashMode by mutableStateOf(false)
    /** Showing a search over the whole drive. */
    var globalSearch by mutableStateOf(false)
    /** The vault the shown folder belongs to (its contents are encrypted), if any. */
    var vault by mutableStateOf<Vault?>(null); private set
    var vaults by mutableStateOf<List<Vault>>(emptyList()); private set
    /** A vault whose password isn't saved on this phone: the screen asks for it. */
    var locked by mutableStateOf<Vault?>(null); private set

    private class Locked(val vault: Vault) : Exception("This vault's password isn't saved on this phone.")

    /** One-line messages for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Intents the screen should start (open with, share). */
    val intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)

    private var connected = false
    private var generation = 0

    init {
        viewModelScope.launch { g.db.vaults().observe().collect { vaults = it } }
        reload()
        // When transfers finish, what's on the server has changed: show it.
        viewModelScope.launch {
            var last = 0
            g.db.transfers().activeCount().collect { n ->
                if (n < last) reload()
                last = n
            }
        }
    }

    // ---------------------------------------------------------------- navigating

    fun showBucket(b: String) {
        if (b == bucket) return
        bucket = b
        prefix = ""
        clearSelection()
        reload()
    }

    fun open(p: String) {
        prefix = p
        query = ""
        searching = false
        clearSelection()
        reload()
    }

    /** One folder up. False when already at the top. */
    fun up(): Boolean {
        if (prefix.isEmpty()) return false
        open(Keys.parent(prefix))
        return true
    }

    /** Show the folder that holds [key] of [bucket] (from a search result). */
    fun goTo(bucket: String, key: String) {
        this.bucket = bucket
        globalSearch = false
        offlineMode = false
        searching = false
        open(Keys.parent(key))
    }

    /** A file was brought back from an old version: say so and show it. */
    fun restored(name: String) {
        messages.tryEmit("$name restored")
        reload()
    }

    fun togglePin(e: Entry, pinned: Boolean) {
        viewModelScope.launch {
            try {
                if (pinned) g.offline.unpin(bucket, e.key) else g.offline.pin(bucket, e.key)
                messages.tryEmit(if (pinned) "Offline copy removed" else "Keeping ${e.name} offline")
            } catch (ex: Exception) {
                messages.tryEmit(Remote.explain(ex))
            }
        }
    }

    suspend fun searchAll(q: String) = g.search.search(q)

    fun refreshIndex() {
        viewModelScope.launch {
            try { g.search.refresh(); messages.tryEmit("Search list updated") } catch (ex: Exception) { messages.tryEmit(Remote.explain(ex)) }
        }
    }

    fun openLocal(path: String, name: String) {
        val file = File(path)
        if (!file.isFile) { messages.tryEmit("That copy is missing"); return }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), Remote.mimeOf(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        intents.tryEmit(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun reload() {
        val mine = ++generation
        loading = true
        error = null
        viewModelScope.launch {
            try {
                if (!connected) { g.remote.connect(); connected = true }
                val list = listFolder(bucket, prefix)
                if (mine == generation) { entries = list; locked = null }
            } catch (e: Locked) {
                if (mine == generation) { locked = e.vault; entries = emptyList(); error = e.message }
            } catch (e: Exception) {
                connected = false
                if (mine == generation) error = Remote.explain(e)
            } finally {
                if (mine == generation) loading = false
            }
        }
    }

    // ---------------------------------------------------------------- vaults

    private suspend fun cipherOf(v: Vault): Crypt? = withContext(Dispatchers.Default) { g.vaults.cipher(v) }

    /** The vault of the shown folder and its cipher; null outside vaults. Throws [Locked] when the password is missing. */
    private suspend fun vaultCtx(): Pair<Vault, Crypt>? {
        val v = g.vaults.containing(bucket, prefix) ?: return null
        return v to (cipherOf(v) ?: throw Locked(v))
    }

    /** The key prefix in the drive that holds the logical folder [logical] of vault [v]. */
    private fun realPrefix(v: Vault, crypt: Crypt, logical: String): String {
        val rel = logical.removePrefix(v.prefix).trim('/')
        return if (rel.isEmpty()) v.prefix else v.prefix + crypt.encryptPath(rel) + "/"
    }

    /** A folder as the user sees it: inside a vault the names are decrypted. */
    private suspend fun listFolder(b: String, p: String): List<Entry> {
        val v = g.vaults.containing(b, p)
        vault = v
        if (v == null) return g.remote.list(b, p)
        val crypt = cipherOf(v) ?: throw Locked(v)
        val raw = g.remote.list(b, realPrefix(v, crypt, p))
        val out = mutableListOf<Entry>()
        var failed = 0
        for (e in raw) {
            val plain = crypt.decryptSegment(e.name)
            if (plain == null) { failed++; continue }
            if (plain == Keys.KEEP) continue
            out += if (e.isDir) e.copy(name = plain, path = Keys.folder(p) + plain + "/")
            else e.copy(name = plain, size = Crypt.decryptedSize(e.size) ?: e.size, path = Keys.folder(p) + plain)
        }
        if (out.isEmpty() && failed > 0) throw IOException("These names can't be decrypted. Is the vault password right?")
        return out
    }

    /** Make the folder [name] here a new vault. */
    fun createVault(name: String, password: String) = operation("Creating vault…") {
        Keys.problem(name)?.let { return@operation it }
        if (entries.any { it.name.equals(name, ignoreCase = true) }) return@operation "\"$name\" already exists here."
        val folder = prefix + name + "/"
        val v = g.vaults.add(bucket, folder, name, password)
        val crypt = cipherOf(v)!!
        g.remote.putSmall(bucket, folder + crypt.encryptSegment(Keys.KEEP), crypt.header(crypt.newNonce()), 0)
        "Vault created. Keep the password safe: without it the files can't be recovered."
    }

    /** Treat an existing folder, already encrypted by rclone or this app, as a vault. */
    fun useAsVault(e: Entry, password: String) {
        viewModelScope.launch {
            g.vaults.add(bucket, e.key, e.name, password)
            messages.tryEmit("${e.name} is now a vault")
            reload()
        }
    }

    fun removeVault(v: Vault) {
        viewModelScope.launch {
            g.vaults.remove(v)
            messages.tryEmit("Vault link removed. Its files are still on the server, encrypted.")
            reload()
        }
    }

    fun unlock(v: Vault, password: String) {
        viewModelScope.launch {
            g.vaults.add(v.bucket, v.prefix, v.name, password)
            locked = null
            reload()
        }
    }

    /** The entries shown: filtered by the search text and sorted, folders first. */
    fun visible(): List<Entry> {
        val q = query.trim()
        val shown = if (q.isEmpty()) entries else entries.filter { it.name.contains(q, ignoreCase = true) }
        val by: Comparator<Entry> = when (sort) {
            Sort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.DATE -> compareBy { it.modified }
            Sort.SIZE -> compareBy { it.size }
        }
        val ordered = if (ascending) by else by.reversed()
        return shown.sortedWith(compareByDescending<Entry> { it.isDir }.then(ordered))
    }

    // ---------------------------------------------------------------- selecting

    fun toggle(key: String) { selected = if (key in selected) selected - key else selected + key }
    fun clearSelection() { selected = emptySet() }
    fun selectAll() { selected = visible().map { it.key }.toSet() }
    fun selectedEntries(): List<Entry> = entries.filter { it.key in selected }

    // ---------------------------------------------------------------- doing things

    private fun operation(start: String, block: suspend () -> String?) {
        viewModelScope.launch {
            busy = start
            try {
                block()?.let { messages.tryEmit(it) }
            } catch (e: Exception) {
                messages.tryEmit(Remote.explain(e))
            } finally {
                busy = null
                reload()
            }
        }
    }

    fun newFolder(name: String) = operation("Creating folder…") {
        Keys.problem(name)?.let { return@operation it }
        if (entries.any { it.name.equals(name, ignoreCase = true) }) return@operation "\"$name\" already exists here."
        val vc = vaultCtx()
        if (vc == null) g.remote.createFolder(bucket, prefix + name)
        else {
            val (v, crypt) = vc
            val dir = realPrefix(v, crypt, prefix) + crypt.encryptSegment(name) + "/"
            g.remote.putSmall(bucket, dir + crypt.encryptSegment(Keys.KEEP), crypt.header(crypt.newNonce()), 0)
        }
        "Folder created"
    }

    fun delete(list: List<Entry>) = operation("Deleting…") {
        val keys = mutableListOf<String>()
        for (e in list) {
            if (e.isDir) keys += g.remote.listAll(bucket, e.key).map { it.key } else keys += e.key
        }
        g.remote.deleteMany(bucket, keys) { n -> busy = "Deleting $n of ${keys.size}" }
        clearSelection()
        if (list.size == 1) "Deleted ${list[0].name}" else "Deleted ${list.size} items"
    }

    fun rename(e: Entry, newName: String) = operation("Renaming…") {
        Keys.problem(newName)?.let { return@operation it }
        if (newName == e.name) return@operation null
        if (entries.any { it.name.equals(newName, ignoreCase = true) && !it.name.equals(e.name, ignoreCase = true) })
            return@operation "\"$newName\" already exists here."
        val segment = vaultCtx()?.second?.encryptSegment(newName) ?: newName
        val to = Keys.parent(e.key) + segment + if (e.isDir) "/" else ""
        moveOne(e, to)
        clearSelection()
        "Renamed to $newName"
    }

    /** Move the selected items into [destPrefix] of the same bucket. */
    fun move(list: List<Entry>, destPrefix: String) = operation("Moving…") {
        val vc = vaultCtx()
        if (g.vaults.containing(bucket, destPrefix)?.id != vc?.first?.id) return@operation "Files can't be moved into or out of a vault."
        val destReal = if (vc == null) destPrefix else realPrefix(vc.first, vc.second, destPrefix)
        val taken = listFolder(bucket, destPrefix).map { it.name.lowercase() }.toSet()
        for (e in list) {
            if (e.isDir && destPrefix.startsWith(e.path)) return@operation "Can't move ${e.name} into itself."
            if (Keys.parent(e.path) == destPrefix) return@operation "${e.name} is already in that folder."
            if (e.name.lowercase() in taken) return@operation "${e.name} already exists in that folder."
        }
        for ((i, e) in list.withIndex()) {
            busy = "Moving ${i + 1} of ${list.size}"
            moveOne(e, destReal + Keys.name(e.key) + if (e.isDir) "/" else "")
        }
        clearSelection()
        if (list.size == 1) "Moved ${list[0].name}" else "Moved ${list.size} items"
    }

    private suspend fun moveOne(e: Entry, to: String) {
        val caseOnly = to.equals(e.key, ignoreCase = true)
        if (caseOnly) {
            // On a case-insensitive card "A.txt" and "a.txt" are one file: go through a different name.
            val tmp = Keys.parent(e.key) + ".pocket-rename-" + UUID.randomUUID().toString().take(8) + if (e.isDir) "/" else ""
            moveOne(e, tmp)
            moveOne(e.copy(key = tmp), to)
            return
        }
        val remote = g.remote
        if (!e.isDir) { remote.move(bucket, e.key, to); return }
        val files = remote.listAll(bucket, e.key)
        for ((i, f) in files.withIndex()) {
            busy = "Copying ${i + 1} of ${files.size}"
            remote.copy(bucket, f.key, Keys.moved(f.key, e.key, to))
        }
        if (files.isEmpty()) remote.createFolder(bucket, to)
        remote.deleteMany(bucket, files.map { it.key })
    }

    // ---------------------------------------------------------------- transfers

    fun uploadPicked(list: List<Picked>) {
        viewModelScope.launch {
            var n = 0
            val vc = try { vaultCtx() } catch (e: Locked) { messages.tryEmit(e.message ?: "Vault locked"); return@launch }
            val base = if (vc == null) prefix else realPrefix(vc.first, vc.second, prefix)
            for (p in list) {
                val key = base + (vc?.second?.encryptSegment(p.name) ?: p.name)
                if (Transfers.enqueueUpload(g, bucket, key, p.uri, p.name, p.size, p.modified, vaultId = vc?.first?.id ?: 0)) n++
            }
            Runner.kick(getApplication(), userInitiated = true)
            messages.tryEmit(queuedMessage("Uploading", n, list.size - n))
        }
    }

    /** "Uploading 3 files (1 already in the queue)", or only the second part when nothing new was queued. */
    private fun queuedMessage(verb: String, queued: Int, skipped: Int): String {
        val dup = if (skipped > 0) (if (skipped == 1) "1 file is already in the queue" else "$skipped files are already in the queue") else ""
        return when {
            queued == 0 && skipped > 0 -> dup.replaceFirstChar { it.uppercase() }
            queued == 0 -> "Nothing to ${verb.lowercase().removeSuffix("ing")}"
            else -> "$verb $queued file${if (queued == 1) "" else "s"}" + if (dup.isNotEmpty()) " ($dup)" else ""
        }
    }

    fun uploadTree(tree: Uri) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { Local.walkTree(getApplication(), tree) }
            var n = 0
            val vc = try { vaultCtx() } catch (e: Locked) { messages.tryEmit(e.message ?: "Vault locked"); return@launch }
            val base = if (vc == null) prefix else realPrefix(vc.first, vc.second, prefix)
            for ((rel, p) in files) {
                val key = base + (vc?.second?.encryptPath(rel) ?: rel)
                if (Transfers.enqueueUpload(g, bucket, key, p.uri, p.name, p.size, p.modified, vaultId = vc?.first?.id ?: 0)) n++
            }
            Runner.kick(getApplication(), userInitiated = true)
            messages.tryEmit(if (files.isEmpty()) "That folder has no files" else queuedMessage("Uploading", n, files.size - n))
        }
    }

    fun download(list: List<Entry>) {
        viewModelScope.launch {
            try {
                var n = 0
                var tried = 0
                val queued: (Boolean) -> Unit = { tried++; if (it) n++ }
                val vc = vaultCtx()
                for (e in list) {
                    if (e.isDir) {
                        val base = e.name
                        for (f in g.remote.listAll(bucket, e.key)) {
                            if (vc == null) {
                                val sub = (base + "/" + Keys.parent(f.key.removePrefix(e.key))).trimEnd('/')
                                queued(Transfers.enqueueDownload(g, bucket, f.key, f.size, sub))
                            } else {
                                val plain = vc.second.decryptPath(f.key.removePrefix(e.key)) ?: continue
                                if (Keys.name(plain) == Keys.KEEP) continue
                                val sub = (base + "/" + Keys.parent(plain)).trimEnd('/')
                                queued(Transfers.enqueueDownload(g, bucket, f.key, f.size, sub, vaultId = vc.first.id, name = Keys.name(plain)))
                            }
                        }
                    } else if (vc == null) queued(Transfers.enqueueDownload(g, bucket, e.key, e.size))
                    else queued(Transfers.enqueueDownload(g, bucket, e.key, Crypt.encryptedSize(e.size), vaultId = vc.first.id, name = e.name))
                }
                Runner.kick(getApplication(), userInitiated = true)
                messages.tryEmit(queuedMessage("Downloading", n, tried - n) + if (n > 0) " to Downloads/Pocket Drive" else "")
                clearSelection()
            } catch (ex: Exception) {
                messages.tryEmit(Remote.explain(ex))
            }
        }
    }

    // ---------------------------------------------------------------- open, share, preview

    /** The file's data in the app's cache (fetched when not already there). */
    suspend fun fetch(e: Entry, tick: (Long) -> Unit = {}): File = fetchToCache(e, tick)

    /** A temporary link a media player can stream from. */
    suspend fun streamUrl(e: Entry): String = g.remote.presign(bucket, e.key)

    /** Inside a vault nothing can be streamed (the server only holds encrypted bytes): fetch, decrypt, play the local file. */
    suspend fun playableSource(e: Entry): String =
        if (vault != null) Uri.fromFile(fetch(e)).toString() else streamUrl(e)

    private suspend fun fetchToCache(e: Entry, tick: (Long) -> Unit = {}): File {
        val dir = File(getApplication<Application>().cacheDir, "share/" + (bucket + "/" + e.key + "/" + e.etag + "/" + e.modified).hashCode().toUInt().toString(16))
        val file = File(dir, e.name)
        if (file.isFile && file.length() == e.size) return file
        file.delete()
        val vc = vaultCtx()
        if (vc == null) g.remote.downloadTo(bucket, e.key, file, 0) { tick(it) }
        else {
            val raw = File(dir, e.name + ".enc")
            g.remote.downloadTo(bucket, e.key, raw, 0) { tick(it) }
            withContext(Dispatchers.IO) {
                raw.inputStream().buffered().use { i -> file.outputStream().buffered().use { o -> vc.second.decrypt(i, o) } }
                raw.delete()
            }
        }
        if (e.modified > 0) file.setLastModified(e.modified)
        return file
    }

    private fun uriFor(f: File): Uri =
        FileProvider.getUriForFile(getApplication(), getApplication<Application>().packageName + ".files", f)

    fun openWith(e: Entry) {
        viewModelScope.launch {
            busy = "Fetching ${e.name}…"
            try {
                val f = fetchToCache(e) { busy = "Fetching ${e.name}… ${it * 100 / e.size.coerceAtLeast(1)}%" }
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), Remote.mimeOf(e.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                intents.tryEmit(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ex: Exception) {
                messages.tryEmit(Remote.explain(ex))
            } finally {
                busy = null
            }
        }
    }

    fun share(list: List<Entry>) {
        val files = list.filter { !it.isDir }
        if (files.isEmpty()) { messages.tryEmit("Folders can't be shared; pick files."); return }
        viewModelScope.launch {
            try {
                val uris = ArrayList<Uri>()
                for ((i, e) in files.withIndex()) {
                    busy = "Fetching ${i + 1} of ${files.size}…"
                    uris += uriFor(fetchToCache(e))
                }
                val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                send.type = if (files.size == 1) Remote.mimeOf(files[0].name) else "*/*"
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intents.tryEmit(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                clearSelection()
            } catch (ex: Exception) {
                messages.tryEmit(Remote.explain(ex))
            } finally {
                busy = null
            }
        }
    }

    /** Fetch a small image so the details sheet can show it. */
    fun loadPreview(e: Entry) {
        previewFile = null
        if (e.isDir || e.size > 20L * 1024 * 1024) return
        viewModelScope.launch {
            previewFile = runCatching { fetchToCache(e) }.getOrNull()
        }
    }

    fun clearPreview() { previewFile = null }

    suspend fun foldersOf(bucket: String, prefix: String): List<Entry> = listFolder(bucket, prefix).filter { it.isDir }.sortedBy { it.name.lowercase() }

    fun bucketIsDevice() = bucket == DEVICE
}
