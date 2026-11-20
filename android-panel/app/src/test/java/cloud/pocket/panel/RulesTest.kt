package cloud.pocket.panel

import cloud.pocket.panel.data.J
import cloud.pocket.panel.data.RetentionCheck
import cloud.pocket.panel.data.RetentionRule
import cloud.pocket.panel.data.RuleDraft
import cloud.pocket.panel.data.changesText
import cloud.pocket.panel.data.checkRetention
import cloud.pocket.panel.data.keepText
import cloud.pocket.panel.data.lastRunText
import cloud.pocket.panel.data.newRuleDraft
import cloud.pocket.panel.data.notifyText
import cloud.pocket.panel.data.parseRules
import cloud.pocket.panel.data.publishLatestText
import cloud.pocket.panel.data.publishText
import cloud.pocket.panel.data.retentionBody
import cloud.pocket.panel.data.ruleBuckets
import cloud.pocket.panel.data.switchBody
import cloud.pocket.panel.data.toDraft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val sample = J(JSONObject("""
        {"retention": [
           {"bucket": "shared", "prefix": "backups/", "keep_last": 7, "keep_days": null,
            "last_run": 1795003600.0, "last_removed": 3, "last_freed": 2048},
           {"bucket": "device", "prefix": "logs/", "keep_last": null, "keep_days": 14,
            "last_run": null, "last_removed": null, "last_freed": null}],
         "publish": {"enabled": true, "url": "http://phone:8080/pub/demo/", "latest": {"version": "1.2.0", "published": 1795003600.0}},
         "notify": {"enabled": false, "topic": "pocket-demo", "sent_today": 2, "limit_day": 50},
         "changes": {"keep_days": 30, "count": 1234}}
    """.trimIndent()))
    private val all = listOf("files", "retention", "publish", "notify", "changes")

    @Test
    fun parsesEveryPart() {
        val r = parseRules(sample, all)
        assertEquals(listOf(
            RetentionRule("shared", "backups/", 7, null, 1795003600.0, 3, 2048),
            RetentionRule("device", "logs/", null, 14, 0.0, 0, 0)), r.retention)
        assertEquals(true, r.publish!!.enabled)
        assertEquals("1.2.0", r.publish?.latestVersion)
        assertEquals("pocket-demo", r.notify!!.topic)
        assertEquals(50L, r.notify?.limitDay)
        assertEquals(1234L, r.changes!!.count)
        assertTrue(r.hasUpdates)
    }

    @Test
    fun partsWithoutTheirFeatureAreLeftOut() {
        val r = parseRules(sample, listOf("retention"))
        assertEquals(2, r.retention!!.size)
        assertNull(r.publish); assertNull(r.notify); assertNull(r.changes)
        assertEquals(false, r.hasUpdates)
        val none = parseRules(sample, listOf("publish"))
        assertNull(none.retention)
    }

    @Test
    fun noLatestRelease() {
        val j = J(JSONObject("""{"publish": {"enabled": false, "url": "u", "latest": null}}"""))
        val p = parseRules(j, all).publish!!
        assertNull(p.latestVersion)
        assertNull(publishLatestText(p))
        assertEquals("Devices on your network could read releases/ without a key", publishText(p))
    }

    @Test
    fun patchBodiesMatchTheContract() {
        val body = retentionBody(listOf(RetentionRule("shared", "backups/", 7, null), RetentionRule("device", "logs/", null, 14)))
        val list = body.getJSONArray("retention")
        assertEquals(2, list.length())
        val first = list.getJSONObject(0)
        assertEquals("shared", first.getString("bucket")); assertEquals("backups/", first.getString("prefix"))
        assertEquals(7, first.getInt("keep_last")); assertTrue(first.has("keep_days") && first.isNull("keep_days"))
        val second = list.getJSONObject(1)
        assertEquals("device", second.getString("bucket"))
        assertTrue(second.has("keep_last") && second.isNull("keep_last")); assertEquals(14, second.getInt("keep_days"))
        assertEquals(setOf("bucket", "prefix", "keep_last", "keep_days"), first.keys().asSequence().toSet())
        assertEquals("""{"retention":[]}""", retentionBody(emptyList()).toString())
        assertEquals("""{"publish":{"enabled":true}}""", switchBody("publish", true).toString())
        assertEquals("""{"notify":{"enabled":false}}""", switchBody("notify", false).toString())
    }

    @Test
    fun checkedListIsTrimmedAndTyped() {
        val drafts = listOf(RuleDraft("shared", " backups/ ", " 7", ""), RuleDraft("device", "logs/", "", "14"))
        val ok = checkRetention(drafts, ruleBuckets(false)) as RetentionCheck.Ok
        assertEquals(listOf(RetentionRule("shared", "backups/", 7, null), RetentionRule("device", "logs/", null, 14)), ok.rules)
    }

    private fun bad(vararg d: RuleDraft, family: Boolean = false) = (checkRetention(d.toList(), ruleBuckets(family)) as RetentionCheck.Bad).message

    @Test
    fun badInputNamesTheRule() {
        val good = RuleDraft("shared", "a/", "1", "")
        assertEquals("Rule 2: set keep last, newer than, or both.", bad(good, RuleDraft("shared", "b/", "", " ")))
        assertEquals("Rule 1: give a folder, such as backups/.", bad(RuleDraft("shared", "  ", "3", "")))
        assertEquals("Rule 1: the folder doesn't start with /.", bad(RuleDraft("shared", "/x", "3", "")))
        assertTrue(bad(RuleDraft("shared", "a/", "1001", "")).startsWith("Rule 1: keep last is a whole number from 1 to 1000"))
        assertTrue(bad(RuleDraft("shared", "a/", "0", "")).startsWith("Rule 1: keep last"))
        assertTrue(bad(RuleDraft("shared", "a/", "2.5", "")).startsWith("Rule 1: keep last"))
        assertTrue(bad(RuleDraft("shared", "a/", "", "3651")).startsWith("Rule 1: newer than"))
        assertEquals("Rule 2: there is already a rule for shared a/.", bad(good, RuleDraft("shared", "a/ ", "", "5")))
        assertEquals("Rule 1: the space is family.", bad(good, family = true))
        assertEquals("At most 20 rules per app.", bad(*Array(21) { RuleDraft("shared", "p$it/", "1", "") }))
    }

    @Test
    fun familyAppHasOnlyTheFamilyBucket() {
        assertEquals(listOf("family"), ruleBuckets(true))
        assertEquals(listOf("shared", "device"), ruleBuckets(false))
        assertEquals(RuleDraft("family", "", "7", ""), newRuleDraft(ruleBuckets(true)))
    }

    @Test
    fun wording() {
        val saved = RetentionRule("shared", "backups/", 7, 30, 0.0)
        val d = saved.toDraft()
        assertEquals(RuleDraft("shared", "backups/", "7", "30", saved), d)
        assertEquals("Keep last 7 or newer than 30 days", d.keepText())
        assertEquals("Newer than 14 days", RuleDraft("device", "logs/", "", "14").keepText())
        assertEquals("New rule", lastRunText(null))
        assertEquals("Not run yet", lastRunText(saved))
        assertTrue(lastRunText(saved.copy(lastRun = 1795003600.0, lastRemoved = 1200, lastFreed = 2048)).endsWith(": 1,200 removed, 2.00 KB freed"))
        val r = parseRules(sample, all)
        assertEquals("Topic pocket-demo · 2 of 50 sent today", notifyText(r.notify!!))
        assertEquals("1,234 changes kept for 30 days, so devices can catch up", changesText(r.changes!!))
        assertEquals("Devices on your network read releases/ without a key at http://phone:8080/pub/demo/", publishText(r.publish!!))
    }
}
