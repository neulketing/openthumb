package com.fug.openthumb.trigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device proof that an approved reply actually reaches the conversation.
 *
 * Everything else about the approval gate can be checked without hardware: the
 * decision is a pure function, the queue and ledger are files, and the
 * notification and Discard path were driven on a Note8. The one line that
 * resisted all of it is `action.actionIntent.send(...)` — delivering the text
 * into someone else's app — because exercising it needs a notification that
 * carries a free-form reply action, and no test can make a messenger post one.
 *
 * It can post one itself. This test builds a notification with exactly the
 * shape [NotificationReplier.findReplyAction] looks for, aims its RemoteInput
 * at a receiver in this app, and asserts the text arrives. The code under test
 * does not know or care that the conversation on the other end is us.
 *
 * Requires notification access, which is what lets the listener see the posted
 * notification and hand back a StatusBarNotification:
 *
 *   adb shell settings put secure enabled_notification_listeners \
 *     com.fug.openthumb/com.fug.openthumb.offload.MinisNotificationListenerService
 */
@RunWith(AndroidJUnit4::class)
class NotificationReplierInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val received = arrayOfNulls<String>(1)
    private var latch = CountDownLatch(1)
    private var receiver: BroadcastReceiver? = null

    @Before
    fun setUp() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Replier test", NotificationManager.IMPORTANCE_LOW),
            )
        }
        latch = CountDownLatch(1)
        received[0] = null
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                received[0] = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY)?.toString()
                latch.countDown()
            }
        }
        // Not exported: the only sender is a PendingIntent this test created.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(ACTION))
        }
    }

    @After
    fun tearDown() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Posts a notification shaped like a messenger's, and returns its key. */
    private fun postRepliable(): String {
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", pi,
        ).addRemoteInput(
            // allowFreeFormInput is the property findReplyAction filters on: an
            // action offering only canned choices cannot carry an agent answer.
            android.app.RemoteInput.Builder(KEY).setLabel("Reply").setAllowFreeFormInput(true).build(),
        ).build()

        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Test conversation")
            .setContentText("are you free tonight")
            .addAction(action)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        return "0|${context.packageName}|$NOTIF_ID|null|${android.os.Process.myUid()}"
    }

    private fun waitForOwnNotification(): android.service.notification.StatusBarNotification? {
        // The listener sees notifications asynchronously; poll rather than sleep
        // a fixed amount, so a slow device does not fail a working path.
        repeat(40) {
            com.fug.openthumb.offload.MinisNotificationListenerService.getActiveNotifications()
                ?.firstOrNull { it.packageName == context.packageName && it.id == NOTIF_ID }
                ?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    @Test
    fun anApprovedReplyReachesTheConversation() {
        postRepliable()
        val sbn = waitForOwnNotification()
        // A null here means notification access is off, not that replying is
        // broken — say which, or the failure sends someone to the wrong code.
        assertNotNull(
            "notification access is not granted; see the adb command in this file's kdoc",
            sbn,
        )

        assertTrue("reply action not detected", NotificationReplier.canReply(sbn!!))
        assertTrue("reply reported failure", NotificationReplier.reply(context, sbn, REPLY))
        assertTrue("reply never arrived", latch.await(10, TimeUnit.SECONDS))
        assertEquals(REPLY, received[0])
    }

    @Test
    fun aNotificationWithoutAReplyActionIsNotRepliedTo() {
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Bank alert")
            .setContentText("card used")
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        val sbn = waitForOwnNotification()
        assertNotNull("notification access is not granted", sbn)
        // A bank alert has nowhere to send an answer. Attempting one would burn
        // a rule's cooldown and a full agent run to produce nothing.
        assertFalse(NotificationReplier.canReply(sbn!!))
        assertFalse(NotificationReplier.reply(context, sbn, REPLY))
    }

    @Test
    fun anApprovedDraftFindsItsConversationByKey() {
        val sbn = postRepliable().let { waitForOwnNotification() }
        assertNotNull("notification access is not granted", sbn)
        val pending = OutboundApproval.Pending(
            id = "test-1",
            ruleId = "r", ruleName = "Reply in messages",
            pkg = context.packageName,
            conversation = "Test conversation",
            sbnKey = sbn!!.key,
            incoming = "are you free tonight",
            draft = REPLY,
            createdAt = System.currentTimeMillis(),
        )
        // The lookup is the part this fork added: a StatusBarNotification holds
        // live PendingIntents and cannot be persisted, so an approved draft has
        // to find its conversation again among the notifications still on screen.
        assertTrue(PendingReplyDispatcher.send(context, pending, REPLY))
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(REPLY, received[0])
    }

    @Test
    fun aDraftWhoseConversationIsGoneFailsRatherThanGuessing() {
        val pending = OutboundApproval.Pending(
            id = "test-2",
            ruleId = "r", ruleName = "Reply in messages",
            pkg = context.packageName,
            conversation = "",
            sbnKey = "0|com.example.gone|999|null|0",
            incoming = "", draft = REPLY,
            createdAt = System.currentTimeMillis(),
        )
        // Recording a failed send beats inventing somewhere to put the text.
        assertFalse(PendingReplyDispatcher.send(context, pending, REPLY))
    }

    private companion object {
        const val CHANNEL = "replier_test"
        const val ACTION = "com.fug.openthumb.test.REPLY_LANDED"
        const val KEY = "test_reply_key"
        const val NOTIF_ID = 918273
        const val REPLY = "I am, after seven."
    }
}
