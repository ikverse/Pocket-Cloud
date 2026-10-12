package cloud.pocket.drive.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.AppGraph
import cloud.pocket.drive.data.KIND_FOLDER
import cloud.pocket.drive.data.KIND_MEDIA
import cloud.pocket.drive.data.Runner
import cloud.pocket.drive.data.SHARED
import cloud.pocket.drive.data.SyncPair
import cloud.pocket.drive.data.TransferNotifier
import cloud.pocket.drive.data.UPLOAD_ONLY
import cloud.pocket.drive.data.DOWNLOAD_ONLY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/** Scheduling sync runs: every 15 minutes, when a photo is taken, and on request. */
object SyncScheduler {
    private const val PERIODIC = "sync-periodic"
    private const val MEDIA = "sync-media-trigger"

    /** Make sure the periodic run and the photo trigger exist when they should. */
    suspend fun ensure(g: AppGraph) {
        val ctx = g.app
        val wm = WorkManager.getInstance(ctx)
        val pairs = g.db.sync().enabledPairs()
        if (!g.settings.enrolled || pairs.isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(MEDIA)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        if (pairs.any { it.kind == KIND_MEDIA } && MediaScanner.hasPermission(ctx)) enqueueMediaTrigger(ctx) else wm.cancelUniqueWork(MEDIA)
    }

    /** One-shot work that fires when photos or videos change; the worker sets the next one when it is done. */
    fun enqueueMediaTrigger(ctx: Context) {
        val c = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        MediaScanner.watchedUris.forEach { c.addContentUriTrigger(it, true) }
        c.setTriggerContentUpdateDelay(15, TimeUnit.SECONDS).setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(c.build()).setInputData(workDataOf("trigger" to true)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(MEDIA, ExistingWorkPolicy.REPLACE, req)
    }

    /** "Sync now": all pairs, or one. */
    fun runNow(ctx: Context, pairId: Long = 0) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("pair" to pairId, "manual" to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now-$pairId", ExistingWorkPolicy.REPLACE, req)
    }

    /** A change was noticed (a push message, a changed folder): sync soon, quietly. */
    fun runSoon(ctx: Context, pairId: Long = 0) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("pair" to pairId))
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-soon-$pairId", ExistingWorkPolicy.REPLACE, req)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as DriveApplication).graph
        val trigger = inputData.getBoolean("trigger", false)
        val manual = inputData.getBoolean("manual", false)
        val pairId = inputData.getLong("pair", 0)
        try {
            if (!g.settings.enrolled) return Result.success()
            if (manual) runCatching { setForeground(getForegroundInfo()) }
            try {
                g.remote.connect()
            } catch (e: Exception) {
                return if (manual) Result.failure() else Result.retry()
            }
            val pairs = if (pairId > 0) listOfNotNull(g.db.sync().pair(pairId)) else g.db.sync().enabledPairs()
            val engine = SyncEngine(g)
            for (p in pairs) engine.runPair(p, manual)
            g.db.sync().purgeLog(System.currentTimeMillis() - 30L * 24 * 3600_000)
            return Result.success()
        } finally {
            if (trigger) SyncScheduler.enqueueMediaTrigger(applicationContext)      // wait for the next photo
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = TransferNotifier.build(applicationContext, null)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(TransferNotifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(TransferNotifier.ID, n)
    }
}

/**
 * While the app is alive, notices changes inside synced folders and asks for a sync soon after. (Android doesn't let
 * an idle app watch folders, so the 15-minute run is what covers the rest.)
 */
class FolderWatcher(private val g: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observers = mutableListOf<FileObserver>()
    private var debounce: Job? = null

    fun start() {
        scope.launch {
            g.db.sync().observePairs()
                .map { list -> list.filter { it.enabled && it.kind == KIND_FOLDER && it.mode != DOWNLOAD_ONLY }.map { it.id to it.localPath } }
                .distinctUntilChanged()
                .collect { watch(it) }
        }
    }

    private fun watch(roots: List<Pair<Long, String>>) {
        observers.forEach { it.stopWatching() }
        observers.clear()
        if (!Net.allFilesAccess()) return
        var budget = MAX_DIRS
        for ((pairId, path) in roots) {
            val stack = ArrayDeque<File>()
            stack.add(File(path))
            while (stack.isNotEmpty() && budget > 0) {
                val dir = stack.removeLast()
                if (!dir.isDirectory || FileScanner.ignored(dir.name)) continue
                budget--
                val o = object : FileObserver(dir, CLOSE_WRITE or CREATE or DELETE or MOVED_FROM or MOVED_TO) {
                    override fun onEvent(event: Int, name: String?) {
                        if (name != null && FileScanner.ignored(name)) return
                        changed(pairId)
                    }
                }
                o.startWatching()
                observers += o
                dir.listFiles()?.filter { it.isDirectory }?.let { stack.addAll(it) }
            }
        }
    }

    private fun changed(pairId: Long) {
        debounce?.cancel()
        debounce = scope.launch {
            delay(20_000)
            SyncScheduler.runSoon(g.app, pairId)
        }
    }

    companion object {
        private const val MAX_DIRS = 400
    }
}

/** One tap: back up the camera folder. */
object CameraBackup {
    const val NAME = "Camera backup"

    /** The camera backup pair, created on first use. */
    suspend fun ensure(g: AppGraph): SyncPair {
        val dao = g.db.sync()
        dao.pairs().firstOrNull { it.name == NAME }?.let { return it }
        val pair = SyncPair(name = NAME, kind = KIND_MEDIA, localPath = "DCIM/Camera/", mediaScope = "both", bucket = SHARED,
            remotePrefix = "Camera/${g.settings.deviceName.replace('/', '-')}/", mode = UPLOAD_ONLY, wifiOnly = true, byDate = true)
        val id = dao.insertPair(pair)
        return pair.copy(id = id)
    }
}
