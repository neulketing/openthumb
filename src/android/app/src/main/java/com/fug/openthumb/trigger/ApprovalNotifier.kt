package com.fug.openthumb.trigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.fug.openthumb.R
import com.fug.openthumb.logging.AppLogger

/**
 * The decision, where the person already is.
 *
 * A pending reply is worthless if approving it means remembering to open an
 * app. So the draft arrives as a notification with Send and Discard on it, and
 * an inline edit box — three taps' worth of UI in the one place a phone user
 * already looks. Nothing here needs the app to be open, or the screen unlocked
 * beyond the lockscreen's own rules.
 *
 * The draft text is shown in full, not summarised. The whole point is that
 * someone reads what is about to be sent in their name.
 */
object ApprovalNotifier {

    const val CHANNEL_ID = "outbound_approval"
    const val ACTION_SEND = "com.fug.openthumb.APPROVAL_SEND"
    const val ACTION_DISCARD = "com.fug.openthumb.APPROVAL_DISCARD"
    const val EXTRA_ID = "approval_id"
    const val KEY_EDITED = "edited_reply"

    private const val TAG = "ApprovalNotifier"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.approval_channel_name),
                // High: this is a question that blocks a reply someone is
                // waiting on. Silent would leave drafts to expire unseen.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.approval_channel_description)
            },
        )
    }

    fun post(context: Context, p: OutboundApproval.Pending) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val app = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(p.pkg, 0)).toString()
        }.getOrDefault(p.pkg)

        val title = context.getString(R.string.approval_title, app)
        val body = if (p.conversation.isNotBlank()) {
            context.getString(R.string.approval_body_with_conversation, p.conversation, p.draft)
        } else {
            p.draft
        }

        // The edit box carries the same action as Send, so correcting a draft
        // and approving it are one gesture rather than two screens.
        val edit = RemoteInput.Builder(KEY_EDITED)
            .setLabel(context.getString(R.string.approval_edit_hint))
            .build()

        val sendIntent = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.approval_send),
            pending(context, ACTION_SEND, p.id),
        ).addRemoteInput(edit).setAllowGeneratedReplies(false).build()

        val discardIntent = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.approval_discard),
            pending(context, ACTION_DISCARD, p.id),
        ).build()

        // Tapping the draft itself opens the list it is waiting in. Without
        // this the body is dead space, and the only way back into the app is to
        // find it on the home screen.
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("minis://settings/triggers"))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(p.draft)
            .setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(sendIntent)
            .addAction(discardIntent)
            // Not dismissible by swipe: swiping away a question is not an
            // answer, and a silently dropped draft is the failure this exists
            // to prevent. It clears when a decision is made, or when it expires.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(p.createdAt)
            .build()

        runCatching { nm.notify(notificationId(p.id), n) }
            .onFailure { AppLogger.warning(TAG, "notify failed: ${it.message}") }
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(notificationId(id))
    }

    /** Stable per-draft id so re-posting updates rather than stacking. */
    fun notificationId(id: String): Int = ("approval:" + id).hashCode()

    private fun pending(context: Context, action: String, id: String): PendingIntent {
        val intent = Intent(context, ApprovalReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
            // Without a distinct data uri, two drafts' intents compare equal
            // under filterEquals and the second reuses the first's extras —
            // approving one would send the other.
            data = android.net.Uri.parse("openthumb://approval/$action/$id")
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

/** Acts on Send / Discard from the approval notification. */
class ApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ApprovalNotifier.EXTRA_ID) ?: return
        val p = OutboundApproval.take(context, id)
        ApprovalNotifier.cancel(context, id)
        if (p == null) {
            AppLogger.info(TAG, "draft $id already decided or expired")
            return
        }

        when (intent.action) {
            ApprovalNotifier.ACTION_DISCARD -> {
                OutboundApproval.record(context, p, "discarded")
                AppLogger.info(TAG, "draft ${p.id} discarded")
            }

            ApprovalNotifier.ACTION_SEND -> {
                val edited = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(ApprovalNotifier.KEY_EDITED)?.toString()?.trim()
                val text = if (edited.isNullOrBlank()) p.draft else edited
                val sent = PendingReplyDispatcher.send(context, p, text)
                OutboundApproval.record(
                    context,
                    p.copy(draft = text),
                    if (sent) "sent" else "send_failed",
                    if (edited.isNullOrBlank()) "" else "edited before sending",
                )
                AppLogger.info(TAG, "draft ${p.id} " + if (sent) "sent" else "send failed")
            }
        }
    }

    private companion object {
        const val TAG = "ApprovalReceiver"
    }
}
