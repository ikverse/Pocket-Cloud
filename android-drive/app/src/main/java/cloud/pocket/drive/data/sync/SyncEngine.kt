package cloud.pocket.drive.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cloud.pocket.drive.MainActivity
import cloud.pocket.drive.R
import cloud.pocket.drive.data.AppGraph
import cloud.pocket.drive.data.DriveDb
import cloud.pocket.drive.data.KIND_FOLDER
import cloud.pocket.drive.data.KIND_MEDIA
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.Runner
import cloud.pocket.drive.data.STATUS_CONFIRM
import cloud.pocket.drive.data.STATUS_ERROR
import cloud.pocket.drive.data.SyncLog
import cloud.pocket.drive.data.SyncPair
import cloud.pocket.drive.data.SyncedFile
import cloud.pocket.drive.data.TransferEntity
import cloud.pocket.drive.data.Transfers
import cloud.pocket.drive.data.UP
import cloud.pocket.drive.data.UPLOAD_ONLY
import cloud.pocket.drive.data.vault.Crypt
import cloud.pocket.drive.data.vault.realPrefix
import cloud.pocket.drive.shizuku.ShizukuAccess
import cloud.pocket.drive.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a run of one pair did, in numbers. */
data class RunSummary(var uploads: Int = 0, var downloads: Int = 0, var deletesRemote: Int = 0, var deletesLocal: Int = 0,
                      var moves: Int = 0, var conflicts: Int = 0, var adopted: Int = 0) {
    val busy get() = uploads + downloads + deletesRemote + deletesLocal + moves + conflicts > 0
    fun text(): String {
        val parts = buildList {
            if (uploads > 0) add("$uploads to upload")
            if (downloads > 0) add("$downloads to download")
            if (moves > 0) add("$moves moved")
            if (deletesRemote + deletesLocal > 0) add("${deletesRemote + deletesLocal} deleted")
            if (conflicts > 0) add("$conflicts conflicts")
        }
        return if (parts.isEmpty()) "Up to date" else parts.joinToString(", ")
    }
}

/** Looking at what changed on the phone and on the server, and bringing the two in step. One run at a time. */
class SyncEngine(private val g: AppGraph) {
    private val ctx get() = g.app
    private val dao get() = g.db.sync()
    private val hasher = Hasher(g.db)

    private suspend fun log(pair: SyncPair, level: String, text: String) = dao.log(SyncLog(pairId = pair.id, level = level, text = text))

    /** Run one pair. [manual] runs ignore the Wi-Fi and charging conditions. */
    suspend fun runPair(pair0: SyncPair, manual: Boolean): RunSummary? = runLock.withLock {
        val pair = dao.pair(pair0.id) ?: return@withLock null
        if (!manual) {
            if (pair.wifiOnly && !Net.onUnmeteredLink(ctx)) return@withLock skip(pair, "Waiting for Wi-Fi")
            if (pair.chargingOnly && !Net.charging(ctx)) return@withLock skip(pair, "Waiting for the charger")
        }
        try {
            withContext(Dispatchers.IO) { run(pair) }
        } catch (e: ScanProblem) {
            fail(pair, e.message ?: "Can't read the folder")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(pair, Remote.explain(e))
        }
    }

    private suspend fun skip(pair: SyncPair, why: String): RunSummary? {
        dao.pair(pair.id)?.let { dao.updatePair(it.copy(lastResult = why)) }
        return null
    }

    private suspend fun fail(pair: SyncPair, message: String): RunSummary? {
        log(pair, "error", message)
        dao.pair(pair.id)?.let { dao.updatePair(it.copy(lastRun = System.currentTimeMillis(), lastResult = message, status = STATUS_ERROR)) }
        return null
    }

