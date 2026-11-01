package com.openminis.app.phonecheck

import com.openminis.app.phonecheck.PhoneCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhoneCheckTest {
    private val today = LocalDate.of(2026, 11, 2)
    private val clean = PhoneCheck.Facts(
        freeBytes = 50, totalBytes = 100, batteryGood = true, securityPatch = "2026-10-01",
        readsScreen = emptyList(), readsNotifications = emptyList(), deviceAdmins = emptyList(),
    )

    private fun level(f: PhoneCheck.Facts, id: String) =
        PhoneCheck.judge(f, today).first { it.id == id }.level

    @Test
    fun `a clean phone scores 100`() {
        val found = PhoneCheck.judge(clean, today)
        assertTrue(found.all { it.level == Level.OK })
        assertEquals(100, PhoneCheck.score(found))
    }

    @Test
    fun `storage thresholds sit on the percent`() {
        assertEquals(Level.WARN, level(clean.copy(freeBytes = 15), "storage"))
        assertEquals(Level.OK, level(clean.copy(freeBytes = 16), "storage"))
        assertEquals(Level.BAD, level(clean.copy(freeBytes = 5), "storage"))
    }

    @Test
    fun `patch age comes from the date and a bad date is skipped not guessed`() {
        assertEquals(Level.WARN, level(clean.copy(securityPatch = "2026-03-01"), "patch"))
        assertEquals(Level.BAD, level(clean.copy(securityPatch = "2019-01-01"), "patch"))
        assertTrue(PhoneCheck.judge(clean.copy(securityPatch = ""), today).none { it.id == "patch" })
    }

    @Test
    fun `an unknown battery says nothing`() {
        assertTrue(PhoneCheck.judge(clean.copy(batteryGood = null), today).none { it.id == "battery" })
        assertEquals(Level.WARN, level(clean.copy(batteryGood = false), "battery"))
    }

    @Test
    fun `apps with reach are named and handed to the agent without permission to act`() {
        val f = PhoneCheck.judge(clean.copy(readsScreen = listOf("Foo", "Bar")), today).first { it.id == "screen" }
        assertEquals(Level.WARN, f.level)
        assertEquals(listOf<Any>(2, "Foo, Bar"), f.args)
        val prompt = f.fixPrompt!!
        assertTrue(prompt, prompt.contains("Foo, Bar") && prompt.contains("until I approve"))
    }

    @Test
    fun `nothing with reach leaves nothing to fix`() {
        assertNull(PhoneCheck.judge(clean, today).first { it.id == "admins" }.fixPrompt)
    }

    @Test
    fun `score floors at zero`() {
        val bad = clean.copy(
            freeBytes = 1, securityPatch = "2019-01-01", batteryGood = false,
            readsScreen = listOf("a"), readsNotifications = listOf("b"), deviceAdmins = listOf("c"),
        )
        assertEquals(100 - 25 - 25 - 8 * 4, PhoneCheck.score(PhoneCheck.judge(bad, today)))
        assertEquals(0, PhoneCheck.score(List(9) { PhoneCheck.judge(bad, today).first() }))
    }
}
