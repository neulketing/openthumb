package com.fug.openthumb.trigger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that stands between what the agent wrote and another person.
 *
 * These are the cases where being wrong is expensive: sending when the user
 * expected to be asked, and — the subtler one — a per-rule flag failing to hold
 * a reply the global setting would have released.
 */
class OutboundApprovalTest {

    private val none = emptySet<String>()

    @Test
    fun `the default asks every time`() {
        assertEquals(OutboundApproval.Mode.ALWAYS, OutboundApproval.Mode.from(null))
        assertFalse(OutboundApproval.decide(OutboundApproval.Mode.ALWAYS, false, "com.a", none))
    }

    @Test
    fun `an unreadable stored mode falls back to asking, never to sending`() {
        // A corrupted or hand-edited preference must not be able to turn the
        // gate off; the safe reading is the only reading.
        assertEquals(OutboundApproval.Mode.ALWAYS, OutboundApproval.Mode.from("garbage"))
        assertEquals(OutboundApproval.Mode.ALWAYS, OutboundApproval.Mode.from(""))
        assertEquals(OutboundApproval.Mode.NEVER, OutboundApproval.Mode.from("never"))
        assertEquals(OutboundApproval.Mode.NEVER, OutboundApproval.Mode.from("NEVER"))
    }

    @Test
    fun `never ask sends without waiting`() {
        assertTrue(OutboundApproval.decide(OutboundApproval.Mode.NEVER, false, "com.a", none))
    }

    @Test
    fun `allowlist sends only for the apps on it`() {
        val list = setOf("com.allowed")
        assertTrue(
            OutboundApproval.decide(OutboundApproval.Mode.ALLOWLIST, false, "com.allowed", list),
        )
        assertFalse(
            OutboundApproval.decide(OutboundApproval.Mode.ALLOWLIST, false, "com.other", list),
        )
    }

    @Test
    fun `an empty allowlist holds everything`() {
        assertFalse(OutboundApproval.decide(OutboundApproval.Mode.ALLOWLIST, false, "com.a", none))
    }

    @Test
    fun `a package is matched exactly, not by prefix`() {
        // com.allowed.evil must not inherit com.allowed's permission.
        val list = setOf("com.allowed")
        assertFalse(
            OutboundApproval.decide(
                OutboundApproval.Mode.ALLOWLIST, false, "com.allowed.evil", list,
            ),
        )
    }

    @Test
    fun `a rule marked for approval waits under every mode`() {
        for (mode in OutboundApproval.Mode.entries) {
            assertFalse(
                "rule flag must hold the reply under $mode",
                OutboundApproval.decide(mode, true, "com.allowed", setOf("com.allowed")),
            )
        }
    }

    @Test
    fun `expiry is decided by age, not by clock time`() {
        val queued = 1_000_000L
        assertFalse(OutboundApproval.isExpired(queued, queued + 29 * 60_000L, 30))
        assertFalse(OutboundApproval.isExpired(queued, queued + 30 * 60_000L, 30))
        assertTrue(OutboundApproval.isExpired(queued, queued + 31 * 60_000L, 30))
    }

    @Test
    fun `a draft survives being written and read back`() {
        val p = OutboundApproval.Pending(
            id = "abc",
            ruleId = "rule-1",
            ruleName = "Reply in messages",
            pkg = "com.example.chat",
            conversation = "Jae",
            sbnKey = "0|com.example.chat|42|null|10123",
            incoming = "are you free tonight",
            draft = "I am, after seven.",
            createdAt = 1_700_000_000_000L,
        )
        val round = OutboundApproval.Pending.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, round)
        // Without the key, an approved draft cannot find its conversation again
        // and every send silently fails.
        assertTrue(round.sbnKey.isNotEmpty())
    }
}
