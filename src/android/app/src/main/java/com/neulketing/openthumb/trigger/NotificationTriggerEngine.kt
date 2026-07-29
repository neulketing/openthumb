package com.neulketing.openthumb.trigger

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.neulketing.openthumb.MinisApp
import com.neulketing.openthumb.logging.AppLogger
import com.neulketing.openthumb.scheduled.ScheduledAgentRunner
import com.neulketing.openthumb.scheduled.ScheduledRepeatMode
import com.neulketing.openthumb.scheduled.ScheduledTargetMode
import com.neulketing.openthumb.scheduled.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-thumb-notification-triggers] Turns incoming status-bar notifications
 * into headless agent runs. Reuses the scheduled-task run path end to end:
 * a matched rule is wrapped in a synthetic [ScheduledTask] and handed to
 * [ScheduledAgentRunner] (FGS + session resolution + completion notification
 * for free; its markFired is a no-op for ids not in the scheduled store).
 *
 * Safety gates, in match order:
 *  1. Own package is ignored — the agent posts notifications itself
 *     (completion, FGS); reacting to them would self-trigger forever.
 *  2. Ongoing/group-summary notifications are ignored (FGS bars, media
 *     players, bundled summaries — noise, not events).
 *  3. Global quiet hours ([NotificationTriggerStore.isQuietNow]) — no rule
 *     fires inside the window.
 *  4. Per-rule active window ([NotificationTriggerRule.withinActiveWindow])
 *     and cooldown, claimed synchronously BEFORE dispatch so a burst can't
 *     double-fire a rule.
 *  5. Global in-flight cap [MAX_CONCURRENT_RUNS].
 *
 * Every dispatch is appended to [NotificationTriggerRunStore] once the
 * runner returns, so the rules screen can show what the phone did on its
 * own. [testFire] bypasses gates 3-4 for the editor's "test this rule".
 */
object NotificationTriggerEngine {

    private const val TAG = "NotifTriggerEngine"

    // ponytail: global cap of 2 concurrent trigger runs; lift to a queue if
    // real usage ever needs more parallelism.
    private const val MAX_CONCURRENT_RUNS = 2

    /** Synthetic package used for "test this rule" runs in the run log. */
    const val TEST_PACKAGE = "openthumb.test"

