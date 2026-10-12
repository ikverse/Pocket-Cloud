package cloud.pocket.drive.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cloud.pocket.PocketException
import cloud.pocket.drive.MainActivity
import cloud.pocket.drive.R
import cloud.pocket.drive.data.sync.SyncHooks
import cloud.pocket.drive.data.vault.Crypt
import cloud.pocket.drive.data.vault.EncryptedSource
import cloud.pocket.drive.data.vault.VaultStore
import aws.sdk.kotlin.services.s3.model.NoSuchUpload
import aws.sdk.kotlin.services.s3.model.S3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The small decisions of the queue, kept apart from the database and network so they can be tested. */
object Resume {
    /** Finished multipart parts as stored in the database: "1:etag,2:etag". */
    fun encode(parts: Map<Int, String>): String = parts.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decode(text: String): Map<Int, String> =
        text.split(',').filter { it.contains(':') }.associate { it.substringBefore(':').toInt() to it.substringAfter(':') }

    /** Seconds-long waits that double with every failed attempt, up to 15 minutes. */
    fun backoffMs(attempts: Int): Long = minOf(30_000L shl attempts.coerceIn(0, 10), 15 * 60_000L)

    /** (offset, length) of every part of an upload of [size] bytes. */
    fun parts(size: Long, partSize: Long): List<Pair<Long, Int>> {
        if (size <= 0) return emptyList()
        val n = ((size + partSize - 1) / partSize).toInt()
        return List(n) { i -> val off = i * partSize; off to minOf(partSize, size - off).toInt() }
    }
}

/** What the engine is doing right now, for the progress notification. */
data class Status(val name: String, val done: Long, val total: Long, val index: Int, val count: Int, val upload: Boolean)

/** What one run of the queue achieved, for the notification that tells the user how it went. */
class Batch {
    var uploaded = 0
    var downloaded = 0
    var retrying = 0
    private var retryReason: String? = null
    val failed = mutableListOf<Pair<String, String>>()      // name to reason

    val isEmpty get() = uploaded + downloaded + retrying + failed.size == 0
    val hasProblems get() = failed.isNotEmpty() || retrying > 0

    fun add(t: TransferEntity) {
        when {
            t.state == DONE -> if (t.kind == UP) uploaded++ else downloaded++
            t.state == FAILED -> failed += t.name to (t.error ?: "Failed")
            t.state == QUEUED && t.error != null -> { retrying++; retryReason = t.error }
        }
    }

    fun title(): String = when {
        failed.isNotEmpty() -> if (failed.size == 1) "A transfer failed" else "${failed.size} transfers failed"
        retrying > 0 -> if (retrying == 1) "A transfer will be retried" else "$retrying transfers will be retried"
        uploaded > 0 && downloaded > 0 -> "Transfers finished"
        uploaded > 0 -> if (uploaded == 1) "1 file uploaded" else "$uploaded files uploaded"
        else -> if (downloaded == 1) "1 file downloaded" else "$downloaded files downloaded"
    }

    fun text(): String {
        val parts = mutableListOf<String>()
        if (failed.isNotEmpty()) {
            val (name, why) = failed.first()
            parts += "$name: $why" + if (failed.size > 1) " (and ${failed.size - 1} more)" else ""
        }
        if (retrying > 0) parts += (if (failed.isEmpty()) retryReason ?: "Trying again soon" else "$retrying will be retried")
        val done = listOfNotNull(uploaded.takeIf { it > 0 }?.let { "$it uploaded" }, downloaded.takeIf { it > 0 }?.let { "$it downloaded" })
        if (done.isNotEmpty() && hasProblems) parts += done.joinToString(", ")
        else if (done.isNotEmpty()) return done.joinToString(", ")
        return parts.joinToString(" · ")
    }
}

sealed interface Drained {
    data object Idle : Drained
    /** Some transfers are waiting to be retried; try again at [at] (ms). */
    data class RetryLater(val at: Long) : Drained
}

/** Runs the transfer queue, one transfer at a time, and keeps each transfer's state in the database. */
class TransferEngine(private val ctx: Context, private val remote: Remote, private val db: DriveDb, private val vaults: VaultStore) {
    private val dao get() = db.transfers()
    private val drainLock = Mutex()
    private val active = ConcurrentHashMap<Long, Job>()
    private val canceled = ConcurrentHashMap.newKeySet<Long>()
    private val pausing = ConcurrentHashMap.newKeySet<Long>()
    private val _current = MutableStateFlow<Status?>(null)

