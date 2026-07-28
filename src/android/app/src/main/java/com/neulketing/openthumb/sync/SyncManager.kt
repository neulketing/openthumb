package com.neulketing.openthumb.sync

import android.content.Context
import com.neulketing.openthumb.logging.AppLogger
import com.neulketing.openthumb.scheduled.ScheduledTask
import com.neulketing.openthumb.scheduled.ScheduledTaskStore
import com.neulketing.openthumb.trigger.NotificationTriggerRule
import com.neulketing.openthumb.trigger.NotificationTriggerRun
import com.neulketing.openthumb.trigger.NotificationTriggerRunStore
import com.neulketing.openthumb.trigger.NotificationTriggerStore

/**
 * [T-thumb-sync-v1] Outcome of one [SyncManager.syncNow] pass.
 * [pushedByKind] counts items the worker accepted per kind; [failedKinds]
 * lists kinds whose upload did not complete (their watermark only advanced
 * past the items that did land, so the next pass resumes where it stopped).
 */
data class SyncReport(
    val pushedByKind: Map<String, Int>,
    val failedKinds: List<String>,
) {
    val totalPushed: Int get() = pushedByKind.values.sum()
    val ok: Boolean get() = failedKinds.isEmpty()
}

/**
 * [T-thumb-sync-v1] Outbound-only sync of the user's own data to the user's
 * own sync worker (tools/sync-worker/). v1 pushes THREE kinds:
 *
 *   trigger_rule    — NotificationTriggerStore rows
 *   trigger_run     — NotificationTriggerRunStore rows
 *   scheduled_task  — ScheduledTaskStore rows
 *
 * Each item is `{kind, id, updatedAt, payload}` with `payload` = the store's
 * own JSON row and `updatedAt` = the store record's timestamp
 * (createdAt / firedAt — see the item-mapping helpers below).
 *
 * OUT OF SCOPE for v1 (documented so nobody assumes it works):
 *  - Downsync / pull-merge: `listRemote` exists on the transport but no
 *    merge into local stores happens yet. Sync is one-way, device → server.
 *  - The `chat` and `memory` kinds the worker supports.
 *  - Background scheduling: sync only runs from the "Sync now" button.
 */
class SyncManager(
    private val settings: SyncSettings,
    private val transport: SyncTransport,
    private val sources: List<SyncSource>,
) {

    /** A local dataset that can enumerate its syncable items. */
    interface SyncSource {
        val kind: String
        /** Items with `updatedAt > sinceMs`, in any order. */
        fun itemsUpdatedSince(sinceMs: Long): List<SyncItem>
    }

    /** What a sync pass WOULD push right now, per kind. No network. */
    fun pendingByKind(): Map<String, List<SyncItem>> =
        sources.associate { it.kind to it.itemsUpdatedSince(settings.lastSyncAt(it.kind)) }

    fun pendingCount(): Int = pendingByKind().values.sumOf { it.size }

    /**
     * Push everything newer than each kind's watermark, in batches of
     * [SyncClient.BATCH_LIMIT], oldest first. After a kind's batches all
     * succeed its watermark advances to the newest uploaded `updatedAt`;
     * on failure the watermark still advances past whatever landed, so a
     * retry never re-uploads accepted items. BLOCKING — call off the main
     * thread.
     */
    fun syncNow(): SyncReport {
        val pushed = LinkedHashMap<String, Int>()
        val failed = mutableListOf<String>()
        for (source in sources) {
            val kind = source.kind
            val since = settings.lastSyncAt(kind)
            val items = source.itemsUpdatedSince(since).sortedBy { it.updatedAt }
            var sent = 0
            var maxUpdated = since
            for (chunk in items.chunked(SyncClient.BATCH_LIMIT)) {
                if (transport.pushBatch(kind, chunk)) {
                    sent += chunk.size
                    maxUpdated = maxOf(maxUpdated, chunk.maxOf { it.updatedAt })
                } else {
                    failed.add(kind)
                    break
                }
            }
            if (maxUpdated > since) settings.setLastSyncAt(kind, maxUpdated)
            pushed[kind] = sent
        }
        val report = SyncReport(pushed, failed)
        if (report.ok) {
            AppLogger.info(TAG, "sync ok: ${report.totalPushed} items pushed")
        } else {
            AppLogger.warning(TAG, "sync partial: ${report.totalPushed} pushed, failed=${failed.joinToString()}")
        }
        return report
    }

    companion object {
        private const val TAG = "SyncManager"

        const val KIND_TRIGGER_RULE = "trigger_rule"
        const val KIND_TRIGGER_RUN = "trigger_run"
        const val KIND_SCHEDULED_TASK = "scheduled_task"

        // ── Item mapping (pure — unit-tested without Android) ────────────

        /** updatedAt = last state change: created, or last fired if later. */
        fun triggerRuleItem(rule: NotificationTriggerRule): SyncItem = SyncItem(
            kind = KIND_TRIGGER_RULE,
            id = rule.id,
            updatedAt = maxOf(rule.createdAt, rule.lastFiredAt ?: 0L),
            payload = rule.toJson(),
        )

        /** Runs have no stored id; derive a stable one from rule + fire time. */
        fun triggerRunItem(run: NotificationTriggerRun): SyncItem = SyncItem(
            kind = KIND_TRIGGER_RUN,
            id = "${run.ruleId}:${run.firedAt}",
            updatedAt = run.firedAt,
            payload = run.toJson(),
        )

        /** updatedAt = last state change: created, or last fired if later. */
        fun scheduledTaskItem(task: ScheduledTask): SyncItem = SyncItem(
            kind = KIND_SCHEDULED_TASK,
            id = task.id,
            updatedAt = maxOf(task.createdAt, task.lastFiredAt ?: 0L),
            payload = task.toJson(),
        )

        // ── Wiring ────────────────────────────────────────────────────────

        /** Fresh manager bound to the CURRENT settings (url/token editable). */
        fun create(context: Context): SyncManager {
            val appContext = context.applicationContext
            val settings = SyncSettings(appContext)
            return SyncManager(
                settings = settings,
                transport = SyncClient(settings.workerUrl, settings.token),
                sources = defaultSources(appContext),
            )
        }

        fun defaultSources(context: Context): List<SyncSource> = listOf(
            object : SyncSource {
                private val store = NotificationTriggerStore(context)
                override val kind = KIND_TRIGGER_RULE
                override fun itemsUpdatedSince(sinceMs: Long) =
                    store.all().map(::triggerRuleItem).filter { it.updatedAt > sinceMs }
            },
            object : SyncSource {
                private val store = NotificationTriggerRunStore(context)
                override val kind = KIND_TRIGGER_RUN
                override fun itemsUpdatedSince(sinceMs: Long) =
                    store.all().map(::triggerRunItem).filter { it.updatedAt > sinceMs }
            },
            object : SyncSource {
                private val store = ScheduledTaskStore(context)
                override val kind = KIND_SCHEDULED_TASK
                override fun itemsUpdatedSince(sinceMs: Long) =
                    store.all().map(::scheduledTaskItem).filter { it.updatedAt > sinceMs }
            },
        )
    }
}
