package com.neulketing.openthumb.trigger

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
}
