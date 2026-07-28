package com.neulketing.openthumb.trigger.stats

import org.json.JSONObject

/**
 * [T-thumb-stats-schema] Structured stats events per
 * `docs/specs/stats-schema.md`. Structure only — no notification text,
 * prompts, names, or identifiers ever appear in these events. They are
 * written to an on-device JSONL file by [StatsSink] and go nowhere; there
 * is no upload path and adding one requires an opt-in + PRIVACY.md change
 * first.
 */
object StatsEvent {

    const val SCHEMA_VERSION = 1

    private fun base(kind: String, nowMs: Long): JSONObject {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("kind", kind)
            .put("ts", cal.timeInMillis / 1000)
    }

    /** One row per trigger dispatch outcome. See schema doc, `trigger_run_stats`. */
    fun triggerRun(
        ruleKind: String,
        appCategory: String,
        outcome: String,
        latencyBucket: String,
        nowMs: Long,
    ): JSONObject = base("trigger_run_stats", nowMs)
        .put("rule_kind", ruleKind)
        .put("app_category", appCategory)
        .put("outcome", outcome)
        .put("latency_bucket", latencyBucket)

    /** Shape of the rule's matcher — never the package id or match text. */
    fun ruleKindOf(rule: com.neulketing.openthumb.trigger.NotificationTriggerRule): String =
        when {
            rule.appPackage == null -> "any"
            rule.matchContains.isNullOrBlank() -> "package"
            else -> "package+text"
        }

    fun durationBucket(ms: Long): String = when {
        ms < 5_000 -> "<5s"
        ms < 30_000 -> "5-30s"
        ms < 120_000 -> "30-120s"
        else -> ">120s"
    }
}
