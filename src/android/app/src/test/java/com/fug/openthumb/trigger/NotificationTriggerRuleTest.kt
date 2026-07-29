package com.fug.openthumb.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thumb-notification-triggers] Contract of the pure trigger logic:
 * matching, cooldown, prompt rendering, JSON round-trip.
 */
class NotificationTriggerRuleTest {

    private fun rule(
        appPackage: String? = "com.kakao.talk",
        matchContains: String? = null,
        enabled: Boolean = true,
        cooldownSec: Int = 300,
        lastFiredAt: Long? = null,
    ) = NotificationTriggerRule(
        id = "r1",
        label = "test",
        appPackage = appPackage,
        matchContains = matchContains,
        prompt = "handle this",
        cooldownSec = cooldownSec,
        enabled = enabled,
        createdAt = 0L,
        lastFiredAt = lastFiredAt,
    )

    // -- matches ----------------------------------------------------------

    @Test
    fun `package must match when set`() {
        assertTrue(NotificationTriggerRule.matches(rule(), "com.kakao.talk", "t", "x"))
        assertFalse(NotificationTriggerRule.matches(rule(), "com.other.app", "t", "x"))
    }

    @Test
    fun `null package matches any app`() {
        assertTrue(NotificationTriggerRule.matches(rule(appPackage = null), "com.any.app", "t", "x"))
    }

    @Test
    fun `substring match is case-insensitive over title and text`() {
        val r = rule(matchContains = "URGENT")
        assertTrue(NotificationTriggerRule.matches(r, "com.kakao.talk", "this is urgent", ""))
        assertTrue(NotificationTriggerRule.matches(r, "com.kakao.talk", "", "Urgent: reply now"))
        assertFalse(NotificationTriggerRule.matches(r, "com.kakao.talk", "calm", "nothing here"))
    }

    @Test
    fun `disabled rule never matches`() {
        assertFalse(NotificationTriggerRule.matches(rule(enabled = false), "com.kakao.talk", "t", "x"))
    }

    // -- cooldown ---------------------------------------------------------

    @Test
    fun `never-fired rule may fire`() {
        assertTrue(NotificationTriggerRule.shouldFire(rule(lastFiredAt = null), nowMs = 1_000L))
    }

    @Test
    fun `rule inside cooldown may not fire, outside may`() {
        val r = rule(cooldownSec = 60, lastFiredAt = 100_000L)
        assertFalse(NotificationTriggerRule.shouldFire(r, nowMs = 100_000L + 59_999L))
        assertTrue(NotificationTriggerRule.shouldFire(r, nowMs = 100_000L + 60_000L))
    }

    // -- prompt rendering -------------------------------------------------

    @Test
    fun `placeholders substitute and raw notification block is appended`() {
        val r = rule().copy(prompt = "From {app}: {title} / {text}")
        val out = NotificationTriggerRule.renderPrompt(r, "com.kakao.talk", "Kim", "hello")
        assertTrue(out.startsWith("From com.kakao.talk: Kim / hello"))
        assertTrue(out.contains("[Triggering notification]"))
        assertTrue(out.contains("app: com.kakao.talk"))
    }

    // -- JSON round-trip --------------------------------------------------

    @Test
    fun `json round-trip preserves all fields`() {
        val r = rule(matchContains = "hi", lastFiredAt = 42L)
        val back = NotificationTriggerRule.fromJson(r.toJson())
        assertEquals(r, back)
    }

    @Test
    fun `json round-trip preserves nulls`() {
        val r = rule(appPackage = null, matchContains = null, lastFiredAt = null)
        val back = NotificationTriggerRule.fromJson(r.toJson())
        assertNull(back.appPackage)
        assertNull(back.matchContains)
        assertNull(back.lastFiredAt)
        assertEquals(r, back)
    }

    // -- active window ------------------------------------------------------

    private fun atTime(hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `null window is always active`() {
        assertTrue(NotificationTriggerRule.isWithinWindow(null, null, atTime(3, 30)))
    }

    @Test
    fun `daytime window contains its hours and excludes others`() {
        assertTrue(NotificationTriggerRule.isWithinWindow(9 * 60, 18 * 60, atTime(10, 0)))
        assertFalse(NotificationTriggerRule.isWithinWindow(9 * 60, 18 * 60, atTime(20, 0)))
    }

    @Test
    fun `window start is inclusive and end is exclusive`() {
        assertTrue(NotificationTriggerRule.isWithinWindow(9 * 60, 18 * 60, atTime(9, 0)))
        assertFalse(NotificationTriggerRule.isWithinWindow(9 * 60, 18 * 60, atTime(18, 0)))
    }

    @Test
    fun `overnight window wraps past midnight`() {
        assertTrue(NotificationTriggerRule.isWithinWindow(22 * 60, 7 * 60, atTime(23, 0)))
        assertTrue(NotificationTriggerRule.isWithinWindow(22 * 60, 7 * 60, atTime(6, 59)))
        assertFalse(NotificationTriggerRule.isWithinWindow(22 * 60, 7 * 60, atTime(12, 0)))
    }

    @Test
    fun `rule active window goes through the shared window test`() {
        val r = rule().copy(activeStartMin = 22 * 60, activeEndMin = 7 * 60)
        assertTrue(NotificationTriggerRule.withinActiveWindow(r, atTime(1, 0)))
        assertFalse(NotificationTriggerRule.withinActiveWindow(r, atTime(15, 0)))
    }

    @Test
    fun `json round-trip preserves the active window`() {
        val r = rule().copy(activeStartMin = 540, activeEndMin = 1080)
        val back = NotificationTriggerRule.fromJson(r.toJson())
        assertEquals(540, back.activeStartMin)
        assertEquals(1080, back.activeEndMin)
        assertEquals(r, back)
    }

    // -- reply opt-in -----------------------------------------------------

    @Test
    fun `replyToNotification defaults off and survives a JSON round-trip`() {
        val off = rule()
        assertFalse(off.replyToNotification)
        // Absent key must decode as off, so rules written before the field
        // existed keep their one-way behaviour instead of silently answering.
        assertFalse(off.toJson().has("replyToNotification"))
        assertFalse(NotificationTriggerRule.fromJson(off.toJson()).replyToNotification)

        val on = rule().copy(replyToNotification = true)
        assertTrue(NotificationTriggerRule.fromJson(on.toJson()).replyToNotification)
    }
}
