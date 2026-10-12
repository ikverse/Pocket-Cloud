package cloud.pocket.drive.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the Pocket Cloud server is and how this device joined it. The enrollment secret is encrypted with a key
 * kept in Android's Keystore, so it is unreadable outside this app.
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drive", Context.MODE_PRIVATE)

    /** What the user typed, e.g. "pocket:9000" or "http://100.82.2.29:9000". */
    var address: String
        get() = prefs.getString("address", "") ?: ""
        set(v) = prefs.edit().putString("address", v.trim()).apply()

    var secret: String?
        get() = prefs.getString("secret", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) = prefs.edit().apply { if (v == null) remove("secret") else putString("secret", encrypt(v)) }.apply()

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: Build.MODEL
        set(v) = prefs.edit().putString("deviceName", v.trim()).apply()

    var enrolled: Boolean
        get() = prefs.getBoolean("enrolled", false)
        set(v) = prefs.edit().putBoolean("enrolled", v).apply()

    /** Features the server announced on its health page (see Remote.probe). */
    var features: Set<String>
        get() = prefs.getStringSet("features", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("features", v).apply()

    /** A second, faster address for the same server on the home network, if the server has one. */
    var lanEndpoint: String
        get() = prefs.getString("lan", "") ?: ""
        set(v) = prefs.edit().putString("lan", v).apply()

    /** The address used for the last connection (the primary one or the home-network one). */
    var endpointInUse: String
        get() = prefs.getString("inUse", "") ?: ""
        set(v) = prefs.edit().putString("inUse", v).apply()

    /** The address the push distributor (ntfy) gave this app, if instant sync is on. */
    var pushEndpoint: String
        get() = prefs.getString("pushEndpoint", "") ?: ""
        set(v) = prefs.edit().putString("pushEndpoint", v).apply()

    var serverVersion: String
        get() = prefs.getString("serverVersion", "") ?: ""
        set(v) = prefs.edit().putString("serverVersion", v).apply()

    /** Features switched on by hand in the app's private settings, for trying one before the server announces it. */
    val forcedFeatures: Set<String> get() = prefs.getString("forcedFeatures", "")!!.split(',').filter { it.isNotBlank() }.toSet()

    fun has(feature: String) = feature in features

    fun endpoint(): String = normalizeAddress(address)

    /** A secret kept encrypted (a vault password), or null. */
    fun secret(key: String): String? = prefs.getString("secret_$key", null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun putSecret(key: String, value: String?) =
        prefs.edit().apply { if (value == null) remove("secret_$key") else putString("secret_$key", encrypt(value)) }.apply()

    fun string(key: String, default: String = ""): String = prefs.getString(key, default) ?: default
    fun putString(key: String, v: String) = prefs.edit().putString(key, v).apply()
    fun bool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun long(key: String, default: Long = 0): Long = prefs.getLong(key, default)
    fun putLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()

    /** Forget the server and this device's key (sign out). */
    fun clear() {
        val keep = prefs.getString("deviceName", null)
        prefs.edit().clear().apply()
        keep?.let { deviceName = it }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12))
    }

    companion object {
        private const val ALIAS = "pocket_drive_secret"

        /** "pocket" -> http://pocket:9000, "10.0.2.2:9765" -> http://10.0.2.2:9765, full URLs are kept. */
        fun normalizeAddress(input: String): String {
            var a = input.trim().trimEnd('/')
            if (a.isEmpty()) return ""
            if (!a.contains("://")) a = "http://$a"
            val scheme = a.substringBefore("://")
            val rest = a.substringAfter("://")
            val hostPort = rest.substringBefore('/')
            val path = rest.substring(hostPort.length)
            return if (hostPort.contains(':')) a else "$scheme://$hostPort:9000$path"
        }
    }
}
