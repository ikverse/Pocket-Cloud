package cloud.pocket.lite

import java.io.File
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Pocket Cloud for Kotlin and Android apps, on the plain `/_pocket/` routes (no AWS SDK).
 *
 * ```
 * val pocket = Pocket.connect(app = "egx")                 // on a computer with ~/.pocket/config
 * pocket.putJson("prices/today.json", prices)
 * ```
 *
 * On Android, pass the machine setup from your build settings and a [CredentialStore] backed by Keystore
 * encryption; see the README.
 */
object Pocket {
    private val DEVICE_ID = Regex("^[A-Za-z0-9-]{8,64}$")

    /**
     * Open [app] on this device. Enrolls when the [store] has no key for it, then asks the server which features
     * it has. Every argument but [app] is optional on a computer that has the machine setup file (`<home>/config`)
     * or `POCKET_ENDPOINT` / `POCKET_ENROLL_SECRET`.
     */
    suspend fun connect(
        app: String,
        endpoint: String? = null,
        enrollSecret: String? = null,
        deviceId: String? = null,
        deviceName: String? = null,
        store: CredentialStore? = null,
        timeoutMs: Long = 30_000,
        home: File? = null,
    ): PocketApp {
        val homeDir = home ?: (store as? FileCredentialStore)?.home ?: FileCredentialStore.defaultHome()
        val creds = store ?: FileCredentialStore(homeDir)
        var ep = endpoint ?: System.getenv("POCKET_ENDPOINT")
        var secret = enrollSecret ?: System.getenv("POCKET_ENROLL_SECRET")
        if (ep.isNullOrBlank() || secret.isNullOrBlank()) {
            val ini = readIni(File(homeDir, "config"))
            ep = ep?.takeIf { it.isNotBlank() } ?: ini["endpoint"]
            secret = secret?.takeIf { it.isNotBlank() } ?: ini["enroll_secret"]
        }
        if (ep.isNullOrBlank() || secret.isNullOrBlank()) {
            throw PocketException("NotConfigured", 0, "This device is not set up for Pocket Cloud. Save the machine " +
                "setup file from the control panel's Settings page as ${File(homeDir, "config")}, or pass endpoint " +
                "and enrollSecret.")
        }
        val id = resolveDeviceId(deviceId, creds)
        val name = deviceName ?: System.getenv("POCKET_DEVICE_NAME")
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Kotlin device"
        val session = Session(app, ep.trimEnd('/'), secret, id, name, creds, timeoutMs)
        session.start()
        val features = try { session.health() } catch (e: UnreachableException) { null }
        return PocketApp(session, features)
    }

    private fun resolveDeviceId(given: String?, store: CredentialStore): String {
        (given ?: System.getenv("POCKET_DEVICE_ID"))?.let {
            if (!DEVICE_ID.matches(it)) throw PocketException("BadRequest", 0, "A device id uses 8-64 letters, digits and dashes.")
            return it
        }
        store.loadDeviceId()?.takeIf { DEVICE_ID.matches(it) }?.let { return it }
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val id = "dev-" + Signer.hex(bytes)
        store.saveDeviceId(id)
        return id
    }

    private fun readIni(f: File): Map<String, String> {
        if (!f.isFile) return emptyMap()
        val out = mutableMapOf<String, String>()
        var section: String? = null
        for (raw in f.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length - 1).trim(); continue }
            val eq = line.indexOfFirst { it == '=' || it == ':' }
            if (section == "pocket" && eq > 0) out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return out
    }
}
