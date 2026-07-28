package com.neulketing.openthumb.trigger

import android.content.Context
import com.neulketing.openthumb.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray

/**
 * [T-thumb-notification-triggers] SharedPreferences-backed JSON array of
 * [NotificationTriggerRule] rows. Same pattern as
 * [com.neulketing.openthumb.scheduled.ScheduledTaskStore] — small dataset,
 * low write frequency, no Room migration cost.
 */
class NotificationTriggerStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<NotificationTriggerRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { NotificationTriggerRule.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    fun get(ruleId: String): NotificationTriggerRule? = all().firstOrNull { it.id == ruleId }

    fun upsert(rule: NotificationTriggerRule) {
        val current = all().filter { it.id != rule.id }
        write(current + rule)
    }

    fun delete(ruleId: String) {
        write(all().filter { it.id != ruleId })
    }

    private fun write(rules: List<NotificationTriggerRule>) {
        val arr = JSONArray()
        for (r in rules) arr.put(r.toJson())
        prefs.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    /** Cold flow emitting the rule list on every prefs change (rules UI). */
    fun observe(): Flow<List<NotificationTriggerRule>> = callbackFlow {
        trySend(all())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_RULES || key == null) {
                trySend(all())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "NotifTriggerStore"
        private const val PREFS_NAME = "minis_notification_triggers_prefs"
        private const val KEY_RULES = "rules_json"
    }
}
