package com.fug.openthumb.trigger

import org.json.JSONObject
import java.util.UUID

/**
 * [T-thumb-notification-triggers] User-defined rule: when a status-bar
 * notification matching this rule arrives, run [prompt] through the agent
 * headlessly (same run path as scheduled tasks). This is the event-driven
 * counterpart to [com.fug.openthumb.scheduled.ScheduledTask].
 *
 * Persistence mirrors ScheduledTask: JSON rows in a SharedPreferences array
 * ([NotificationTriggerStore]).
 */
data class NotificationTriggerRule(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** Package the notification must come from; null = any app. */
    val appPackage: String? = null,
    /**
     * Case-insensitive substring the notification title or text must contain;
     * null/blank = match every notification from [appPackage].
     */
    val matchContains: String? = null,
    /** Agent prompt. Placeholders: {app} {title} {text}. */
    val prompt: String,
    /** Minimum seconds between firings of this rule. */
    val cooldownSec: Int = DEFAULT_COOLDOWN_SEC,
    val enabled: Boolean = true,
    /**
     * Optional per-rule active window, minutes-of-day local (0..1439).
     * The rule only fires when the wall clock is inside [activeStartMin,
     * activeEndMin); a start later than the end wraps past midnight.
     * null on either side = unbounded.
     */
    val activeStartMin: Int? = null,
    val activeEndMin: Int? = null,
    /**
     * Post the agent's answer back into the conversation the notification came
     * from, via the notification's own reply action ([NotificationReplier]).
     * Turns a messenger into a two-way channel to the agent instead of a
     * one-way trigger. Off by default: a rule that fires on, say, a bank alert
     * has nowhere sensible to reply to.
     */
    val replyToNotification: Boolean = false,
    /**
     * Hold this rule's replies for a decision even when the global approval
     * mode would send them. Tightening only — it can never make a reply skip a
     * gate the global setting applies, because the person who set it here knew
     * something about this rule that the global setting does not.
     */
    val requireApproval: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastFiredAt: Long? = null,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        if (appPackage != null) put("appPackage", appPackage)
        if (matchContains != null) put("matchContains", matchContains)
        put("prompt", prompt)
        put("cooldownSec", cooldownSec)
        put("enabled", enabled)
        if (activeStartMin != null) put("activeStartMin", activeStartMin)
        if (activeEndMin != null) put("activeEndMin", activeEndMin)
        put("createdAt", createdAt)
        if (replyToNotification) put("replyToNotification", true)
        if (requireApproval) put("requireApproval", true)
        if (lastFiredAt != null) put("lastFiredAt", lastFiredAt)
    }

    companion object {
        const val DEFAULT_COOLDOWN_SEC = 300

        fun fromJson(o: JSONObject): NotificationTriggerRule = NotificationTriggerRule(
            id = o.getString("id"),
            label = o.optString("label"),
            appPackage = if (o.has("appPackage")) o.optString("appPackage", null) else null,
            matchContains = if (o.has("matchContains")) o.optString("matchContains", null) else null,
            prompt = o.optString("prompt"),
            cooldownSec = o.optInt("cooldownSec", DEFAULT_COOLDOWN_SEC),
            enabled = o.optBoolean("enabled", true),
            activeStartMin = if (o.has("activeStartMin")) o.optInt("activeStartMin") else null,
            activeEndMin = if (o.has("activeEndMin")) o.optInt("activeEndMin") else null,
            replyToNotification = o.optBoolean("replyToNotification", false),
            requireApproval = o.optBoolean("requireApproval", false),
            createdAt = o.optLong("createdAt"),
            lastFiredAt = if (o.has("lastFiredAt")) o.optLong("lastFiredAt") else null,
        )

        /**
         * Pure match predicate — kept free of Android types so it runs under
         * plain JUnit. [pkg]/[title]/[text] come from the StatusBarNotification.
         */
        fun matches(rule: NotificationTriggerRule, pkg: String, title: String, text: String): Boolean {
            if (!rule.enabled) return false
            if (rule.appPackage != null && rule.appPackage != pkg) return false
            val needle = rule.matchContains?.trim().orEmpty()
            if (needle.isEmpty()) return true
            return (title + "\n" + text).contains(needle, ignoreCase = true)
        }

        /** Cooldown gate: true when the rule may fire at [nowMs]. */
        fun shouldFire(rule: NotificationTriggerRule, nowMs: Long): Boolean {
            val last = rule.lastFiredAt ?: return true
            return nowMs - last >= rule.cooldownSec * 1000L
        }

        /** Active-window gate: true when [rule] may fire at [nowMs]. */
        fun withinActiveWindow(rule: NotificationTriggerRule, nowMs: Long): Boolean =
            isWithinWindow(rule.activeStartMin, rule.activeEndMin, nowMs)

        /**
         * Shared minutes-of-day window test (per-rule active window and the
         * global quiet hours). [startMin]/[endMin] are local minutes-of-day;
         * either may be null (unbounded). A start later than the end wraps
         * past midnight. Keeps Calendar use in one place so the semantics are
         * identical everywhere.
         */
        fun isWithinWindow(startMin: Int?, endMin: Int?, nowMs: Long): Boolean {
            if (startMin == null && endMin == null) return true
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val start = startMin ?: 0
            val end = endMin ?: 24 * 60
            return if (start <= end) now in start until end else now >= start || now < end
        }

        /** Substitute {app} {title} {text} and append the notification block. */
        fun renderPrompt(rule: NotificationTriggerRule, pkg: String, title: String, text: String): String {
            val body = rule.prompt
                .replace("{app}", pkg)
                .replace("{title}", title)
                .replace("{text}", text)
            // Always append the raw notification so the prompt works even
            // without placeholders.
            return body + "\n\n[Triggering notification]\napp: " + pkg +
                "\ntitle: " + title + "\ntext: " + text
        }
    }
}
