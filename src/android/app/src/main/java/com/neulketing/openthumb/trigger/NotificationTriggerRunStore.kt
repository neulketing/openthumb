package com.neulketing.openthumb.trigger

import android.content.Context
import com.neulketing.openthumb.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-thumb-notification-triggers] One recorded firing of a
 * [NotificationTriggerRule]. [ok] is false when the agent run could not be
 * launched at all (runner returned no session); the run itself happening
 * headlessly means its chat is the artifact of record.
 */
data class NotificationTriggerRun(
    val ruleId: String,
    val ruleLabel: String,
    val firedAt: Long,
    val pkg: String,
    val title: String,
    val ok: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ruleId", ruleId)
        put("ruleLabel", ruleLabel)
        put("firedAt", firedAt)
        put("pkg", pkg)
        put("title", title)
        put("ok", ok)
    }

    companion object {
        fun fromJson(o: JSONObject): NotificationTriggerRun = NotificationTriggerRun(
            ruleId = o.optString("ruleId"),
            ruleLabel = o.optString("ruleLabel"),
            firedAt = o.optLong("firedAt"),
            pkg = o.optString("pkg"),
            title = o.optString("title"),
            ok = o.optBoolean("ok", true),
        )
    }
}

/**
 * Global newest-first firing log for notification triggers — the counterpart
 * to ScheduledTask.runHistory, kept in one list because trigger rules fire
 * far more often than scheduled tasks and the interesting question is
 * "what did the phone do on its own", per rule or overall. Capped at
 * [MAX_RUNS]; same SharedPreferences pattern as [NotificationTriggerStore].
 */
class NotificationTriggerRunStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<NotificationTriggerRun> {
        val raw = prefs.getString(KEY_RUNS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { NotificationTriggerRun.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    @Synchronized
    fun append(run: NotificationTriggerRun) {
        val runs = (listOf(run) + all()).take(MAX_RUNS)
        val arr = JSONArray()
        for (r in runs) arr.put(r.toJson())
        prefs.edit().putString(KEY_RUNS, arr.toString()).apply()
    }

    fun observe(): Flow<List<NotificationTriggerRun>> = callbackFlow {
        trySend(all())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_RUNS || key == null) {
                trySend(all())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "NotifTriggerRunStore"
        private const val PREFS_NAME = "minis_notification_trigger_runs_prefs"
        private const val KEY_RUNS = "runs_json"
        private const val MAX_RUNS = 100
    }
}
