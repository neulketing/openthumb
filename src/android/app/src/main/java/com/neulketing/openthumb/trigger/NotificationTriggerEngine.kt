package com.neulketing.openthumb.trigger

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.neulketing.openthumb.logging.AppLogger
import com.neulketing.openthumb.scheduled.ScheduledAgentRunner
import com.neulketing.openthumb.scheduled.ScheduledRepeatMode
import com.neulketing.openthumb.scheduled.ScheduledTargetMode
import com.neulketing.openthumb.scheduled.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 *  3. Per-rule cooldown ([NotificationTriggerRule.cooldownSec]), claimed
 *     synchronously BEFORE dispatch so a burst can't double-fire a rule.
 *  4. Global in-flight cap [MAX_CONCURRENT_RUNS].
 */
object NotificationTriggerEngine {

    private const val TAG = "NotifTriggerEngine"

    // ponytail: global cap of 2 concurrent trigger runs; lift to a queue if
    // real usage ever needs more parallelism.
    private const val MAX_CONCURRENT_RUNS = 2

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
            dispatch(context, claimed, pkg, title, text)
        }
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
        if (!NotificationTriggerRule.shouldFire(fresh, now)) return null
        val claimed = fresh.copy(lastFiredAt = now)
        store.upsert(claimed)
        return claimed
    }

    private fun dispatch(
        context: Context,
        rule: NotificationTriggerRule,
        pkg: String,
        title: String,
        text: String,
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
            try {
                ScheduledAgentRunner.run(context, task, waitForCompletion = true)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }
}
