package com.neulketing.openthumb.trigger

import org.json.JSONObject
import java.util.UUID

/**
 * [T-thumb-notification-triggers] User-defined rule: when a status-bar
 * notification matching this rule arrives, run [prompt] through the agent
 * headlessly (same run path as scheduled tasks). This is the event-driven
 * counterpart to [com.neulketing.openthumb.scheduled.ScheduledTask].
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
        put("createdAt", createdAt)
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
