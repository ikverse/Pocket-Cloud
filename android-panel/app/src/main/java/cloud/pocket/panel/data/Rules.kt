package cloud.pocket.panel.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Per-app rules (docs/app-api.md section 9): GET / PATCH /api/apps/<app>/rules. Each part is shown only when the
 * server reports its feature in /api/status → features, as the web panel does.
 */

/** The features that make the rules worth fetching. */
val RULE_FEATURES = listOf("retention", "publish", "notify", "changes")

const val MAX_RULES = 20
const val KEEP_LAST_MAX = 1000
const val KEEP_DAYS_MAX = 3650

data class RetentionRule(
    val bucket: String,
    val prefix: String,
    val keepLast: Int?,
    val keepDays: Int?,
    val lastRun: Double = 0.0,
    val lastRemoved: Long = 0,
    val lastFreed: Long = 0,
)

data class PublishRule(val enabled: Boolean, val url: String, val latestVersion: String?, val latestPublished: Double)
data class NotifyRule(val enabled: Boolean, val topic: String, val sentToday: Long, val limitDay: Long)
data class ChangeFeed(val keepDays: Int, val count: Long)

/** A part is null when the server doesn't report its feature, or left it out of the answer. */
data class AppRules(
    val retention: List<RetentionRule>?,
    val publish: PublishRule?,
    val notify: NotifyRule?,
    val changes: ChangeFeed?,
) {
    val hasUpdates get() = publish != null || notify != null || changes != null
}

private fun J.intOrNull(k: String): Int? = if (has(k)) i(k) else null

fun parseRules(j: J, features: Collection<String>): AppRules {
    val retention = if ("retention" in features && j.o.optJSONArray("retention") != null)
        j.arr("retention").map { r ->
            RetentionRule(r.s("bucket"), r.s("prefix"), r.intOrNull("keep_last"), r.intOrNull("keep_days"),
                r.d("last_run"), r.l("last_removed"), r.l("last_freed"))
        } else null
    val publish = if ("publish" in features && j.has("publish")) j.obj("publish").let { p ->
        val latest = if (p.has("latest")) p.obj("latest") else null
        PublishRule(p.b("enabled"), p.s("url"), latest?.s("version")?.ifBlank { null }, latest?.d("published") ?: 0.0)
    } else null
    val notify = if ("notify" in features && j.has("notify")) j.obj("notify").let { n ->
        NotifyRule(n.b("enabled"), n.s("topic"), n.l("sent_today"), n.l("limit_day"))
    } else null
    val changes = if ("changes" in features && j.has("changes")) j.obj("changes").let { c -> ChangeFeed(c.i("keep_days"), c.l("count")) } else null
    return AppRules(retention, publish, notify, changes)
}

/** The app's rules, or null when the server has none of the rule features or the request fails. */
suspend fun PocketApi.appRules(app: String): AppRules? = try {
    val features = get("/api/status").strs("features")
    if (features.none { it in RULE_FEATURES }) null else parseRules(get("/api/apps/$app/rules"), features)
} catch (e: ApiException) {
    null
}

/** The spaces a rule can cover: the family app has only its family bucket. */
fun ruleBuckets(isFamily: Boolean): List<String> = if (isFamily) listOf("family") else listOf("shared", "device")

// ---- editing: the form keeps the numbers as typed; they are checked when a rule is done and when the list is saved

data class RuleDraft(
    val bucket: String,
    val prefix: String,
    val keepLast: String,
    val keepDays: String,
    /** The saved rule this one was loaded from (for its last run), null for a new rule. */
    val saved: RetentionRule? = null,
)

fun RetentionRule.toDraft() = RuleDraft(bucket, prefix, keepLast?.toString() ?: "", keepDays?.toString() ?: "", this)

/** A new rule starts like the web panel's: the first space, keep the 7 newest. */
fun newRuleDraft(buckets: List<String>) = RuleDraft(buckets.first(), "", "7", "")

private fun keepNumber(text: String, max: Int): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..max }

