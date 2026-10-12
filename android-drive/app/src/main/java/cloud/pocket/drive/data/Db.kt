package cloud.pocket.drive.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val QUEUED = "queued"
const val RUNNING = "running"
const val PAUSED = "paused"
const val DONE = "done"
const val FAILED = "failed"
const val CANCELED = "canceled"
const val UP = "up"
const val DOWN = "down"

/** One upload or download in the transfer queue. */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,                 // UP or DOWN
    val bucket: String,
    val key: String,
    /** Upload: where the data comes from (content:// or file path). Download: file to write, or "" for the Downloads folder. */
    val localUri: String,
    val name: String,
    val size: Long,
    val done: Long = 0,
    val state: String = QUEUED,
    val error: String? = null,
    val uploadId: String? = null,     // multipart upload in progress
    val parts: String = "",           // finished parts: "1:etag,2:etag"
    val mtime: Long = 0,              // upload: modification time of the source (ms)
    val subdir: String = "",          // download to Downloads: folder under "Pocket Drive"
    val attempts: Int = 0,
    val notBefore: Long = 0,
    val pairId: Long = 0,             // sync pair that created it (0: started by hand)
    val rel: String = "",             // path inside the sync pair
    val vaultId: Long = 0,            // encrypt (upload) or decrypt (download) with this vault's password
    val vaultNonce: String = "",      // upload: the file's nonce, kept so a resumed upload stays the same file
    val etag: String = "",            // download for sync: the server's checksum of what is being fetched
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
)

@Dao
interface TransferDao {
    @Insert suspend fun insert(t: TransferEntity): Long
    @Update suspend fun update(t: TransferEntity)
    @Query("SELECT * FROM transfers WHERE id = :id") suspend fun get(id: Long): TransferEntity?
    @Query("SELECT * FROM transfers WHERE state = 'queued' AND notBefore <= :now ORDER BY id LIMIT 1")
    suspend fun nextReady(now: Long): TransferEntity?
    @Query("SELECT COUNT(*) FROM transfers WHERE state = 'queued'") suspend fun queuedCount(): Int
    @Query("SELECT MIN(notBefore) FROM transfers WHERE state = 'queued'") suspend fun earliestQueued(): Long?
    @Query("SELECT COUNT(*) FROM transfers WHERE state IN ('queued','running')") fun activeCount(): Flow<Int>
    /** Transfers started by hand that gave up (sync has its own reports). */
    @Query("SELECT COUNT(*) FROM transfers WHERE state = 'failed' AND pairId = 0") fun failedCount(): Flow<Int>
    /** Why the waiting transfers aren't moving, when they said. */
    @Query("SELECT error FROM transfers WHERE state = 'queued' AND error IS NOT NULL ORDER BY id LIMIT 1") fun waitingReason(): Flow<String?>
    @Query("SELECT * FROM transfers ORDER BY CASE state WHEN 'running' THEN 0 WHEN 'queued' THEN 1 WHEN 'paused' THEN 2 ELSE 3 END, id DESC LIMIT 600")
    fun observeAll(): Flow<List<TransferEntity>>
    @Query("UPDATE transfers SET done = :done WHERE id = :id") suspend fun setDone(id: Long, done: Long)
    @Query("UPDATE transfers SET state = 'queued' WHERE state = 'running'") suspend fun resetRunning()
    @Query("DELETE FROM transfers WHERE state IN ('done','failed','canceled')") suspend fun clearFinished()
    @Query("DELETE FROM transfers WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE transfers SET state = 'queued', error = NULL, attempts = 0, notBefore = 0 WHERE state IN ('failed','canceled') AND id = :id")
    suspend fun retry(id: Long)
    @Query("UPDATE transfers SET state = 'queued', error = NULL, notBefore = 0 WHERE state = 'paused'") suspend fun resumeAll()
    @Query("UPDATE transfers SET state = 'paused' WHERE state = 'queued'") suspend fun pauseQueued()
    @Query("SELECT COUNT(*) FROM transfers WHERE state IN ('queued','running','paused') AND kind = :kind AND bucket = :bucket AND `key` = :key")
    suspend fun pendingCount(kind: String, bucket: String, key: String): Int
    @Query("SELECT COUNT(*) FROM transfers WHERE pairId = :pairId AND state IN ('queued','running','paused')")
    suspend fun pendingForPair(pairId: Long): Int
    @Query("DELETE FROM transfers WHERE state = 'done' AND finishedAt < :before") suspend fun purgeOld(before: Long)
}

