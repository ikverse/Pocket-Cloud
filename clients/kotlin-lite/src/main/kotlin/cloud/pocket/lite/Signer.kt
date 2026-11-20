package cloud.pocket.lite

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Request signing for the /_pocket/ routes: a port of `sign_pocket` in server/pocket/sigv4.py. */
object Signer {
    /** SHA-256 of nothing, for requests without a body. */
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * The headers for one request. [rawPath] and [rawQuery] are exactly what goes on the wire (still
     * percent-encoded, query without `?`); [bodySha256] is the lower-case hex SHA-256 of the body.
     */
    fun sign(method: String, rawPath: String, rawQuery: String?, bodySha256: String, accessKey: String,
             secret: String, date: Long): Map<String, String> {
        val text = listOf(method.uppercase(), rawPath, rawQuery ?: "", date.toString(), bodySha256).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return mapOf("x-pocket-key" to accessKey, "x-pocket-date" to date.toString(),
            "x-pocket-sign" to hex(mac.doFinal(text.toByteArray())))
    }

    fun sha256(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    internal fun hex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) { append(HEX[(x.toInt() shr 4) and 15]); append(HEX[x.toInt() and 15]) }
    }

    private const val HEX = "0123456789abcdef"
}

/** Percent-encoding of section 1: every byte outside `A-Z a-z 0-9 - _ . ~` is `%XX`. */
internal object Encode {
    private fun unreserved(c: Int) = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
        c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code

    /** One segment or one query value (a `/` becomes `%2F`). */
    fun component(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (unreserved(c)) append(c.toChar()) else append('%').append("%02X".format(c))
        }
    }

    /** A key in a path: per segment, `/` kept. Checks the key rules first. */
    fun key(key: String): String {
        checkKey(key)
        return key.split('/').joinToString("/") { component(it) }
    }

    fun checkKey(key: String) {
        val n = key.toByteArray(Charsets.UTF_8).size
        val bad = n == 0 || n > 1024 || key.startsWith("/") || key.endsWith("/") || key.contains('\u0000') ||
            key.split('/').any { it.isEmpty() || it == "." || it == ".." }
        if (bad) throw PocketException("BadKey", 0, "Not a valid key: \"$key\".")
    }

    fun query(params: List<Pair<String, String?>>): String =
        params.filter { it.second != null }.joinToString("&") { "${component(it.first)}=${component(it.second!!)}" }
}
