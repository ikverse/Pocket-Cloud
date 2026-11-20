package cloud.pocket.drive.ui.screens

import cloud.pocket.drive.data.DONE
import cloud.pocket.drive.data.DOWN
import cloud.pocket.drive.data.SyncLog
import cloud.pocket.drive.data.TransferEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of Activity's log: a sync log line, or a transfer the user started that finished. */
data class TimelineLine(val ts: Long, val who: String, val text: String, val level: String, val pairId: Long)

object Timeline {
    /**
     * The sync log of every pair and the finished hand-started transfers, newest first. Transfers made by sync are
     * left out: the sync log already sums them up ("212 photos uploaded").
     */
    fun merge(log: List<SyncLog>, pairNames: Map<Long, String>, transfers: List<TransferEntity>, limit: Int = 300): List<TimelineLine> {
        val sync = log.map { TimelineLine(it.ts, pairNames[it.pairId] ?: "Sync", it.text, it.level, it.pairId) }
        val mine = transfers.filter { it.state == DONE && it.pairId == 0L }.map {
            TimelineLine(if (it.finishedAt > 0) it.finishedAt else it.createdAt, "You",
                (if (it.kind == DOWN) "Downloaded " else "Uploaded ") + it.name, "info", 0)
        }
        return (sync + mine).sortedByDescending { it.ts }.take(limit)
    }

    /** Recent per-file conflicts (both sides changed; both copies kept) since [since], newest first, not dismissed. */
    fun conflicts(log: List<SyncLog>, since: Long): List<SyncLog> = log.filter { it.level == "conflict" && it.ts > since }.sortedByDescending { it.ts }

    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val dm = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    /** "21:04" today, "Yesterday", otherwise "17 Nov". */
    fun shortWhen(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (ms <= 0) return "—"
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (t.toLocalDate()) {
            today -> hm.format(t)
            today.minusDays(1) -> "Yesterday"
            else -> dm.format(t)
        }
    }

    /** "Today 21:04", "Yesterday", "17 Nov" — for file rows. */
    fun fileWhen(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val s = shortWhen(ms, now, zone)
        return if (s.length == 5 && s[2] == ':') "Today $s" else s
    }
}