    private suspend fun run(pair: SyncPair): RunSummary {
        val remote = g.remote
        val root = if (pair.kind == KIND_FOLDER) File(pair.localPath) else null
        val protectedFolder = root != null && ShizukuAccess.isProtected(pair.localPath)
        if (protectedFolder && ShizukuAccess.state() != ShizukuState.READY) throw ScanProblem("This folder is closed to apps. Backing it up needs Shizuku: " + ShizukuAccess.state().text)
        if (root != null && !protectedFolder && !Net.folderSyncSupported()) throw ScanProblem("Syncing a folder needs Android 11 or newer. Photos and videos can still be backed up.")
        if (root != null && !protectedFolder && !Net.allFilesAccess()) throw ScanProblem("Pocket Drive needs \"All files access\" to sync a folder. Allow it under Sync, Permissions.")

        // ---- what is where
        var local = if (protectedFolder) (ShizukuAccess.scan(ctx, pair.localPath) ?: throw ScanProblem("Shizuku didn't answer."))
            else if (root != null) FileScanner.scan(root) else MediaScanner.scan(ctx, pair.localPath, pair.mediaScope, pair.byDate)
        // an encrypted pair keeps its files in a vault: names and sizes on the server are the encrypted ones
        val vault = if (pair.vault) g.vaults.containing(pair.bucket, pair.remotePrefix) else null
        val crypt = vault?.let { g.vaults.cipher(it) }
        if (pair.vault && (vault == null || crypt == null)) throw ScanProblem("This sync is encrypted, but its vault password isn't saved on this phone.")
        val realBase = if (vault != null && crypt != null) crypt.realPrefix(vault, pair.remotePrefix) else pair.remotePrefix
        val realKeys = mutableMapOf<String, String>()          // plain path -> key on the server
        val rawRemote = remote.listAll(pair.bucket, realBase)
        var remoteMap = if (crypt == null) rawRemote.filter { !Keys.isHidden(it.key) }.associate {
            it.key.removePrefix(pair.remotePrefix) to RFile(it.size, it.modified, it.etag?.trim('"')?.takeIf { e -> e.length == 32 })
        } else {
            val map = linkedMapOf<String, RFile>()
            for (f in rawRemote) {
                val plain = crypt.decryptPath(f.key.removePrefix(realBase)) ?: continue
                if (Keys.name(plain) == Keys.KEEP) continue
                val size = Crypt.decryptedSize(f.size) ?: continue
                map[plain] = RFile(size, f.modified, null)       // the checksum on the server is of the encrypted bytes: no use here
                realKeys[plain] = f.key
            }
            map
        }
        fun realKey(rel: String) = realKeys[rel] ?: (realBase + (crypt?.encryptPath(rel) ?: rel))
        val vaultId = vault?.id ?: 0L
        var base = dao.synced(pair.id).associate { it.path to Base(it.localSize, it.localMtime, it.remoteSize, it.remoteMtime, it.etag) }

        // files still being written are left out of this run on every side
        val now = System.currentTimeMillis()
        val unstable = local.values.filter { now - it.mtime < 3000 }.map { it.rel }.toSet()
        if (unstable.isNotEmpty()) {
            local = local - unstable
            remoteMap = remoteMap - unstable
            base = base - unstable
        }
        val unsafe = (local.keys + remoteMap.keys).filter { it.split('/').any { p -> p == ".." } || it.startsWith("/") }.toSet()
        if (unsafe.isNotEmpty()) {
            log(pair, "warn", "Skipped ${unsafe.size} file(s) with unsafe names.")
            local = local - unsafe; remoteMap = remoteMap - unsafe; base = base - unsafe
        }

        // ---- the plan
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val localFiles = local.mapValues { LFile(it.value.size, it.value.mtime) }
        val plan = Planner.plan(localFiles, remoteMap, base, PlanConfig(if (protectedFolder) UPLOAD_ONLY else pair.mode, pair.newestWins, g.settings.deviceName, today, pair.decision)) { path ->
            if (protectedFolder) null else local[path]?.let { hasher.md5(ctx, it) }
        }
        if (plan.skipped.isNotEmpty()) log(pair, "warn", "Left ${plan.skipped.size} files alone because their names differ only by capital letters: ${plan.skipped.take(3).joinToString()}")

        // ---- a decision about deletes, then the guard
        var fresh = dao.pair(pair.id) ?: pair
        if (pair.decision.isNotEmpty()) fresh = fresh.copy(decision = "", status = "", pendingDeletes = 0, pendingFrom = "")
        val guard = plan.guard
        if (guard != null) {
            fresh = fresh.copy(status = STATUS_CONFIRM, pendingDeletes = guard.deletes, pendingFrom = guard.from)
            if (pair.status != STATUS_CONFIRM) {
                val where = when (guard.from) { "local" -> "deleted on this phone"; "remote" -> "deleted on the server"; else -> "deleted" }
                log(pair, "warn", "${guard.deletes} files were $where. Nothing was deleted: waiting for your answer.")
                SyncNotifier.attention(ctx, pair.id.toInt(), "${pair.name}: ${guard.deletes} files to delete", "Open Pocket Drive to keep or delete them.")
            }
        } else if (fresh.status == STATUS_CONFIRM || fresh.status == STATUS_ERROR) {
            fresh = fresh.copy(status = "", pendingDeletes = 0, pendingFrom = "")
        }

        // ---- carry it out
        val s = RunSummary()
        val deleteKeys = mutableListOf<String>()
        var queued = false
        for (a in plan.actions) {
            when (a) {
                is Action.Upload -> {
                    val e = local[a.path] ?: continue
                    val source = if (protectedFolder) ShizukuAccess.stage(ctx, e.source)?.path ?: continue else e.source
                    Transfers.enqueueUpload(g, pair.bucket, realKey(a.path), source, Keys.name(a.path), e.size, e.mtime, pair.id, a.path, vaultId)
                    s.uploads++; queued = true
                }
                is Action.Download -> {
                    if (root == null) continue
                    val r = remoteMap[a.path] ?: continue
                    Transfers.enqueueDownload(g, pair.bucket, realKey(a.path), if (vaultId > 0) Crypt.encryptedSize(r.size) else r.size,
                        destPath = File(root, a.path).path, pairId = pair.id, rel = a.path, etag = r.etag ?: "", vaultId = vaultId, name = Keys.name(a.path))
                    s.downloads++; queued = true
                }
                is Action.DeleteRemote -> { deleteKeys += realKey(a.path); s.deletesRemote++ }
                is Action.DeleteLocal -> {
                    if (root != null && LocalTrash.put(root, a.path)) { dao.forget(pair.id, a.path); s.deletesLocal++ }
                }
                is Action.MoveRemote -> {
                    val toKey = realBase + (crypt?.encryptPath(a.to) ?: a.to)
                    remote.move(pair.bucket, realKey(a.from), toKey)
                    val head = remote.head(pair.bucket, toKey)
                    val e = local[a.to]
                    dao.forget(pair.id, a.from)
                    if (e != null && head != null) dao.putSynced(SyncedFile(pairId = pair.id, path = a.to, localSize = e.size, localMtime = e.mtime,
                        remoteSize = if (vaultId > 0) Crypt.decryptedSize(head.size) ?: head.size else head.size, remoteMtime = head.modified,
                        etag = if (vaultId > 0) "" else head.etag?.trim('"') ?: ""))
                    s.moves++
                    log(pair, "info", "Moved ${a.from} to ${a.to} on the server.")
                }
                is Action.MoveLocal -> {
                    if (root == null) continue
                    val from = File(root, a.from)
                    val to = File(root, a.to)
                    to.parentFile?.mkdirs()
                    if (from.isFile && !to.exists() && from.renameTo(to)) {
                        val r = remoteMap[a.to]
                        dao.forget(pair.id, a.from)
                        if (r != null) dao.putSynced(SyncedFile(pairId = pair.id, path = a.to, localSize = to.length(), localMtime = to.lastModified(),
                            remoteSize = r.size, remoteMtime = r.mtime, etag = r.etag ?: ""))
                        LocalTrash.removeEmptyDirs(from.parentFile, root)
                        s.moves++
                        log(pair, "info", "Moved ${a.from} to ${a.to} here.")
                    }
                }
                is Action.Conflict -> {
                    if (root == null) continue
                    val e = local[a.path] ?: continue
                    val r = remoteMap[a.path] ?: continue
                    val renamed = File(root, a.conflictPath)
                    renamed.parentFile?.mkdirs()
                    if (File(e.source).renameTo(renamed)) {
                        Transfers.enqueueDownload(g, pair.bucket, realKey(a.path), if (vaultId > 0) Crypt.encryptedSize(r.size) else r.size,
                            destPath = File(root, a.path).path, pairId = pair.id, rel = a.path, etag = r.etag ?: "", vaultId = vaultId, name = Keys.name(a.path))
                        Transfers.enqueueUpload(g, pair.bucket, realKey(a.conflictPath), renamed.path, Keys.name(a.conflictPath), renamed.length(),
                            renamed.lastModified(), pair.id, a.conflictPath, vaultId)
                        s.conflicts++; queued = true
                        log(pair, "conflict", "${a.path} changed on both sides. Your copy was kept as ${Keys.name(a.conflictPath)}.")
                    }
                }
                is Action.Adopt -> {
                    val e = local[a.path]
                    val r = remoteMap[a.path]
                    if (e != null && r != null) dao.putSynced(SyncedFile(pairId = pair.id, path = a.path, localSize = e.size, localMtime = e.mtime,
                        remoteSize = r.size, remoteMtime = r.mtime, etag = r.etag ?: ""))
                    s.adopted++
                }
                is Action.Forget -> dao.forget(pair.id, a.path)
            }
        }
        if (deleteKeys.isNotEmpty()) {
            remote.deleteMany(pair.bucket, deleteKeys)
            plan.actions.filterIsInstance<Action.DeleteRemote>().forEach { dao.forget(pair.id, it.path) }
            log(pair, "info", "Deleted ${deleteKeys.size} file(s) on the server (they stay in the server's undo for a while).")
        }
        if (root != null && !protectedFolder) LocalTrash.purge(root)
        ShizukuAccess.cleanStage(ctx)
        if (queued) Runner.kick(ctx)

        val summary = if (guard != null) "${s.text()}; waiting for you: ${guard.deletes} deletions" else s.text()
        dao.pair(pair.id)?.let { latest ->
            dao.updatePair(latest.copy(lastRun = System.currentTimeMillis(), lastResult = summary, status = fresh.status, pendingDeletes = fresh.pendingDeletes,
                pendingFrom = fresh.pendingFrom, decision = if (pair.decision.isNotEmpty() && latest.decision == pair.decision) "" else latest.decision))
        }
        if (s.busy) log(pair, "info", "Run: ${s.text()}.")
        return s
    }

