package com.neulketing.openthumb.trigger

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.neulketing.openthumb.logging.AppLogger

/**
 * [T-thumb-notification-reply] Sends the agent's answer back into the
 * conversation the notification came from.
 *
 * Messengers that support wear/quick reply attach a notification action whose
 * `remoteInputs` carry a free-text field; firing that action's PendingIntent
 * with the text filled in posts the message as if the user had typed it in the
 * app. That is the whole mechanism — no accessibility injection, no UI
 * scripting, no per-app protocol. It is the public Android reply contract, so
 * every messenger that ships a reply action (KakaoTalk, SMS, Telegram, LINE, …)
 * works with the same code path and none of them can tell the difference.
 *
 * The engine pairs this with [NotificationTriggerEngine]: notification in →
 * agent runs → reply out, which turns any such messenger into a two-way channel
 * to the on-device agent.
 */
object NotificationReplier {

    private const val TAG = "NotificationReplier"

    /** Longest reply we will post. Messenger inputs reject or truncate huge bodies. */
    const val MAX_REPLY_CHARS = 1500

    /**
     * True when [sbn] carries a usable free-text reply action. Cheap enough to
     * call during evaluation so a rule can be skipped before running the agent.
     */
    fun canReply(sbn: StatusBarNotification): Boolean = findReplyAction(sbn) != null

    /**
     * Post [text] as a reply to [sbn].
     *
     * @return true when the reply intent was sent. False means the notification
     *   had no reply action, the text was blank, or the owning app revoked the
     *   PendingIntent (a stale notification) — all recoverable, none fatal.
     */
    fun reply(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        val body = text.trim().take(MAX_REPLY_CHARS)
        if (body.isEmpty()) {
            AppLogger.info(TAG, "reply skipped: empty agent response")
            return false
        }
        val action = findReplyAction(sbn) ?: run {
            AppLogger.info(TAG, "reply skipped: ${sbn.packageName} has no reply action")
            return false
        }
        val inputs = action.remoteInputs ?: return false

        val bundle = Bundle()
        // Every declared input gets the text: apps that split the reply across
        // several RemoteInputs read whichever key they registered.
        for (input in inputs) bundle.putCharSequence(input.resultKey, body)

        val fillIn = Intent()
        RemoteInput.addResultsToIntent(inputs, fillIn, bundle)

        return runCatching {
            action.actionIntent.send(context, 0, fillIn)
            AppLogger.info(TAG, "replied to ${sbn.packageName} (${body.length} chars)")
            true
        }.getOrElse { t ->
            // PendingIntent.CanceledException when the source notification is
            // gone. Report it, never crash the listener thread.
            AppLogger.warning(TAG, "reply failed for ${sbn.packageName}: ${t.message}")
            false
        }
    }

    /**
     * First notification action that accepts free text. Actions without
     * `remoteInputs` (Mark as read, Mute, …) are not reply targets, and an
     * input with `choices` but no free-text entry only accepts canned replies,
     * so it cannot carry an agent answer.
     */
    private fun findReplyAction(sbn: StatusBarNotification) =
        sbn.notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
}
