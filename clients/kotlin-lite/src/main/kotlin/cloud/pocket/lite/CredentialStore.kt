package cloud.pocket.lite

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A device's key for one app, as enrollment returns it. */
@Serializable
data class DeviceKey(
    val endpoint: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("access_key") val accessKey: String,
    @kotlinx.serialization.SerialName("secret_key") val secretKey: String,
    val region: String = "us-east-1",
)

/**
 * Where the library keeps the device id and each app's key. Android apps implement this on top of
 * Keystore-encrypted storage (EncryptedFile, DataStore + Tink, ...); on a computer [FileCredentialStore] is the
 * default. Calls are made off the main thread and may block.
 */
interface CredentialStore {
    fun loadKey(app: String): DeviceKey?
    fun saveKey(app: String, key: DeviceKey)
    fun clearKey(app: String)
    fun loadDeviceId(): String?
    fun saveDeviceId(id: String)
}

/** Keys in memory only: they are gone when the process ends (tests, short-lived tools). */
class MemoryCredentialStore : CredentialStore {
    private val keys = ConcurrentHashMap<String, DeviceKey>()
    @Volatile private var deviceId: String? = null
    override fun loadKey(app: String) = keys[app]
    override fun saveKey(app: String, key: DeviceKey) { keys[app] = key }
    override fun clearKey(app: String) { keys.remove(app) }
    override fun loadDeviceId() = deviceId
    override fun saveDeviceId(id: String) { deviceId = id }
}

/**
 * The same files as the other Pocket Cloud libraries: `<home>/device_id` and `<home>/apps/<app>.json`, readable
 * by this user only. [home] defaults to `POCKET_HOME` or `~/.pocket`.
 */
class FileCredentialStore(val home: File = defaultHome()) : CredentialStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun loadKey(app: String): DeviceKey? {
        val f = File(home, "apps/$app.json")
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(DeviceKey.serializer(), f.readText()) }.getOrNull()
    }

    override fun saveKey(app: String, key: DeviceKey) =
        writePrivate(File(home, "apps/$app.json"), json.encodeToString(DeviceKey.serializer(), key))

    override fun clearKey(app: String) { File(home, "apps/$app.json").delete() }

    override fun loadDeviceId(): String? = File(home, "device_id").takeIf { it.isFile }?.readText()?.trim()

    override fun saveDeviceId(id: String) = writePrivate(File(home, "device_id"), id + "\n")

    companion object {
        fun defaultHome(): File = System.getenv("POCKET_HOME")?.let(::File)
            ?: File(System.getProperty("user.home"), ".pocket")
    }
}

/** Write a file only this user can read, replacing any old one in one step. */
internal fun writePrivate(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, ".tmp-" + System.nanoTime())
    tmp.writeText(text)
    tmp.setReadable(false, false); tmp.setReadable(true, true)
    tmp.setWritable(false, false); tmp.setWritable(true, true)
    replaceFile(tmp, f)
}

/** Rename [from] over [to]; Windows will not rename over an existing file, so delete it first there. */
internal fun replaceFile(from: File, to: File) {
    if (from.renameTo(to)) return
    to.delete()
    if (!from.renameTo(to)) throw IOException("could not save $to")
}