    /** What is being transferred right now (null when idle), whichever part of the app is running the queue. */
    val current: StateFlow<Status?> = _current

    /** Work through everything that is ready, then report whether anything is still waiting. */
    suspend fun drain(onStatus: suspend (Status) -> Unit = {}): Drained = drainLock.withLock {
        var index = 0
        val batch = Batch()
        val report: suspend (Status) -> Unit = { s -> _current.value = s; onStatus(s) }
        try {
            while (true) {
                val t = dao.nextReady(System.currentTimeMillis()) ?: break
                index++
                val count = index + dao.queuedCount() - 1
                execute(t, index, count, report)
                if (t.pairId == 0L) dao.get(t.id)?.let { batch.add(it) }      // sync and offline copies have their own reports
            }
        } finally {
            _current.value = null
        }
        if (!batch.isEmpty) TransferNotifier.finished(ctx, batch)
        val waiting = dao.earliestQueued()
        if (dao.queuedCount() > 0 && waiting != null) Drained.RetryLater(waiting) else Drained.Idle
    }

    /** Stop a running transfer, or drop a waiting one. */
    suspend fun cancel(id: Long) {
        val job = active[id]
        if (job != null) {
            canceled.add(id)
            job.cancel()
        } else {
            dao.get(id)?.takeIf { it.state in setOf(QUEUED, PAUSED) }?.let { dao.update(it.copy(state = CANCELED, finishedAt = System.currentTimeMillis())) }
        }
    }

    /** Hold everything: waiting transfers stop being picked, and the running one stops (it continues when resumed). */
    suspend fun pauseAll() {
        dao.pauseQueued()
        active.keys.toList().forEach { id -> pausing.add(id); active[id]?.cancel() }
    }

    suspend fun resumeAll() = dao.resumeAll()

