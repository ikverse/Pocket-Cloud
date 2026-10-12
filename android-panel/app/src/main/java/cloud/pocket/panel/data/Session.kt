package cloud.pocket.panel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The panel address and the sign-in token. The token is encrypted with a key kept in Android's Keystore,
 * so it is unreadable outside this app; the admin password itself is never stored.
 */
class Session(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    var address: String
        get() = prefs.getString("address", "") ?: ""
        set(v) = prefs.edit().putString("address", v).apply()

    var token: String?
        get() = prefs.getString("token", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) = prefs.edit().apply { if (v == null) remove("token") else putString("token", encrypt(v)) }.apply()

    /** Alerts already shown as notifications (by id), so each is shown once. */
    var notifiedAlerts: Set<String>
        get() = prefs.getStringSet("notified", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("notified", v).apply()

    fun api(): PocketApi? = token?.let { t -> address.takeIf { it.isNotBlank() }?.let { PocketApi(it, t) } }

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
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12))
    }

    private companion object {
        const val ALIAS = "pocket_session_token"
    }
}
