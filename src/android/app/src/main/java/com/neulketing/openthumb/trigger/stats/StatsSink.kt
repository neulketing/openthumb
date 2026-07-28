package com.neulketing.openthumb.trigger.stats

import com.neulketing.openthumb.logging.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * [T-thumb-stats-schema] Local-only sink for stats events: appends one JSON
 * object per line to `events.jsonl`, rotating at [MAX_BYTES] by dropping
 * the oldest half of the file. There is deliberately no network code here —
 * see docs/specs/stats-schema.md ("off by default").
 *
 * The file location is injected so unit tests can point at a temp dir; the
 * app passes `File(context.filesDir, "stats")`.
 */
class StatsSink(private val dir: File) {

    private val file: File get() = File(dir, "events.jsonl")

    @Synchronized
    fun append(event: JSONObject) {
        try {
            dir.mkdirs()
            file.appendText(event.toString() + "\n")
            rotateIfNeeded()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "stats append failed: ${t.message}")
        }
    }

    private fun rotateIfNeeded() {
        if (file.length() <= MAX_BYTES) return
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    companion object {
        private const val TAG = "StatsSink"
        private const val MAX_BYTES = 1_000_000L
    }
}
