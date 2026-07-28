package com.neulketing.openthumb.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-thumb-sync-v1] Settings for the optional BYO-backend sync (see
 * tools/sync-worker/ and PRIVACY.md). Same SharedPreferences pattern as
 * [com.neulketing.openthumb.trigger.NotificationTriggerStore].
 *
 * Privacy contract: sync is OFF by default. Nothing is uploaded until the
 * user enters their own worker URL + token and flips [enabled]. Both are
 * revocable at any time from the Sync settings screen; the token lives only
 * in this prefs file and is sent only to the configured worker.
 *
 * The primary constructor takes the [SharedPreferences] directly so plain
 * JUnit tests can inject an in-memory fake; app code uses the Context
 * constructor.
 */
class SyncSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    /** Base URL of the user's own sync worker, e.g. https://x.you.workers.dev */
    var workerUrl: String
        get() = prefs.getString(KEY_WORKER_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_WORKER_URL, v.trim()).apply()

    /** Bearer token the worker was deployed with (SYNC_TOKEN secret). */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    /** Master switch. False by default — the privacy thesis requires opt-in. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** True once the user has filled in both fields; still gated by [enabled]. */
    val isConfigured: Boolean
        get() = workerUrl.isNotBlank() && token.isNotBlank()

    /** Watermark of the last successful push for [kind]; 0 = never synced. */
    fun lastSyncAt(kind: String): Long = prefs.getLong(keyLastSync(kind), 0L)

    fun setLastSyncAt(kind: String, ms: Long) {
        prefs.edit().putLong(keyLastSync(kind), ms).apply()
    }

    companion object {
        private const val PREFS_NAME = "minis_sync_prefs"
        private const val KEY_WORKER_URL = "worker_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENABLED = "enabled"

        private fun keyLastSync(kind: String) = "last_sync_at_$kind"
    }
}