    suspend fun cancelAll() {
        active.keys.toList().forEach { id -> canceled.add(id); active[id]?.cancel() }
        while (true) {
            val t = dao.nextReady(Long.MAX_VALUE) ?: break
            dao.update(t.copy(state = CANCELED, finishedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun execute(t0: TransferEntity, index: Int, count: Int, onStatus: suspend (Status) -> Unit) {
        if (t0.pairId > 0 && !SyncHooks.allowed(ctx, db, t0)) {          // a Wi-Fi-only pair while on mobile data: wait
            dao.update(t0.copy(notBefore = System.currentTimeMillis() + 5 * 60_000L, error = "Waiting for Wi-Fi"))
            return
        }
        val t = t0.copy(state = RUNNING, error = null)
        dao.update(t)
        supervisorScope {
            val job = launch {
                try {
                    val progress = Progress(t) { done -> dao.setDone(t.id, done); onStatus(Status(t.name, done, t.size, index, count, t.kind == UP)) }
                    onStatus(Status(t.name, t.done, t.size, index, count, t.kind == UP))
                    if (t.kind == UP) upload(t, progress) else download(t, progress)
                    withContext(NonCancellable) {
                        val latest = dao.get(t.id) ?: t
                        dao.update(latest.copy(state = DONE, done = latest.size.coerceAtLeast(latest.done), error = null,
                            finishedAt = System.currentTimeMillis()))
                        if (latest.pairId > 0) runCatching { SyncHooks.completed(db, remote, latest) }
                        else if (latest.pairId == Offline.PAIR) runCatching { Offline.record(db, latest) }
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        val latest = dao.get(t.id) ?: t
                        if (canceled.remove(t.id)) dao.update(latest.copy(state = CANCELED, finishedAt = System.currentTimeMillis()))
                        else if (pausing.remove(t.id)) dao.update(latest.copy(state = PAUSED))
                        else dao.update(latest.copy(state = QUEUED))        // stopped by the system: pick up again later
                    }
                    throw e
                } catch (e: Throwable) {
                    withContext(NonCancellable) { failed(t, e) }
                }
            }
            active[t.id] = job
            try { job.join() } finally { active.remove(t.id) }
        }
    }

    private suspend fun failed(t: TransferEntity, e: Throwable) {
        val latest = dao.get(t.id) ?: t
        val message = Remote.explain(e)
        if (isPermanent(e) || latest.attempts + 1 >= MAX_ATTEMPTS) {
            dao.update(latest.copy(state = FAILED, error = message, finishedAt = System.currentTimeMillis()))
        } else {
            val wait = Resume.backoffMs(latest.attempts)
            dao.update(latest.copy(state = QUEUED, error = message, attempts = latest.attempts + 1, notBefore = System.currentTimeMillis() + wait))
        }
    }

    private fun isPermanent(e: Throwable): Boolean = when (e) {
        is FileNotFoundException, is SecurityException -> true
        is PocketException -> e.message?.contains("refused", true) == true
        is S3Exception -> Remote.statusOf(e)?.let { it in 400..499 && it != 408 && it != 429 } ?: false
        else -> false
    }

    /** Reports bytes done, but writes to the database at most a few times a second. */
    private class Progress(val t: TransferEntity, val sink: suspend (Long) -> Unit) {
        private var last = 0L
        suspend fun report(done: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - last >= 400) { last = now; sink(done) }
        }
    }

    // ------------------------------------------------------------------ upload

    private suspend fun upload(t: TransferEntity, progress: Progress) {
        val plainSize = Local.size(ctx, t.localUri).also { if (it < 0) throw FileNotFoundException("The file can't be read any more.") }
        val mtime = t.mtime.takeIf { it > 0 } ?: Local.modified(ctx, t.localUri)
        var current = dao.get(t.id) ?: t
        // a vault upload sends the encrypted bytes: their size, and a nonce that must never be used for two different files
        val source = if (t.vaultId > 0) {
            val crypt = vaults.cipher(t.vaultId) ?: throw SecurityException("The vault's password is missing on this phone.")
            val changed = current.vaultNonce.isNotEmpty() && current.size != plainSize && current.uploadId != null
            if (changed) {
                current.uploadId?.let { remote.abortMultipart(t.bucket, t.key, it) }
                current = current.copy(uploadId = null, parts = "", done = 0, vaultNonce = "")
            }
            val nonce = if (current.vaultNonce.isEmpty()) crypt.newNonce() else hexToBytes(current.vaultNonce)
            current = current.copy(vaultNonce = bytesToHex(nonce), size = plainSize)
            dao.update(current)
            EncryptedSource(crypt, nonce, plainSize) { off, len -> Local.readRange(ctx, t.localUri, off, len) }
        } else null
        val size = source?.size ?: plainSize
        val readAt: (Long, Int) -> ByteArray = { off, len -> source?.read(off, len) ?: Local.readRange(ctx, t.localUri, off, len) }
        if (size <= Remote.MULTIPART_ABOVE) {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { readAt(0, size.toInt()) }
            remote.putSmall(t.bucket, t.key, bytes, mtime)
            progress.report(size, force = true)
            dao.update((dao.get(t.id) ?: t).copy(size = plainSize))
            return
        }
        var uploadId = current.uploadId
        var held = linkedMapOf<Int, String>()
        if (uploadId != null) {
            val parts = remote.heldParts(t.bucket, t.key, uploadId)
            if (parts == null) uploadId = null else held.putAll(parts)
        }
        if (uploadId == null) {
            held = linkedMapOf()
            uploadId = remote.startMultipart(t.bucket, t.key, mtime)
            dao.update((dao.get(t.id) ?: t).copy(uploadId = uploadId, parts = "", size = plainSize, done = 0))
        } else {
            dao.update((dao.get(t.id) ?: t).copy(size = plainSize))
        }
        try {
            for ((i, part) in Resume.parts(size, Remote.PART_SIZE.toLong()).withIndex()) {
                val n = i + 1
                val (offset, len) = part
                if (held.containsKey(n)) { progress.report(offset + len); continue }
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { readAt(offset, len) }
                held[n] = remote.putPart(t.bucket, t.key, uploadId, n, bytes)
                dao.update((dao.get(t.id) ?: t).copy(parts = Resume.encode(held)))
                progress.report(offset + len, force = true)
            }
            remote.finishMultipart(t.bucket, t.key, uploadId, held)
        } catch (e: NoSuchUpload) {
            dao.update((dao.get(t.id) ?: t).copy(uploadId = null, parts = "", done = 0))
            throw IOException("The server dropped the upload; starting it again.")
        }
        dao.update((dao.get(t.id) ?: t).copy(uploadId = null, parts = ""))
    }

    // ------------------------------------------------------------------ download

    private suspend fun download(t: TransferEntity, progress: Progress) {
        val part = if (t.localUri.isBlank()) File(ctx.filesDir, "downloads/${t.id}.part")
        else File(File(t.localUri).parentFile, ".pocket-${t.id}.part")      // next to the destination, so the final step is a rename
        var start = if (part.isFile) part.length() else 0L
        if (t.size in 1..start) { part.delete(); start = 0 }          // a leftover from an earlier, different file
        if (start == 0L) part.delete()
        val fetched = try {
            remote.downloadTo(t.bucket, t.key, part, start) { got -> progress.report(got) }
        } catch (e: S3Exception) {
            if (Remote.statusOf(e) == 416) { part.delete(); throw IOException("The download restarts.") }
            throw e
        }
        if (part.length() != fetched.size) throw IOException("The download was cut short (${part.length()} of ${fetched.size} bytes).")
        if (t.vaultId > 0) {
            val crypt = vaults.cipher(t.vaultId) ?: throw SecurityException("The vault's password is missing on this phone.")
            val plain = File(part.parentFile, part.name + ".plain")
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    part.inputStream().buffered().use { input -> plain.outputStream().buffered().use { out -> crypt.decrypt(input, out) } }
                } catch (e: cloud.pocket.drive.data.vault.CryptException) {
                    plain.delete(); part.delete()
                    throw SecurityException(e.message ?: "Can't decrypt the file.")
                }
            }
            part.delete()
            plain.renameTo(part)
        }
        if (t.localUri.isBlank()) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { Downloads.save(ctx, part, t.name, t.subdir, Remote.mimeOf(t.name)) }
            part.delete()
        } else {
            val dest = File(t.localUri)
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
                if (fetched.modified > 0) dest.setLastModified(fetched.modified)
            }
        }
        progress.report(fetched.size, force = true)
    }

    private fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        const val MAX_ATTEMPTS = 6
    }
}