/** What's wrong with one rule (without its number), or null. Mirrors the server's checks except duplicates. */
fun checkDraft(d: RuleDraft, buckets: List<String>): String? {
    val prefix = d.prefix.trim()
    return when {
        d.bucket !in buckets -> "the space is " + buckets.joinToString(" or ") + "."
        prefix.isEmpty() -> "give a folder, such as backups/."
        prefix.startsWith("/") -> "the folder doesn't start with /."
        prefix.toByteArray().size > 1024 -> "the folder is at most 1024 bytes."
        d.keepLast.isNotBlank() && keepNumber(d.keepLast, KEEP_LAST_MAX) == null -> "keep last is a whole number from 1 to $KEEP_LAST_MAX, or blank."
        d.keepDays.isNotBlank() && keepNumber(d.keepDays, KEEP_DAYS_MAX) == null -> "newer than is a whole number of days from 1 to $KEEP_DAYS_MAX, or blank."
        d.keepLast.isBlank() && d.keepDays.isBlank() -> "set keep last, newer than, or both."
        else -> null
    }
}

sealed interface RetentionCheck {
    data class Ok(val rules: List<RetentionRule>) : RetentionCheck
    data class Bad(val message: String) : RetentionCheck
}

/** Check the whole list before it is sent; messages name the rule ("Rule 2: ...") like the server's. */
fun checkRetention(drafts: List<RuleDraft>, buckets: List<String>): RetentionCheck {
    if (drafts.size > MAX_RULES) return RetentionCheck.Bad("At most $MAX_RULES rules per app.")
    val seen = mutableSetOf<Pair<String, String>>()
    val rules = drafts.mapIndexed { i, d ->
        checkDraft(d, buckets)?.let { return RetentionCheck.Bad("Rule ${i + 1}: $it") }
        val prefix = d.prefix.trim()
        if (!seen.add(d.bucket to prefix)) return RetentionCheck.Bad("Rule ${i + 1}: there is already a rule for ${d.bucket} $prefix.")
        RetentionRule(d.bucket, prefix, keepNumber(d.keepLast, KEEP_LAST_MAX), keepNumber(d.keepDays, KEEP_DAYS_MAX))
    }
    return RetentionCheck.Ok(rules)
}

/** PATCH body replacing the whole retention list. */
fun retentionBody(rules: List<RetentionRule>): JSONObject = JSONObject().put("retention", JSONArray().apply {
    rules.forEach { r ->
        put(JSONObject().put("bucket", r.bucket).put("prefix", r.prefix)
            .put("keep_last", r.keepLast ?: JSONObject.NULL).put("keep_days", r.keepDays ?: JSONObject.NULL))
    }
})

/** PATCH body for the publish or notify switch. */
fun switchBody(kind: String, on: Boolean): JSONObject = JSONObject().put(kind, JSONObject().put("enabled", on))

// ---- wording, as in the web panel

fun RuleDraft.keepText(): String = listOfNotNull(
    keepLast.trim().takeIf { it.isNotEmpty() }?.let { "keep last $it" },
    keepDays.trim().takeIf { it.isNotEmpty() }?.let { "newer than $it days" },
).joinToString(" or ").replaceFirstChar { it.uppercase() }.ifEmpty { "Nothing kept set" }

fun lastRunText(r: RetentionRule?): String = when {
    r == null -> "New rule"
    r.lastRun <= 0 -> "Not run yet"
    else -> "Last run ${dateTime(r.lastRun)}: ${count(r.lastRemoved)} removed, ${bytes(r.lastFreed)} freed"
}

fun publishText(p: PublishRule): String =
    if (p.enabled) "Devices on your network read releases/ without a key at ${p.url}"
    else "Devices on your network could read releases/ without a key"

fun publishLatestText(p: PublishRule): String? =
    p.latestVersion?.let { "Latest: $it, published ${dateTime(p.latestPublished)}" }

fun notifyText(n: NotifyRule): String = "Topic ${n.topic} · ${count(n.sentToday)} of ${count(n.limitDay)} sent today"

fun changesText(c: ChangeFeed): String = "${count(c.count)} changes kept for ${c.keepDays} days, so devices can catch up"
