package cloud.pocket.drive

import android.app.Application
import cloud.pocket.drive.data.AppGraph
import cloud.pocket.drive.data.Runner
import cloud.pocket.drive.data.TransferNotifier
import cloud.pocket.drive.data.sync.FolderWatcher
import cloud.pocket.drive.data.sync.SyncNotifier
import cloud.pocket.drive.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DriveApplication : Application() {
    lateinit var graph: AppGraph
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        TransferNotifier.createChannel(this)
        SyncNotifier.createChannel(this)
        FolderWatcher(graph).start()
        resume()
    }

    /** Transfers that were running when the app was stopped go back in the queue and carry on. */
    fun resume() {
        scope.launch {
            val dao = graph.db.transfers()
            dao.resetRunning()
            dao.purgeOld(System.currentTimeMillis() - 7 * 24 * 3600_000L)
            if (graph.settings.enrolled && dao.queuedCount() > 0) Runner.kick(this@DriveApplication)
            SyncScheduler.ensure(graph)
            if (graph.settings.enrolled) {
                // quietly keep the search list and offline copies fresh whenever the app starts
                runCatching { graph.remote.connect(); graph.search.refreshIfOld(); graph.offline.refresh() }
            }
        }
    }
}