/** Notifications for the transfer queue. */
object TransferNotifier {
    const val CHANNEL = "transfers"
    const val ID = 4201
    const val RESULT_ID = 4202
    const val PROBLEMS = "transfer_problems"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of uploads, downloads and sync"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(PROBLEMS, "Transfer problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "An upload or download that failed or is being retried"
        })
    }

    /** Tells the user how a run of the queue went: a brief note when all went well, a lasting one when something failed. */
    fun finished(ctx: Context, batch: Batch) {
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("open", "transfers")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val problem = batch.hasProblems
        val n = NotificationCompat.Builder(ctx, if (problem) PROBLEMS else CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(batch.title())
            .setContentText(batch.text())
            .setStyle(NotificationCompat.BigTextStyle().bigText(batch.text()))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { if (!problem) setTimeoutAfter(10_000) }
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(RESULT_ID, n) }      // fails quietly without the notification permission
    }

    fun build(ctx: Context, s: Status?): android.app.Notification {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open", "transfers")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (s == null) return b.setContentTitle("Pocket Drive").setContentText("Getting ready").setProgress(0, 0, true).build()
        val verb = if (s.upload) "Uploading" else "Downloading"
        b.setContentTitle(if (s.count > 1) "$verb ${s.index} of ${s.count}" else verb).setContentText(s.name)
        if (s.total > 0) b.setProgress(1000, (s.done * 1000 / s.total).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        return b.build()
    }
}

/** Adding to the queue and waking the workers that run it. */
object Transfers {
    suspend fun enqueueUpload(g: AppGraph, bucket: String, key: String, uri: String, name: String, size: Long, mtime: Long,
                              pairId: Long = 0, rel: String = "", vaultId: Long = 0): Boolean {
        val dao = g.db.transfers()
        if (dao.pendingCount(UP, bucket, key) > 0) return false
        dao.insert(TransferEntity(kind = UP, bucket = bucket, key = key, localUri = uri, name = name, size = size, mtime = mtime,
            pairId = pairId, rel = rel, vaultId = vaultId))
        return true
    }

    suspend fun enqueueDownload(g: AppGraph, bucket: String, key: String, size: Long, subdir: String = "", destPath: String = "",
                                pairId: Long = 0, rel: String = "", etag: String = "", vaultId: Long = 0, name: String = ""): Boolean {
        val dao = g.db.transfers()
        if (dao.pendingCount(DOWN, bucket, key) > 0) return false
        dao.insert(TransferEntity(kind = DOWN, bucket = bucket, key = key, localUri = destPath, name = name.ifEmpty { Keys.name(key) }, size = size,
            subdir = subdir, pairId = pairId, rel = rel, etag = etag, vaultId = vaultId))
        return true
    }

    fun uriString(file: File): String = Uri.fromFile(file).toString()
}
