package com.fug.openthumb.sync

import com.fug.openthumb.logging.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [T-thumb-sync-v1] One syncable record, matching the worker's batch item
 * shape (tools/sync-worker/README.md): `payload` is the owning store's own
 * JSON row, sent verbatim.
 */
data class SyncItem(
    val kind: String,
    val id: String,
    val updatedAt: Long,
    val payload: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("id", id)
        put("updatedAt", updatedAt)
        put("payload", payload)
    }
}

/** One entry of `GET /sync/list` — `{id, updatedAt}` for delta sync. */
data class SyncRemoteEntry(val id: String, val updatedAt: Long)

/**
 * Transport seam so [SyncManager] can run against an in-memory fake in
 * plain JUnit tests (no network, no Android).
 */
interface SyncTransport {
    fun testConnection(): Boolean
    fun pushBatch(kind: String, items: List<SyncItem>): Boolean
    fun listRemote(kind: String, since: Long): List<SyncRemoteEntry>
}

/**
 * [T-thumb-sync-v1] Minimal OkHttp client for the user's own sync worker.
 * Mirrors the provider APIs (e.g. OpenAIModelsApi): plain blocking OkHttp
 * `execute()` — every method MUST be called off the main thread (the UI
 * wraps calls in Dispatchers.IO).
 *
 * Security: the token goes only into the Authorization header of requests
 * to the configured worker. Neither the token nor payload contents are ever
 * logged — log lines carry exception class names and item counts only.
 */
class SyncClient(
    private val workerUrl: String,
    private val token: String,
) : SyncTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private fun endpoint(path: String) = workerUrl.trimEnd('/') + path

    private fun Request.Builder.authed(): Request.Builder =
        header("Authorization", "Bearer $token")

    /** True when the worker answers `GET /sync/list?kind=trigger_rule` with 200. */
    override fun testConnection(): Boolean = try {
        val request = Request.Builder()
            .url(endpoint("/sync/list?kind=trigger_rule&since=0"))
            .authed()
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (t: Throwable) {
        AppLogger.warning(TAG, "testConnection failed: ${t.javaClass.simpleName}")
        false
    }

    /**
     * Upsert [items] (max [BATCH_LIMIT] per call, enforced by the worker).
     * True on HTTP 2xx. Empty batches are a no-op success.
     */
    override fun pushBatch(kind: String, items: List<SyncItem>): Boolean {
        if (items.isEmpty()) return true
        val body = JSONObject().apply {
            put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        }
        val request = Request.Builder()
            .url(endpoint("/sync/batch"))
            .authed()
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.warning(TAG, "pushBatch $kind: HTTP ${resp.code} (${items.size} items)")
                }
                resp.isSuccessful
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "pushBatch $kind failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * `[{id, updatedAt}]` for [kind] changed after [since], oldest first.
     * v1 pushes only; this exists so the connection test exercises a real
     * read and for the future downsync. Empty list on any failure.
     */
    override fun listRemote(kind: String, since: Long): List<SyncRemoteEntry> = try {
        val request = Request.Builder()
            .url(endpoint("/sync/list?kind=$kind&since=$since"))
            .authed()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(SyncRemoteEntry(o.optString("id"), o.optLong("updatedAt")))
                }
            }
        }
    } catch (t: Throwable) {
        AppLogger.warning(TAG, "listRemote $kind failed: ${t.javaClass.simpleName}")
        emptyList()
    }

    companion object {
        private const val TAG = "SyncClient"
        private const val TIMEOUT_SEC = 15L
        /** Worker-side per-batch cap (tools/sync-worker/README.md). */
        const val BATCH_LIMIT = 200
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
