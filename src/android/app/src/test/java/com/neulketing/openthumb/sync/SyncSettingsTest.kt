package com.neulketing.openthumb.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thumb-sync-v1] SyncSettings contract: off by default, and every field
 * round-trips through SharedPreferences (a fresh instance over the same
 * prefs sees the same values).
 */
class SyncSettingsTest {

    private fun newSettings(prefs: FakeSharedPreferences = FakeSharedPreferences()) =
        SyncSettings(prefs)

    @Test
    fun `defaults are off and empty`() {
        val s = newSettings()
        assertFalse(s.enabled)
        assertEquals("", s.workerUrl)
        assertEquals("", s.token)
        assertFalse(s.isConfigured)
        assertEquals(0L, s.lastSyncAt(SyncManager.KIND_TRIGGER_RULE))
    }

    @Test
    fun `all fields round-trip through prefs`() {
        val prefs = FakeSharedPreferences()
        newSettings(prefs).apply {
            workerUrl = "https://openthumb-sync.me.workers.dev"
            token = "deadbeef"
            enabled = true
            setLastSyncAt(SyncManager.KIND_TRIGGER_RULE, 111L)
            setLastSyncAt(SyncManager.KIND_TRIGGER_RUN, 222L)
            setLastSyncAt(SyncManager.KIND_SCHEDULED_TASK, 333L)
        }

        val back = newSettings(prefs)
        assertTrue(back.enabled)
        assertEquals("https://openthumb-sync.me.workers.dev", back.workerUrl)
        assertEquals("deadbeef", back.token)
        assertTrue(back.isConfigured)
        assertEquals(111L, back.lastSyncAt(SyncManager.KIND_TRIGGER_RULE))
        assertEquals(222L, back.lastSyncAt(SyncManager.KIND_TRIGGER_RUN))
        assertEquals(333L, back.lastSyncAt(SyncManager.KIND_SCHEDULED_TASK))
    }

    @Test
    fun `url and token are trimmed on write`() {
        val s = newSettings()
        s.workerUrl = "  https://x.workers.dev/ \n"
        s.token = "  abc  "
        assertEquals("https://x.workers.dev/", s.workerUrl)
        assertEquals("abc", s.token)
    }

    @Test
    fun `isConfigured needs both url and token`() {
        val s = newSettings()
        s.workerUrl = "https://x.workers.dev"
        assertFalse(s.isConfigured)
        s.token = "t"
        assertTrue(s.isConfigured)
        s.workerUrl = ""
        assertFalse(s.isConfigured)
    }

    @Test
    fun `watermarks are per kind and independent`() {
        val s = newSettings()
        s.setLastSyncAt(SyncManager.KIND_TRIGGER_RULE, 42L)
        assertEquals(42L, s.lastSyncAt(SyncManager.KIND_TRIGGER_RULE))
        assertEquals(0L, s.lastSyncAt(SyncManager.KIND_TRIGGER_RUN))
    }
}