    companion object {
        private val runLock = Mutex()
    }
}

/** Keeps the sync's records in step with what the transfer queue finishes. */
object SyncHooks {
    /** Wi-Fi-only pairs don't use mobile data, even for transfers that were queued earlier. */
    suspend fun allowed(ctx: Context, db: DriveDb, t: TransferEntity): Boolean {
        val pair = db.sync().pair(t.pairId) ?: return true
        return !(pair.wifiOnly && !Net.onUnmeteredLink(ctx))
    }

    /** A transfer for a pair finished: both sides now match, as of what was transferred. */
    suspend fun completed(db: DriveDb, remote: Remote, t: TransferEntity) {
        val dao = db.sync()
        val pair = dao.pair(t.pairId) ?: return
        if (t.rel.isEmpty()) return
        if (t.kind == UP) {
            val head = remote.head(t.bucket, t.key) ?: return
            // what was read at upload time: if the file changed since, the next run sees it as changed
            dao.putSynced(SyncedFile(pairId = pair.id, path = t.rel, localSize = t.size, localMtime = t.mtime,
                remoteSize = if (t.vaultId > 0) Crypt.decryptedSize(head.size) ?: head.size else head.size, remoteMtime = head.modified,
                etag = if (t.vaultId > 0) "" else head.etag?.trim('"') ?: ""))
        } else {
            val dest = File(t.localUri)
            dao.putSynced(SyncedFile(pairId = pair.id, path = t.rel, localSize = dest.length(), localMtime = dest.lastModified(),
                remoteSize = if (t.vaultId > 0) dest.length() else t.size, remoteMtime = dest.lastModified(), etag = if (t.vaultId > 0) "" else t.etag))
        }
        // the last transfer of the pair is done: the pair is in step
        if (db.transfers().pendingForPair(pair.id) == 0) {
            dao.pair(pair.id)?.takeIf { it.status != STATUS_CONFIRM && it.status != STATUS_ERROR }
                ?.let { dao.updatePair(it.copy(lastRun = System.currentTimeMillis(), lastResult = "Up to date")) }
        }
    }
}

/** Notifications that need the user (a paused deletion, a pair that can't run). */
object SyncNotifier {
    const val CHANNEL = "sync"

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Sync problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Sync needs your answer, or can't run"
            })
    }

    fun attention(ctx: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).putExtra("open", "sync")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(9000 + id, n) }      // fails quietly without the notification permission
    }
}
