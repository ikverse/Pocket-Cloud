package cloud.pocket.panel.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.pocket.panel.MainActivity
import cloud.pocket.panel.R
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (Android's shortest repeat), looks for new Pocket Cloud alerts and shows each one once as a
 * notification, so alerts reach this phone and not only the Note 9 running the server.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = Session(applicationContext)
        val api = session.api() ?: return Result.success()
        val active = try {
            api.get("/api/alerts").arr("active")
        } catch (e: ApiException) {
            return if (e.status == 401) Result.success() else Result.retry()
        }
        val seen = session.notifiedAlerts
        val fresh = active.filter { it.s("id") !in seen && it.s("level") != "info" }
        fresh.forEach { notify(applicationContext, it) }
        session.notifiedAlerts = active.map { it.s("id") }.toSet()      // forget cleared ones
        return Result.success()
    }

    companion object {
        const val CHANNEL = "alerts"
        private const val WORK = "pocket-alerts"

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pocket CloudServer alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Problems on your Pocket CloudServer, like a full pool or a failed job"
                })
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

        private fun notify(context: Context, alert: J) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .putExtra("open", "alerts").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (alert.s("level") == "bad") "Pocket CloudServer needs attention" else "Pocket CloudServer warning")
                .setContentText(alert.s("text"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.s("text")))
                .setPriority(if (alert.s("level") == "bad") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(alert.i("id"), n)
        }
    }
}