    private val inFlight = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called from the notification listener's binder thread. Must never
     * throw and must return fast — matching + cooldown claim are quick
     * SharedPreferences reads; the agent run itself is launched on [scope].
     */
    fun maybeFire(context: Context, sbn: StatusBarNotification) {
        try {
            evaluate(context, sbn)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "trigger evaluation failed: ${t.message}")
        }
    }

    private fun evaluate(context: Context, sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == context.packageName) return                       // gate 1
        if (sbn.isOngoing) return                                    // gate 2
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val store = NotificationTriggerStore(context)
        val nowMs = System.currentTimeMillis()
        if (store.isQuietNow(nowMs)) {                              // quiet hours
            recordStats(context, pkg, null, "quiet_hours", sbn.postTime, nowMs)
            return
        }
        val candidates = store.all().filter {
            NotificationTriggerRule.matches(it, pkg, title, text)
        }
        if (candidates.isEmpty()) return

        for (rule in candidates) {
            val claimed = claim(store, rule) ?: continue             // gate 3
            if (inFlight.get() >= MAX_CONCURRENT_RUNS) {             // gate 4
                AppLogger.warning(TAG, "rule ${rule.id} matched but $MAX_CONCURRENT_RUNS runs in flight — dropped")
                continue
            }
            dispatch(context, claimed, pkg, title, text, sbn.postTime, sbn)
        }
    }

    /**
     * "Test this rule" from the editor: fire the prompt immediately with a
     * synthetic notification, bypassing cooldown, quiet hours and the active
     * window — the point is to see the run, not to respect the schedule.
     * Recorded in the run log with pkg = [TEST_PACKAGE].
     */
    fun testFire(context: Context, rule: NotificationTriggerRule) {
        if (inFlight.get() >= MAX_CONCURRENT_RUNS) {
            AppLogger.warning(TAG, "test run for rule ${rule.id} skipped — $MAX_CONCURRENT_RUNS runs in flight")
            return
        }
        dispatch(
            context, rule,
            pkg = TEST_PACKAGE,
            title = "Test notification",
            text = "This is a manual test of rule \"${rule.label}\".",
            postedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Stats instrumentation (docs/specs/stats-schema.md): structure only,
     * local file only. The source app's Play-store category is read from
     * PackageManager; anything unknown becomes "other".
     */
    private fun recordStats(
        context: Context,
        pkg: String,
        rule: NotificationTriggerRule?,
        outcome: String,
        postedAtMs: Long,
        nowMs: Long,
    ) {
        val category = runCatching {
            when (context.packageManager.getApplicationInfo(pkg, 0).category) {
                android.content.pm.ApplicationInfo.CATEGORY_GAME -> "game"
                android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "audio"
                android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "video"
                android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "image"
                android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "social"
                android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "news"
                android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "maps"
                android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
                else -> "other"
            }
        }.getOrDefault("other")
        val ruleKind = rule?.let { com.neulketing.openthumb.trigger.stats.StatsEvent.ruleKindOf(it) } ?: "any"
        com.neulketing.openthumb.trigger.stats.StatsSink(
            java.io.File(context.filesDir, "stats"),
        ).append(
            com.neulketing.openthumb.trigger.stats.StatsEvent.triggerRun(
                ruleKind = ruleKind,
                appCategory = category,
                outcome = outcome,
                latencyBucket = com.neulketing.openthumb.trigger.stats.StatsEvent.durationBucket(
                    (nowMs - postedAtMs).coerceAtLeast(0),
                ),
                nowMs = nowMs,
            ),
        )
    }

    /**
     * Cooldown check + lastFiredAt write in one synchronized step, so two
     * notifications arriving back-to-back can't both pass the check.
     * Returns the updated rule when claimed, null when still cooling down.
     */
    @Synchronized
    private fun claim(
        store: NotificationTriggerStore,
        rule: NotificationTriggerRule,
    ): NotificationTriggerRule? {
        val fresh = store.get(rule.id) ?: return null
        val now = System.currentTimeMillis()
        if (!NotificationTriggerRule.withinActiveWindow(fresh, now)) return null
        if (!NotificationTriggerRule.shouldFire(fresh, now)) return null
        val claimed = fresh.copy(lastFiredAt = now)
        store.upsert(claimed)
        return claimed
    }

    /**
     * Read what the agent actually said in [sessionId] and post it back into
     * the conversation [sbn] came from. Reply failures are logged, never
     * thrown: the trigger run already succeeded and losing the reply must not
     * mark it failed.
     */
    private suspend fun replyWithAnswer(context: Context, sessionId: String, sbn: StatusBarNotification) {
        val answer = runCatching { lastAssistantText(context, sessionId) }
            .getOrElse { t ->
                AppLogger.warning(TAG, "reply lookup failed: ${t.message}")
                null
            }
        if (answer.isNullOrBlank()) {
            AppLogger.info(TAG, "no assistant text in $sessionId — nothing to reply")
            return
        }
        NotificationReplier.reply(context, sbn, answer)
    }

    /**
     * Flattened text of the newest assistant message. `partsJson` is an array
     * of typed blocks; only `text` blocks are speakable — tool calls and
     * attachments have no place in a chat reply.
     */
    private suspend fun lastAssistantText(context: Context, sessionId: String): String? {
        val app = context.applicationContext as? MinisApp ?: return null
        val message = app.chatRepository.loadMessages(sessionId)
            .lastOrNull { it.role == "assistant" } ?: return null
        val parts = runCatching { JSONArray(message.partsJson) }.getOrNull()
            ?: return message.partsJson.takeIf { it.isNotBlank() }
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") sb.append(part.optString("value", ""))
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun dispatch(
        context: Context,
        rule: NotificationTriggerRule,
        pkg: String,
        title: String,
        text: String,
        postedAtMs: Long,
        /**
         * Source notification, present only on the live path. Needed to reply
         * into the originating conversation; null for "Test this rule", which
         * has no conversation to answer.
         */
        sbn: StatusBarNotification? = null,
    ) {
        val prompt = NotificationTriggerRule.renderPrompt(rule, pkg, title, text)
        // Synthetic task: id is namespaced so ScheduledTaskManager.markFired
        // (keyed on the scheduled store) no-ops, and time fields are unused
        // because we invoke the runner directly.
        val task = ScheduledTask(
            id = "ntrig-${rule.id}",
            label = rule.label.ifBlank { "Notification trigger" },
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = prompt,
            targetMode = ScheduledTargetMode.NewSession,
        )
        AppLogger.info(TAG, "rule ${rule.id} fired for $pkg (\"${title.take(40)}\")")
        inFlight.incrementAndGet()
        scope.launch {
            var ok = false
            try {
                val sessionId = ScheduledAgentRunner.run(context, task, waitForCompletion = true)
                ok = sessionId != null
                if (ok && rule.replyToNotification && sbn != null) {
                    replyWithAnswer(context, sessionId!!, sbn)
                }
            } finally {
                inFlight.decrementAndGet()
                val doneMs = System.currentTimeMillis()
                recordStats(context, pkg, rule, if (ok) "fired" else "launch_failed", postedAtMs, doneMs)
                NotificationTriggerRunStore(context).append(
                    NotificationTriggerRun(
                        ruleId = rule.id,
                        ruleLabel = rule.label,
                        firedAt = System.currentTimeMillis(),
                        pkg = pkg,
                        title = title,
                        ok = ok,
                    ),
                )
            }
        }
    }
}
