package com.fug.openthumb.trigger

import android.content.Context
import com.fug.openthumb.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Nothing the agent writes reaches another person without someone saying yes.
 *
 * The trigger engine already had five gates — quiet hours, an active window, a
 * per-rule cooldown, a concurrency cap, and a check that the notification can
 * be replied to at all. Every one of them limits *how often* a reply is sent.
 * None of them looks at *what* it says. So an agent that misreads a message
 * answers a real person, at speed, and the first anyone hears of it is the
 * reply already in the thread.
 *
 * This adds the missing gate: a draft, a decision, and a record of both. The
 * decision is the user's, and [Mode] is how they choose when to be asked.
 *
 * Default is [Mode.ALWAYS]. A gate that ships off is not a gate, and the
 * setting exists so the person who understands their own risk can lower it —
 * not so the risky option can be the one nobody chose.
 */
object OutboundApproval {

    /** When the user wants to be asked before a reply is sent. */
    enum class Mode {
        /** Every reply waits for a decision. The default. */
        ALWAYS,

        /** Replies to apps on the allowlist send themselves; everything else waits. */
        ALLOWLIST,

        /** Nothing waits. Only sensible when the rules only touch you. */
        NEVER,
        ;

        companion object {
            fun from(raw: String?): Mode =
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALWAYS
        }
    }

    /** A reply the agent wrote, waiting for a decision. */
    data class Pending(
        val id: String,
        val ruleId: String,
        val ruleName: String,
        val pkg: String,
        val conversation: String,
        /**
         * The notification's own key. A StatusBarNotification holds live
         * PendingIntents and cannot be persisted, so this is what lets an
         * approved draft find its conversation again.
         */
        val sbnKey: String,
        val incoming: String,
        val draft: String,
        val createdAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("ruleId", ruleId)
            put("ruleName", ruleName)
            put("pkg", pkg)
            put("conversation", conversation)
            put("sbnKey", sbnKey)
            put("incoming", incoming)
            put("draft", draft)
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(o: JSONObject) = Pending(
                id = o.getString("id"),
                ruleId = o.optString("ruleId"),
                ruleName = o.optString("ruleName"),
                pkg = o.optString("pkg"),
                conversation = o.optString("conversation"),
                sbnKey = o.optString("sbnKey"),
                incoming = o.optString("incoming"),
                draft = o.optString("draft"),
                createdAt = o.optLong("createdAt"),
            )
        }
    }

    private const val PREFS = "outbound_approval"
    private const val KEY_MODE = "mode"
    private const val KEY_ALLOWLIST = "allowlist"
    private const val KEY_PENDING = "pending"
    private const val KEY_EXPIRY_MINUTES = "expiry_minutes"

    /**
     * A draft older than this is dropped rather than sent. An answer to a
     * message from hours ago is worse than no answer: the conversation moved
     * on, and approving it later sends a reply that no longer fits.
     */
    const val DEFAULT_EXPIRY_MINUTES = 30

    private const val LEDGER = "outbound-ledger.jsonl"
    private const val TAG = "OutboundApproval"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- settings -------------------------------------------------------

    fun mode(context: Context): Mode = Mode.from(prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        AppLogger.info(TAG, "approval mode set to ${mode.name}")
    }

    /** Packages whose replies send themselves under [Mode.ALLOWLIST]. */
    fun allowlist(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWLIST, emptySet()).orEmpty()

    fun setAllowlist(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_ALLOWLIST, packages).apply()
    }

    fun expiryMinutes(context: Context): Int =
        prefs(context).getInt(KEY_EXPIRY_MINUTES, DEFAULT_EXPIRY_MINUTES)

    fun setExpiryMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_EXPIRY_MINUTES, minutes.coerceIn(1, 24 * 60)).apply()
    }

    /**
     * Whether this reply may be sent without asking.
     *
     * `requireApproval` on the rule can only tighten the decision, never loosen
     * it: a rule marked as needing approval waits even in [Mode.NEVER], because
     * the person who marked it knew something the global setting does not.
     */
    fun autoSendAllowed(context: Context, rule: NotificationTriggerRule?, pkg: String): Boolean {
        if (rule?.requireApproval == true) return false
        return when (mode(context)) {
            Mode.ALWAYS -> false
            Mode.NEVER -> true
            Mode.ALLOWLIST -> pkg in allowlist(context)
        }
    }

    // ---- the queue ------------------------------------------------------

    /** Drafts still waiting, newest first, with expired ones already dropped. */
    @Synchronized
    fun pending(context: Context): List<Pending> {
        val cutoff = System.currentTimeMillis() - expiryMinutes(context) * 60_000L
        val all = readPending(context)
        val live = all.filter { it.createdAt >= cutoff }
        if (live.size != all.size) {
            for (p in all - live.toSet()) record(context, p, "expired")
            writePending(context, live)
        }
        return live.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun enqueue(
        context: Context,
        rule: NotificationTriggerRule?,
        pkg: String,
        conversation: String,
        sbnKey: String,
        incoming: String,
        draft: String,
    ): Pending {
        val p = Pending(
            id = UUID.randomUUID().toString(),
            ruleId = rule?.id.orEmpty(),
            ruleName = rule?.label.orEmpty(),
            pkg = pkg,
            conversation = conversation,
            sbnKey = sbnKey,
            incoming = incoming,
            draft = draft,
            createdAt = System.currentTimeMillis(),
        )
        writePending(context, readPending(context) + p)
        record(context, p, "queued")
        return p
    }

    @Synchronized
    fun take(context: Context, id: String): Pending? {
        val all = readPending(context)
        val found = all.firstOrNull { it.id == id } ?: return null
        writePending(context, all.filter { it.id != id })
        return found
    }

    @Synchronized
    fun clear(context: Context) {
        for (p in readPending(context)) record(context, p, "discarded")
        writePending(context, emptyList())
    }

    private fun readPending(context: Context): List<Pending> {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { Pending.fromJson(o) }.onSuccess { add(it) }
                }
            }
        }.getOrElse {
            AppLogger.warning(TAG, "pending queue unreadable: ${it.message}")
            emptyList()
        }
    }

    private fun writePending(context: Context, items: List<Pending>) {
        val arr = JSONArray()
        for (p in items) arr.put(p.toJson())
        prefs(context).edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    // ---- the ledger -----------------------------------------------------

    /**
     * Append-only, one JSON object per line. Every draft and every decision
     * lands here, including the ones nobody approved — "what did it try to
     * send" is a question the user must be able to answer after the fact, and
     * a record that only holds successes cannot answer it.
     */
    fun record(context: Context, p: Pending, outcome: String, note: String = "") {
        val line = JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("id", p.id)
            put("outcome", outcome)
            put("pkg", p.pkg)
            put("rule", p.ruleName)
            put("conversation", p.conversation)
            put("draft", p.draft)
            if (note.isNotEmpty()) put("note", note)
        }
        runCatching {
            File(context.filesDir, LEDGER).appendText(line.toString() + "\n")
        }.onFailure { AppLogger.warning(TAG, "ledger write failed: ${it.message}") }
    }

    /** Newest [limit] ledger entries. */
    fun ledger(context: Context, limit: Int = 200): List<JSONObject> {
        val f = File(context.filesDir, LEDGER)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().asReversed().asSequence()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .take(limit)
                .toList()
        }.getOrElse { emptyList() }
    }
}
