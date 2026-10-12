package cloud.pocket.panel.update

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Finding, fetching and installing a newer build.
 *
 * One per process, held here rather than on the Application class, because the install-status
 * receiver and the Settings screen both need the same one and neither should have to know how the
 * app is wired. Its own scope: nothing here is tied to a screen, and a download must outlive the
 * settings page that started it.
 */
class Updates private constructor(context: Context) {

    private val repository = UpdateRepository(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** How far the app has got with finding, fetching and checking a newer build. */
    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /**
     * One-line news for whichever screen has a snackbar: a newer build found at launch, or an
     * install that could not start. Buffered, because the launch check finishes before any screen
     * is listening.
     */
    val messages = Channel<String>(Channel.BUFFERED)

    /** The version this build calls itself. */
    val currentVersionName: String get() = repository.currentVersionName

    /** Whether the app asks GitHub for a newer build when it opens. On unless switched off. */
    var checkOnOpen: Boolean
        get() = preferences.getBoolean(KEY_CHECK_ON_OPEN, true)
        set(value) {
            preferences.edit().putBoolean(KEY_CHECK_ON_OPEN, value).apply()
            checkOnOpenState = value
        }

    /** The same setting as Compose state, so a switch bound to it redraws when it is flipped. */
    var checkOnOpenState by mutableStateOf(preferences.getBoolean(KEY_CHECK_ON_OPEN, true))
        private set

    /**
     * Asks GitHub whether a newer build exists, and answers either way.
     *
     * The button is a question, so "you are on the newest version" is an answer worth giving. The
     * launch check is not, which is why it is [checkQuietly] and not this.
     */
    fun checkForUpdate() {
        if (updateState is UpdateState.Checking || updateState is UpdateState.Downloading) return
        updateState = UpdateState.Checking
        scope.launch {
            updateState = runCatching { repository.check() }.fold(
                onSuccess = { update ->
                    if (update == null) {
                        UpdateState.UpToDate(repository.currentVersionName)
                    } else {
                        UpdateState.Available(update)
                    }
                },
                onFailure = { error ->
                    UpdateState.Failed(
                        error.message?.takeIf(String::isNotBlank) ?: "The update check failed.",
                    )
                },
            )
        }
    }

    /**
     * The launch check, which speaks only when there is something new.
     *
     * Telling someone who never asked that nothing has changed is a notification about nothing. A
     * failure is silent for the same reason - the phone was offline, which is not news either.
     */
    fun checkQuietly() {
        scope.launch {
            // The disk before the network, and whatever the setting says: an update already fetched
            // was asked for by someone, and the only thing left to do with it is install it. This is
            // what carries a download through the app being restarted by the permission grant.
            val waiting = runCatching { repository.downloaded() }.getOrNull()
            if (waiting != null) {
                updateState = UpdateState.Ready(waiting.first, waiting.second)
                messages.trySend("Version ${waiting.first.versionName} is downloaded and ready to install")
            }
            if (!checkOnOpen) return@launch
            val update = runCatching { repository.check() }.getOrNull() ?: return@launch
            // A download in hand beats an offer of the same version, and loses to a newer one.
            val ready = (updateState as? UpdateState.Ready)?.update?.version
            if (ready != null && ready >= update.version) return@launch
            updateState = UpdateState.Available(update)
            messages.trySend("Version ${update.versionName} is available in Settings")
        }
    }

    /**
     * Fetches the APK and checks it before offering to install it.
     *
     * The signing check is what turns Android's "App not installed" into a sentence that says what
     * to do about it. It is the same check Android makes, made early enough to be explained.
     */
    fun downloadUpdate(update: AvailableUpdate) {
        if (updateState is UpdateState.Downloading) return
        updateState = UpdateState.Downloading(update, 0f)
        scope.launch {
            updateState = runCatching {
                // Nothing to fetch if it is already here.
                val waiting = runCatching { repository.downloaded() }.getOrNull()
                if (waiting != null && waiting.first.version >= update.version) {
                    return@runCatching UpdateState.Ready(waiting.first, waiting.second)
                }
                // The progress callback arrives on the thread doing the reading. Compose state
                // takes a write from any thread.
                val file = repository.download(update) { progress ->
                    updateState = UpdateState.Downloading(update, progress)
                }
                when (repository.inspect(file)) {
                    DownloadedApk.MATCHES -> UpdateState.Ready(update, file)
                    DownloadedApk.WRONG_KEY -> {
                        file.delete()
                        UpdateState.Failed(
                            "Version ${update.versionName} is signed with a different key, so " +
                                "Android will not install it over this build. Uninstall this one " +
                                "and install that release by hand.",
                        )
                    }
                    DownloadedApk.DAMAGED -> {
                        file.delete()
                        UpdateState.Failed(
                            "The download of version ${update.versionName} arrived damaged. " +
                                "Press Check again, then Download, to fetch it again.",
                        )
                    }
                }
            }.getOrElse { error ->
                UpdateState.Failed(
                    error.message?.takeIf(String::isNotBlank) ?: "The download failed.",
                )
            }
        }
    }

    /** Puts the card back to the button, after an answer has been read. */
    fun dismissUpdate() {
        updateState = UpdateState.Idle
    }

    /**
     * Hands the downloaded APK to Android to install.
     *
     * The confirmation is Android's own and arrives a moment later, through [UpdateInstallReceiver].
     * A failure to even start says so here, because a button that appears to do nothing is the
     * worst outcome this path has.
     */
    fun installUpdate(file: File) {
        scope.launch {
            runCatching { repository.install(file) }.onFailure { error ->
                reportUpdateProblem(
                    error.message?.takeIf(String::isNotBlank)
                        ?: "The install could not be started.",
                )
            }
        }
    }

    /**
     * Says why a button could not do what it says, without throwing away what the card holds.
     *
     * A downloaded update is still a downloaded update afterwards, so this speaks rather than
     * resetting anything.
     */
    fun reportUpdateProblem(reason: String) {
        messages.trySend(reason)
    }

    /** True once the user has allowed this app to install apps; Android is the only one who can ask. */
    fun canInstallUpdates(): Boolean = repository.canInstall()

    fun installPermissionIntent(): Intent = repository.permissionIntent()

    fun releasesPageIntent(): Intent = repository.releasesPageIntent()

    companion object {
        private const val KEY_CHECK_ON_OPEN = "check_on_open"

        @Volatile
        private var instance: Updates? = null

        /** The process's one updater, made on first use. */
        fun of(context: Context): Updates =
            instance ?: synchronized(this) {
                instance ?: Updates(context).also { instance = it }
            }
    }
}
