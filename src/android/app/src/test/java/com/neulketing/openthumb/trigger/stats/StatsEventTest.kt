package com.neulketing.openthumb.trigger.stats

import com.neulketing.openthumb.trigger.NotificationTriggerRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-thumb-stats-schema] Contract of the stats instrumentation:
 * hour-truncated timestamps, fixed buckets, structure-only fields, and a
 * bounded local file. See docs/specs/stats-schema.md.
 */
class StatsEventTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `timestamps are truncated to the hour`() {
        val nowMs = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.MINUTE, 47)
            set(java.util.Calendar.SECOND, 12)
        }.timeInMillis
        val e = StatsEvent.triggerRun("any", "social", "fired", "<5s", nowMs)
        assertEquals(0L, e.getLong("ts") % 3600)
        assertEquals(StatsEvent.SCHEMA_VERSION, e.getInt("v"))
        assertEquals("trigger_run_stats", e.getString("kind"))
    }

    @Test
    fun `duration buckets are exhaustive at the boundaries`() {
        assertEquals("<5s", StatsEvent.durationBucket(0))
        assertEquals("<5s", StatsEvent.durationBucket(4_999))
        assertEquals("5-30s", StatsEvent.durationBucket(5_000))
        assertEquals("30-120s", StatsEvent.durationBucket(30_000))
        assertEquals(">120s", StatsEvent.durationBucket(120_000))
    }

    @Test
    fun `rule kind describes shape not values`() {
        fun rule(pkg: String?, needle: String?) = NotificationTriggerRule(
            label = "x", appPackage = pkg, matchContains = needle, prompt = "p",
        )
        assertEquals("any", StatsEvent.ruleKindOf(rule(null, null)))
        assertEquals("package", StatsEvent.ruleKindOf(rule("com.kakao.talk", null)))
        assertEquals("package+text", StatsEvent.ruleKindOf(rule("com.kakao.talk", "urgent")))
    }

    @Test
    fun `events contain no content fields`() {
        val e = StatsEvent.triggerRun("package+text", "social", "fired", "5-30s", 0L)
        val keys = e.keys().asSequence().toSet()
        assertEquals(
            setOf("v", "kind", "ts", "rule_kind", "app_category", "outcome", "latency_bucket"),
            keys,
        )
    }

    @Test
    fun `sink appends one json object per line`() {
        val sink = StatsSink(tmp.newFolder("stats"))
        sink.append(JSONObject().put("v", 1))
        sink.append(JSONObject().put("v", 2))
        val lines = java.io.File(tmp.root, "stats/events.jsonl").readLines()
        assertEquals(2, lines.size)
        assertEquals(1, JSONObject(lines[0]).getInt("v"))
        assertEquals(2, JSONObject(lines[1]).getInt("v"))
    }

    @Test
    fun `sink drops the oldest half when the file grows past the cap`() {
        val dir = tmp.newFolder("stats")
        val file = java.io.File(dir, "events.jsonl")
        file.writeText(buildString {
            repeat(4000) { i -> append("""{"seq":$i,"pad":"${"x".repeat(300)}"}""", "\n") }
        })
        assertTrue(file.length() > 1_000_000)
        StatsSink(dir).append(JSONObject().put("seq", 9999))
        val lines = file.readLines()
        assertTrue("rotated file must be under the cap", file.length() <= 1_000_000)
        assertEquals(9999, JSONObject(lines.last()).getInt("seq"))
        assertEquals("oldest half was dropped", 2000, JSONObject(lines.first()).getInt("seq"))
    }
}
