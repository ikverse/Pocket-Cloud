package cloud.pocket.drive.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.vault.VaultStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The pieces the app is built from, created once per process. */
class AppGraph(val app: Context) {
    val settings = Settings(app)
    val remote = Remote(app, settings)
    val db = DriveDb.get(app)
    val vaults = VaultStore(db, settings)
    val engine = TransferEngine(app, remote, db, vaults)
    val server = ServerApi(remote, settings)
    val offline by lazy { Offline(this) }
    val search by lazy { SearchIndex(this) }
}

/**
 * Starting the queue. When the user just asked, the queue also runs right here in the app (it is in view), because the
 * system may leave a user-initiated job waiting for a network it never reports as ready. The job (Android 14+) and the
 * work request stay as backups for when the app is closed; the engine runs one drain at a time, so they don't clash.
 */
object Runner {
    private const val JOB_ID = 7301
    private const val WORK = "transfers"

    fun kick(ctx: Context, userInitiated: Boolean = false) {
        if (userInitiated) drainNow(ctx)
        if (userInitiated && Build.VERSION.SDK_INT >= 34) scheduleUserInitiated(ctx)
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun drainNow(ctx: Context) {
        val app = ctx.applicationContext as DriveApplication
        app.scope.launch {
            val g = app.graph
            try {
                g.remote.connect()
            } catch (e: Exception) {
                noteWaiting(g, Remote.explain(e))
                return@launch
            }
            try {
                g.engine.drain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                noteWaiting(g, Remote.explain(e))      // shown on the waiting transfers instead of vanishing
            }
        }
    }

    /** Say why the waiting transfers aren't moving, so the Transfers tab shows a reason instead of a bare "Waiting". */
    suspend fun noteWaiting(g: AppGraph, reason: String) {
        val dao = g.db.transfers()
        val batch = Batch()
        dao.observeAll().first().filter { it.state == QUEUED }.forEach {
            if (it.error != reason) dao.update(it.copy(error = reason))
            if (it.pairId == 0L) batch.add(it.copy(error = reason))
        }
        if (!batch.isEmpty) TransferNotifier.finished(g.app, batch)      // the user should hear about it outside the app too
    }

    private fun scheduleUserInitiated(ctx: Context): Boolean = try {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) true
        else {
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, TransferJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
                .setEstimatedNetworkBytes(-1L, -1L)
                .build()
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        }
    } catch (e: Exception) {
        false      // not allowed right now (the app isn't in view): use ordinary work instead
    }
}

/** Runs the transfer queue as WorkManager work, with a progress notification. */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as DriveApplication).graph
        runCatching { setForeground(getForegroundInfo()) }      // refused while the app is in the background on some phones
        try {
            g.remote.connect()
        } catch (e: Exception) {
            if (g.db.transfers().queuedCount() == 0) return Result.success()
            Runner.noteWaiting(g, Remote.explain(e))
            return Result.retry()
        }
        // the queue may be run by the app itself (see Runner.kick): the notification follows whoever runs it
        val result = coroutineScope {
            val feed = launch { g.engine.current.filterNotNull().collect { s -> runCatching { setForeground(foreground(s)) } } }
            try { g.engine.drain() } finally { feed.cancel() }
        }
        return when (result) {
            is Drained.Idle -> Result.success()
            is Drained.RetryLater -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(null)

    private fun foreground(s: Status?): ForegroundInfo {
        val n = TransferNotifier.build(applicationContext, s)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(TransferNotifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(TransferNotifier.ID, n)
    }
}

/** The same queue run as a user-initiated data transfer job: it may run as long as the transfer needs. */
class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val g = (application as DriveApplication).graph
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(params, TransferNotifier.ID, TransferNotifier.build(this, null), JOB_END_NOTIFICATION_POLICY_DETACH)
        }
        job = scope.launch {
            var again = false
            try {
                g.remote.connect()
                val feed = launch {
                    g.engine.current.filterNotNull().collect { s ->
                        if (Build.VERSION.SDK_INT >= 34) setNotification(params, TransferNotifier.ID, TransferNotifier.build(this@TransferJobService, s), JOB_END_NOTIFICATION_POLICY_DETACH)
                    }
                }
                val r = try { g.engine.drain() } finally { feed.cancel() }
                again = r is Drained.RetryLater
            } catch (e: Exception) {
                again = g.db.transfers().queuedCount() > 0
                if (again) Runner.noteWaiting(g, Remote.explain(e))
            } finally {
                jobFinished(params, again)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}

/** After a restart of the phone: the app process starts (see DriveApplication), which resumes the queue. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) (context.applicationContext as DriveApplication).resume()
    }
}
