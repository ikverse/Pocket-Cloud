package cloud.pocket.drive.data

import java.io.File

/** Pinned files and folders: copies kept in the app's own storage, fresh whenever a connection allows. */
class Offline(private val g: AppGraph) {
    private val dao get() = g.db.offline()

    fun root(): File = File(g.app.filesDir, "offline")

    fun fileFor(bucket: String, key: String): File = File(root(), "$bucket/$key")

    /** Keep a file or folder offline from now on. */
    suspend fun pin(bucket: String, key: String) {
        dao.addPin(Pin(bucket = bucket, key = key))
        refresh()
    }

    /** Stop keeping it offline and free the space. */
    suspend fun unpin(bucket: String, key: String) {
        dao.removePin(bucket, key)
        cleanUp()
    }

    /** True when the file is covered by a pin of itself or of a folder above it. */
    fun covered(pins: List<Pin>, bucket: String, key: String): Boolean =
        pins.any { it.bucket == bucket && (it.key == key || (it.key.endsWith("/") && key.startsWith(it.key))) }

    /**
     * Bring the offline copies in step with the drive: fetch what is missing or changed, drop what is gone or no longer
     * pinned. Downloads go through the transfer queue.
     */
    suspend fun refresh() {
        val pins = dao.pins()
        if (pins.isEmpty()) { cleanUp(); return }
        var queued = false
        val wanted = mutableSetOf<Pair<String, String>>()
        for (pin in pins) {
            val files = if (pin.key.endsWith("/")) g.remote.listAll(pin.bucket, pin.key).filter { !Keys.isHidden(it.key) }
            else listOfNotNull(g.remote.head(pin.bucket, pin.key)?.let { h -> Entry(Keys.name(pin.key), pin.key, false, h.size, h.modified, h.etag) })
            for (f in files) {
                wanted += pin.bucket to f.key
                val have = dao.file(pin.bucket, f.key)
                val current = have != null && File(have.path).isFile && have.size == f.size && (have.etag == (f.etag ?: "") || have.modified == f.modified)
                if (!current) {
                    val dest = fileFor(pin.bucket, f.key)
                    if (Transfers.enqueueDownload(g, pin.bucket, f.key, f.size, destPath = dest.path, pairId = PAIR, rel = f.key, etag = f.etag ?: "")) queued = true
                }
            }
        }
        // copies that are no longer wanted
        for (f in dao.files()) if ((f.bucket to f.key) !in wanted) {
            File(f.path).delete()
            dao.removeFile(f.id)
        }
        if (queued) Runner.kick(g.app)
        cleanUp()
    }

    /** Remove copies whose pin is gone, and empty folders. */
    suspend fun cleanUp() {
        val pins = dao.pins()
        for (f in dao.files()) if (!covered(pins, f.bucket, f.key)) {
            File(f.path).delete()
            dao.removeFile(f.id)
        }
        root().walkBottomUp().filter { it.isDirectory && it != root() && it.list()?.isEmpty() == true }.forEach { it.delete() }
    }

    companion object {
        /** A download for a pin finished: remember the copy. */
        suspend fun record(db: DriveDb, t: TransferEntity) {
            val f = File(t.localUri)
            if (!f.isFile) return
            db.offline().putFile(OfflineFile(bucket = t.bucket, key = t.key, size = t.size, etag = t.etag, modified = f.lastModified(), path = f.path))
        }

        /** Transfers made for offline pins carry this instead of a sync pair's number. */
        const val PAIR = -1L
    }
}

/** Stateless helpers the screens use without holding the manager. */
object OfflineHelper {
    fun covered(pins: List<Pin>, bucket: String, key: String): Boolean =
        pins.any { it.bucket == bucket && (it.key == key || (it.key.endsWith("/") && key.startsWith(it.key))) }
}
