package cloud.pocket.drive.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import cloud.pocket.drive.data.sync.LocalEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/** Where Shizuku stands on this phone, in words for the settings screen. */
enum class ShizukuState(val text: String) {
    MISSING("Shizuku isn't running. Install it, then start it (it needs a one-time wireless debugging step)."),
    NEEDS_PERMISSION("Shizuku is running. Pocket Drive needs its permission."),
    READY("Shizuku is ready."),
}

/**
 * Reading folders Android keeps closed (Android/data and Android/obb of other apps) through Shizuku, which lends this
 * app shell rights. Read only: files are listed and copied out to be backed up; nothing is written back.
 */
object ShizukuAccess {
    private const val REQUEST = 4711
    private var shell: IShell? = null

    /** True for paths Android hides from ordinary apps. */
    fun isProtected(path: String): Boolean {
        val p = path.replace('\\', '/')
        return p.contains("/Android/data") || p.contains("/Android/obb")
    }

    fun state(): ShizukuState = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.MISSING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NEEDS_PERMISSION
        else -> ShizukuState.READY
    }

    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false) && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQUEST)
    }

    private suspend fun service(ctx: Context): IShell? {
        shell?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (state() != ShizukuState.READY) return null
        val ready = CompletableDeferred<IShell?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) { ready.complete(binder?.let { IShell.Stub.asInterface(it) }) }
            override fun onServiceDisconnected(name: ComponentName) { shell = null }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name)).processNameSuffix("shell").debuggable(false).version(1)
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
        return withTimeoutOrNull(10_000) { ready.await() }?.also { shell = it }
    }

    /** Every file below [dir], with size and modification time. Null when Shizuku isn't ready. */
    suspend fun scan(ctx: Context, dir: String): Map<String, LocalEntry>? {
        val sh = service(ctx) ?: return null
        val base = dir.trimEnd('/')
        val out = withContext(Dispatchers.IO) { sh.run(arrayOf("find", base, "-type", "f", "-exec", "stat", "-c", "%s %Y %n", "{}", "+")) }
        val files = linkedMapOf<String, LocalEntry>()
        for (line in out.lines()) {
            val a = line.indexOf(' ')
            val b = line.indexOf(' ', a + 1)
            if (a <= 0 || b <= a) continue
            val size = line.substring(0, a).toLongOrNull() ?: continue
            val mtime = (line.substring(a + 1, b).toLongOrNull() ?: continue) * 1000
            val full = line.substring(b + 1)
            if (!full.startsWith("$base/")) continue
            val rel = full.removePrefix("$base/")
            files[rel] = LocalEntry(rel, size, mtime, full)
        }
        return files
    }

    /** Copy a protected file where this app can read it, and return that copy. */
    suspend fun stage(ctx: Context, source: String): File? {
        val sh = service(ctx) ?: return null
        val dir = File(ctx.externalCacheDir ?: ctx.cacheDir, "stage").also { it.mkdirs() }
        val target = File(dir, source.hashCode().toUInt().toString(16) + "-" + source.substringAfterLast('/'))
        withContext(Dispatchers.IO) { sh.run(arrayOf("cp", source, target.path)) }
        return target.takeIf { it.isFile }
    }

    /** Delete staged copies older than a day. */
    fun cleanStage(ctx: Context) {
        val limit = System.currentTimeMillis() - 24 * 3600_000L
        File(ctx.externalCacheDir ?: ctx.cacheDir, "stage").listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }
}