/** A folder (or a photo/video folder) kept in step with a folder of the drive. */
@Entity(tableName = "sync_pairs")
data class SyncPair(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,                 // KIND_FOLDER or KIND_MEDIA
    val localPath: String,            // folder: absolute path. media: the media folder, e.g. "DCIM/Camera/"
    val mediaScope: String = "both",  // media: "images", "videos" or "both"
    val bucket: String = SHARED,
    val remotePrefix: String = "",    // "" or ends with "/"
    val mode: String = TWO_WAY,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val newestWins: Boolean = false,
    val vault: Boolean = false,
    val byDate: Boolean = false,      // media: file into year/month folders by the date taken
    val enabled: Boolean = true,
    val lastRun: Long = 0,
    val lastResult: String = "",
    val status: String = "",          // "" | STATUS_CONFIRM | STATUS_ERROR
    val pendingDeletes: Int = 0,      // a run paused because it would delete this many files
    val pendingFrom: String = "",     // "local" (deleted here) or "remote" (deleted on the server)
    val decision: String = "",        // "" | "delete" | "restore": the user's answer to a paused run
    val createdAt: Long = System.currentTimeMillis(),
)

const val KIND_FOLDER = "folder"
const val KIND_MEDIA = "media"
const val TWO_WAY = "two_way"
const val UPLOAD_ONLY = "upload"
const val DOWNLOAD_ONLY = "download"
const val STATUS_CONFIRM = "confirm"
const val STATUS_ERROR = "error"

/** What a file looked like on both sides the last time they matched. */
@Entity(tableName = "synced_files", indices = [androidx.room.Index(value = ["pairId", "path"], unique = true)])
data class SyncedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: Long,
    val path: String,                 // relative to the pair, "/" separated
    val localSize: Long,
    val localMtime: Long,
    val remoteSize: Long,
    val remoteMtime: Long,
    val etag: String = "",
    val syncedAt: Long = System.currentTimeMillis(),
)

/** One line of the sync history. */
@Entity(tableName = "sync_log")
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: Long,
    val ts: Long = System.currentTimeMillis(),
    val level: String,                // "info", "warn", "error" or "conflict"
    val text: String,
)

/** Checksums of local files, so a file is read once and not on every run. */
@Entity(tableName = "hash_cache")
data class HashEntry(@PrimaryKey val key: String, val size: Long, val mtime: Long, val md5: String)

/** A folder of the drive whose contents are encrypted (rclone's crypt format). The password is kept encrypted in the settings. */
@Entity(tableName = "vaults", indices = [androidx.room.Index(value = ["bucket", "prefix"], unique = true)])
data class Vault(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bucket: String,
    val prefix: String,               // ends in "/"
    val name: String,
)

@Dao
interface VaultDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insert(v: Vault): Long
    @Update suspend fun update(v: Vault)
    @Query("SELECT * FROM vaults ORDER BY name") suspend fun all(): List<Vault>
    @Query("SELECT * FROM vaults ORDER BY name") fun observe(): Flow<List<Vault>>
    @Query("SELECT * FROM vaults WHERE id = :id") suspend fun get(id: Long): Vault?
    @Query("DELETE FROM vaults WHERE id = :id") suspend fun delete(id: Long)
}

/** A file or folder the user wants available without a connection. [key] ends in "/" for folders. */
@Entity(tableName = "pins", indices = [androidx.room.Index(value = ["bucket", "key"], unique = true)])
data class Pin(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bucket: String,
    val key: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A copy of a drive file kept in the app's own storage for offline use. */
@Entity(tableName = "offline_files", indices = [androidx.room.Index(value = ["bucket", "key"], unique = true)])
data class OfflineFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bucket: String,
    val key: String,
    val size: Long,
    val etag: String,
    val modified: Long,
    val path: String,
    val checkedAt: Long = System.currentTimeMillis(),
)

/** One line of the drive-wide file index used for search. */
@Entity(tableName = "index_entries", indices = [androidx.room.Index(value = ["bucket", "key"], unique = true), androidx.room.Index(value = ["name"])])
data class IndexEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bucket: String,
    val key: String,
    val name: String,
    val size: Long,
    val modified: Long,
)

