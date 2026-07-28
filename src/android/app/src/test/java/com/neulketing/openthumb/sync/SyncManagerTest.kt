package com.neulketing.openthumb.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thumb-sync-v1] SyncManager's "what would be pushed" query and the
 * push loop: watermark filtering, batching at the worker's 200-item cap,
 * watermark persistence, and resume behaviour after a failed batch.
 * All fakes in-memory — hermetic, no network.
 */
class SyncManagerTest {

    /** Records every pushBatch call; [failOnKind] makes that kind's first batch fail. */
    private class FakeTransport(
        val failOnKind: String? = null,
    ) : SyncTransport {
        data class Push(val kind: String, val items: List<SyncItem>)

        val pushes = mutableListOf<Push>()
        var failed = false

        override fun testConnection(): Boolean = true

        override fun pushBatch(kind: String, items: List<SyncItem>): Boolean {
            if (kind == failOnKind && !failed) {
                failed = true
                return false
            }
            pushes.add(Push(kind, items))
            return true
        }

        override fun listRemote(kind: String, since: Long): List<SyncRemoteEntry> = emptyList()
    }

    private class FakeSource(
        override val kind: String,
        private val items: List<SyncItem>,
    ) : SyncManager.SyncSource {
        override fun itemsUpdatedSince(sinceMs: Long) = items.filter { it.updatedAt > sinceMs }
    }

    private fun item(kind: String, id: String, updatedAt: Long) =
        SyncItem(kind, id, updatedAt, JSONObject())

    private fun manager(
        settings: SyncSettings,
        transport: SyncTransport,
        vararg sources: SyncManager.SyncSource,
    ) = SyncManager(settings, transport, sources.toList())

    @Test
    fun `pendingByKind filters out already-synced items`() {
        val settings = SyncSettings(FakeSharedPreferences())
        settings.setLastSyncAt("trigger_rule", 100L)
        val source = FakeSource(
            "trigger_rule",
            listOf(
                item("trigger_rule", "old", 50L),
                item("trigger_rule", "edge", 100L),
                item("trigger_rule", "new", 150L),
            ),
        )
        val pending = manager(settings, FakeTransport(), source).pendingByKind()
        assertEquals(listOf("new"), pending["trigger_rule"]?.map { it.id })
    }

    @Test
    fun `pendingCount sums across kinds`() {
        val settings = SyncSettings(FakeSharedPreferences())
        val m = manager(
            settings,
            FakeTransport(),
            FakeSource("trigger_rule", listOf(item("trigger_rule", "a", 1L))),
            FakeSource("trigger_run", listOf(item("trigger_run", "b", 1L), item("trigger_run", "c", 2L))),
        )
        assertEquals(3, m.pendingCount())
    }

    @Test
    fun `syncNow pushes each kind once and persists watermarks`() {
        val prefs = FakeSharedPreferences()
        val settings = SyncSettings(prefs)
        val transport = FakeTransport()
        val m = manager(
            settings,
            transport,
            FakeSource("trigger_rule", listOf(item("trigger_rule", "r1", 10L), item("trigger_rule", "r2", 20L))),
            FakeSource("trigger_run", listOf(item("trigger_run", "u1", 5L))),
        )

        val report = m.syncNow()
        assertTrue(report.ok)
        assertEquals(3, report.totalPushed)
        assertEquals(2, report.pushedByKind["trigger_rule"])
        assertEquals(1, report.pushedByKind["trigger_run"])
        // Watermarks land on the newest uploaded updatedAt per kind.
        assertEquals(20L, settings.lastSyncAt("trigger_rule"))
        assertEquals(5L, settings.lastSyncAt("trigger_run"))

        // Second pass has nothing to do.
        val second = m.syncNow()
        assertTrue(second.ok)
        assertEquals(0, second.totalPushed)
        assertEquals(2, transport.pushes.size)
    }

    @Test
    fun `syncNow batches at the worker cap`() {
        val settings = SyncSettings(FakeSharedPreferences())
        val transport = FakeTransport()
        val many = (1..450).map { item("trigger_run", "u$it", it.toLong()) }
        val m = manager(settings, transport, FakeSource("trigger_run", many))

        val report = m.syncNow()
        assertTrue(report.ok)
        assertEquals(450, report.totalPushed)
        assertEquals(listOf(200, 200, 50), transport.pushes.map { it.items.size })
        assertEquals(450L, settings.lastSyncAt("trigger_run"))
    }

    @Test
    fun `failed batch marks the kind and does not advance past unsent items`() {
        val settings = SyncSettings(FakeSharedPreferences())
        val many = (1..250).map { item("trigger_run", "u$it", it.toLong()) }
        // Fail the SECOND batch: use a transport that fails once the first
        // 200 landed.
        val transport = object : SyncTransport {
            var calls = 0
            override fun testConnection() = true
            override fun pushBatch(kind: String, items: List<SyncItem>): Boolean {
                calls++
                return calls == 1
            }
            override fun listRemote(kind: String, since: Long) = emptyList<SyncRemoteEntry>()
        }
        val m = manager(settings, transport, FakeSource("trigger_run", many))

        val report = m.syncNow()
        assertFalse(report.ok)
        assertEquals(listOf("trigger_run"), report.failedKinds)
        assertEquals(200, report.totalPushed)
        // The 200 accepted items stay synced; the retry resumes at 201.
        assertEquals(200L, settings.lastSyncAt("trigger_run"))
        assertEquals(50, m.pendingCount())
    }

    @Test
    fun `failure before any successful batch keeps the watermark at zero`() {
        val settings = SyncSettings(FakeSharedPreferences())
        val transport = FakeTransport(failOnKind = "trigger_rule")
        val m = manager(
            settings,
            transport,
            FakeSource("trigger_rule", listOf(item("trigger_rule", "r1", 10L))),
        )
        val report = m.syncNow()
        assertFalse(report.ok)
        assertEquals(0L, settings.lastSyncAt("trigger_rule"))
        assertEquals(1, m.pendingCount())
    }
}
