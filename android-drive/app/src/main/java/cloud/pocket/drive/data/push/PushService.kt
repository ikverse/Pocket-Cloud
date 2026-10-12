package cloud.pocket.drive.data.push

import android.content.Context
import cloud.pocket.drive.DriveApplication
import cloud.pocket.drive.data.AppGraph
import cloud.pocket.drive.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Instant sync: the server tells the ntfy app on this phone that something changed, ntfy wakes this service, and a
 * sync starts. Only available when the server announces the "push" feature.
 */
class PushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph: AppGraph get() = (applicationContext as DriveApplication).graph

    override fun onMessage(message: PushMessage, instance: String) {
        SyncScheduler.runSoon(applicationContext)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        graph.settings.pushEndpoint = endpoint.url
        scope.launch { Push.tellServer(graph) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        graph.settings.pushEndpoint = ""
    }

    override fun onUnregistered(instance: String) {
        graph.settings.pushEndpoint = ""
    }
}

object Push {
    fun available(g: AppGraph) = g.settings.has("push")

    fun enabled(g: AppGraph) = g.settings.pushEndpoint.isNotBlank()

    /** Use the distributor app that is installed (ntfy), then register. [done] says whether one was found. */
    fun enable(ctx: Context, done: (Boolean) -> Unit) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(ctx) { found ->
            if (found) UnifiedPush.register(ctx)
            done(found)
        }
    }

    fun disable(ctx: Context, g: AppGraph) {
        UnifiedPush.unregister(ctx)
        g.settings.pushEndpoint = ""
    }

    /** Give the server this phone's push address, so it knows where to send "something changed". */
    suspend fun tellServer(g: AppGraph) {
        val endpoint = g.settings.pushEndpoint
        if (endpoint.isBlank() || !available(g)) return
        runCatching { g.server.call("POST", "/_pocket/push", body = JSONObject().put("endpoint", endpoint)) }
    }
}
