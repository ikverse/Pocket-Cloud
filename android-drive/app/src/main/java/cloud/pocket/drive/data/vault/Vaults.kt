package cloud.pocket.drive.data.vault

import cloud.pocket.drive.data.DriveDb
import cloud.pocket.drive.data.Keys
import cloud.pocket.drive.data.Settings
import cloud.pocket.drive.data.Vault
import java.util.concurrent.ConcurrentHashMap

/** The vaults this phone knows: folders of the drive whose contents are encrypted on the phone before they are uploaded. */
class VaultStore(private val db: DriveDb, private val settings: Settings) {
    private val dao get() = db.vaults()
    private val ciphers = ConcurrentHashMap<Long, Crypt>()

    suspend fun all(): List<Vault> = dao.all()

    /** The vault that holds [prefix] of [bucket] (the vault folder itself or anything below it). */
    suspend fun containing(bucket: String, prefix: String): Vault? =
        dao.all().firstOrNull { it.bucket == bucket && Keys.folder(prefix).startsWith(it.prefix) }

    /** Make [prefix] (ending in "/") a vault with this password, remembering the password on this phone. */
    suspend fun add(bucket: String, prefix: String, name: String, password: String): Vault {
        val existing = dao.all().firstOrNull { it.bucket == bucket && it.prefix == prefix }
        val vault = existing?.copy(name = name) ?: Vault(bucket = bucket, prefix = prefix, name = name).let { it.copy(id = dao.insert(it)) }
        if (existing != null) dao.update(vault)
        settings.putSecret(secretKey(vault.id), password)
        ciphers.remove(vault.id)
        return vault
    }

    suspend fun remove(vault: Vault) {
        dao.delete(vault.id)
        settings.putSecret(secretKey(vault.id), null)
        ciphers.remove(vault.id)
    }

    fun hasPassword(vault: Vault) = settings.secret(secretKey(vault.id)) != null

    /** The cipher for a vault (key derivation takes about a second the first time). Null when no password is stored. */
    fun cipher(vault: Vault): Crypt? = ciphers[vault.id] ?: settings.secret(secretKey(vault.id))?.let { Crypt(it) }?.also { ciphers[vault.id] = it }

    suspend fun cipher(id: Long): Crypt? = dao.get(id)?.let { cipher(it) }

    private fun secretKey(id: Long) = "vaultPassword_$id"
}

/**
 * The encrypted bytes of a file, produced on demand for any range, so an upload can be sent in parts and resumed.
 * [readPlain] gives plain bytes of the source file at an offset.
 */
class EncryptedSource(private val crypt: Crypt, private val nonce: ByteArray, private val plainSize: Long, private val readPlain: (Long, Int) -> ByteArray) {
    val size: Long = Crypt.encryptedSize(plainSize)
    private var cachedIndex = -1L
    private var cachedBlock = ByteArray(0)

    fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var pos = 0
        var cur = offset
        val header = crypt.header(nonce)
        while (pos < length && cur < Crypt.HEADER) {
            out[pos++] = header[cur.toInt()]
            cur++
        }
        val sealedSize = (Crypt.BLOCK + 16).toLong()
        while (pos < length && cur < size) {
            val rel = cur - Crypt.HEADER
            val index = rel / sealedSize
            val inBlock = (rel % sealedSize).toInt()
            val block = sealed(index)
            val n = minOf(block.size - inBlock, length - pos)
            block.copyInto(out, pos, inBlock, inBlock + n)
            pos += n
            cur += n
        }
        return if (pos == length) out else out.copyOf(pos)
    }

    private fun sealed(index: Long): ByteArray {
        if (index != cachedIndex) {
            val plainOffset = index * Crypt.BLOCK
            val len = minOf(Crypt.BLOCK.toLong(), plainSize - plainOffset).toInt()
            cachedBlock = crypt.sealBlock(nonce, index, readPlain(plainOffset, len))
            cachedIndex = index
        }
        return cachedBlock
    }
}

/** The key prefix in the drive that holds the logical folder [logical] of [vault]: the vault's folder plus the enciphered path. */
fun Crypt.realPrefix(vault: Vault, logical: String): String {
    val rel = logical.removePrefix(vault.prefix).trim('/')
    return if (rel.isEmpty()) vault.prefix else vault.prefix + encryptPath(rel) + "/"
}
