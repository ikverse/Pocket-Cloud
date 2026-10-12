package cloud.pocket.panel

import android.app.Application
import cloud.pocket.panel.data.AlertWorker
import cloud.pocket.panel.data.Session

class PocketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AlertWorker.createChannel(this)
        if (Session(this).api() != null) AlertWorker.schedule(this)
    }
}