@Dao
interface OfflineDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun addPin(p: Pin): Long
    @Query("DELETE FROM pins WHERE bucket = :bucket AND `key` = :key") suspend fun removePin(bucket: String, key: String)
    @Query("SELECT * FROM pins") suspend fun pins(): List<Pin>
    @Query("SELECT * FROM pins") fun observePins(): Flow<List<Pin>>
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putFile(f: OfflineFile)
    @Query("SELECT * FROM offline_files WHERE bucket = :bucket AND `key` = :key") suspend fun file(bucket: String, key: String): OfflineFile?
    @Query("SELECT * FROM offline_files ORDER BY `key`") fun observeFiles(): Flow<List<OfflineFile>>
    @Query("SELECT * FROM offline_files") suspend fun files(): List<OfflineFile>
    @Query("DELETE FROM offline_files WHERE id = :id") suspend fun removeFile(id: Long)
}

@Dao
interface IndexDao {
    @Query("DELETE FROM index_entries WHERE bucket = :bucket") suspend fun clear(bucket: String)
    @Insert suspend fun addAll(list: List<IndexEntry>)
    @Query("SELECT * FROM index_entries WHERE instr(lower(name), lower(:q)) > 0 ORDER BY modified DESC LIMIT 200")
    suspend fun search(q: String): List<IndexEntry>
    @Query("SELECT COUNT(*) FROM index_entries") suspend fun count(): Int
}

@Dao
interface SyncDao {
    @Insert suspend fun insertPair(p: SyncPair): Long
    @Update suspend fun updatePair(p: SyncPair)
    @Query("SELECT * FROM sync_pairs ORDER BY id") fun observePairs(): Flow<List<SyncPair>>
    @Query("SELECT * FROM sync_pairs ORDER BY id") suspend fun pairs(): List<SyncPair>
    @Query("SELECT * FROM sync_pairs WHERE enabled = 1 ORDER BY id") suspend fun enabledPairs(): List<SyncPair>
    @Query("SELECT * FROM sync_pairs WHERE id = :id") suspend fun pair(id: Long): SyncPair?
    @Query("DELETE FROM sync_pairs WHERE id = :id") suspend fun deletePair(id: Long)

    @Query("SELECT * FROM synced_files WHERE pairId = :pairId") suspend fun synced(pairId: Long): List<SyncedFile>
    @Query("SELECT * FROM synced_files WHERE pairId = :pairId AND path = :path") suspend fun syncedFile(pairId: Long, path: String): SyncedFile?
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putSynced(f: SyncedFile)
    @Query("DELETE FROM synced_files WHERE pairId = :pairId AND path = :path") suspend fun forget(pairId: Long, path: String)
    @Query("DELETE FROM synced_files WHERE pairId = :pairId") suspend fun forgetAll(pairId: Long)
    @Query("SELECT COUNT(*) FROM synced_files WHERE pairId = :pairId") suspend fun syncedCount(pairId: Long): Int

    @Insert suspend fun log(l: SyncLog)
    @Query("SELECT * FROM sync_log WHERE pairId = :pairId ORDER BY id DESC LIMIT 300") fun observeLog(pairId: Long): Flow<List<SyncLog>>
    @Query("DELETE FROM sync_log WHERE ts < :before") suspend fun purgeLog(before: Long)
    @Query("DELETE FROM sync_log WHERE pairId = :pairId") suspend fun clearLog(pairId: Long)

    @Query("SELECT * FROM hash_cache WHERE `key` = :key") fun hashNow(key: String): HashEntry?
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) fun putHashNow(h: HashEntry)
}

@Database(entities = [TransferEntity::class, SyncPair::class, SyncedFile::class, SyncLog::class, HashEntry::class, Pin::class, OfflineFile::class, IndexEntry::class, Vault::class],
    version = 4, exportSchema = true)
abstract class DriveDb : RoomDatabase() {
    abstract fun transfers(): TransferDao
    abstract fun sync(): SyncDao
    abstract fun offline(): OfflineDao
    abstract fun index(): IndexDao
    abstract fun vaults(): VaultDao

    companion object {
        @Volatile private var instance: DriveDb? = null

        fun get(context: Context): DriveDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DriveDb::class.java, "drive.db")
                .fallbackToDestructiveMigration(true)
                .build().also { instance = it }
        }
    }
}
