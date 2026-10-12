package cloud.pocket.drive.data

/** A list of every file in the drive kept on the phone, so search is instant and works without a connection. */
class SearchIndex(private val g: AppGraph) {
    private val dao get() = g.db.index()

    /** Read the whole drive's file list again. */
    suspend fun refresh() {
        val vaults = g.vaults.all()
        for (bucket in listOf(SHARED, DEVICE)) {
            val files = g.remote.listAll(bucket, "").filter { f -> !Keys.isHidden(f.key) && vaults.none { it.bucket == bucket && f.key.startsWith(it.prefix) } }
            dao.clear(bucket)
            files.chunked(500).forEach { chunk ->
                dao.addAll(chunk.map { IndexEntry(bucket = bucket, key = it.key, name = it.name, size = it.size, modified = it.modified) })
            }
        }
        g.settings.putLong("indexAt", System.currentTimeMillis())
    }

    /** Refresh when the list is more than [maxAgeMs] old. */
    suspend fun refreshIfOld(maxAgeMs: Long = 6 * 3600_000L) {
        if (System.currentTimeMillis() - g.settings.long("indexAt") > maxAgeMs) refresh()
    }

    suspend fun search(query: String): List<IndexEntry> = if (query.isBlank()) emptyList() else dao.search(query.trim())
}
