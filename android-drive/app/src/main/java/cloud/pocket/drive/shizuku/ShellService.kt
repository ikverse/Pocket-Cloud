package cloud.pocket.drive.shizuku

import java.util.concurrent.TimeUnit

/** Started by Shizuku in its own process with shell rights; the app asks it to list and copy protected folders. */
class ShellService : IShell.Stub() {
    override fun run(command: Array<String>): String = try {
        val process = ProcessBuilder(*command).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        out
    } catch (e: Exception) {
        ""
    }
}
