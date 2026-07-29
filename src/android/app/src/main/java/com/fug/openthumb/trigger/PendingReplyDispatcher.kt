package com.fug.openthumb.trigger

import android.content.Context
import com.fug.openthumb.logging.AppLogger
import com.fug.openthumb.offload.MinisNotificationListenerService

/**
 * Sends an approved draft, by finding the conversation it belongs to again.
 *
 * A reply cannot be posted without the notification that carried the reply
 * action, and a `StatusBarNotification` holds live PendingIntents — it cannot
 * be written to disk and read back after the process restarts. So the queue
 * stores the notification's key and this looks it up among the notifications
 * still on screen at the moment of approval.
 *
 * That the lookup can fail is the correct behaviour, not a gap. If the person
 * cleared the notification, or the messenger replaced it, the reply action is
 * gone and there is nothing to reply to. Better to record a failed send than to
 * invent a new conversation to put the text in.
 */
object PendingReplyDispatcher {

    private const val TAG = "PendingReplyDispatcher"

    fun send(context: Context, p: OutboundApproval.Pending, text: String): Boolean {
        val active = MinisNotificationListenerService.getActiveNotifications()
        if (active == null) {
            AppLogger.warning(TAG, "notification access is off — cannot send ${p.id}")
            return false
        }
        val sbn = active.firstOrNull { it.key == p.sbnKey }
            ?: active.firstOrNull { it.packageName == p.pkg && it.key.contains(p.conversation) }
        if (sbn == null) {
            AppLogger.info(
                TAG,
                "the conversation for ${p.id} is no longer on screen — nothing to reply to",
            )
            return false
        }
        return NotificationReplier.reply(context, sbn, text)
    }
}
