package com.fug.openthumb.sync

import com.fug.openthumb.scheduled.ScheduledRepeatMode
import com.fug.openthumb.scheduled.ScheduledTask
import com.fug.openthumb.trigger.NotificationTriggerRule
import com.fug.openthumb.trigger.NotificationTriggerRun
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thumb-sync-v1] Batch item shape the worker expects
 * (`{kind, id, updatedAt, payload}` — tools/sync-worker/README.md) and the
 * per-store item mapping (id choice, updatedAt from the record's own
 * timestamps, payload = the store's JSON row).
 */
class SyncItemTest {

    @Test
    fun `item serializes with kind id updatedAt payload keys`() {
        val payload = JSONObject().put("hello", "world")
        val item = SyncItem("trigger_rule", "r1", 123L, payload)
        val json = item.toJson()

        assertEquals(setOf("kind", "id", "updatedAt", "payload"), json.keySet())
        assertEquals("trigger_rule", json.getString("kind"))
        assertEquals("r1", json.getString("id"))
        assertEquals(123L, json.getLong("updatedAt"))
        assertEquals("world", json.getJSONObject("payload").getString("hello"))
    }

    @Test
    fun `rule item uses rule id and store json payload`() {
        val rule = NotificationTriggerRule(
            id = "r1",
            label = "kakao",
            prompt = "handle",
            createdAt = 100L,
            lastFiredAt = 160L,
        )
        val item = SyncManager.triggerRuleItem(rule)
        assertEquals(SyncManager.KIND_TRIGGER_RULE, item.kind)
        assertEquals("r1", item.id)
        // updatedAt = last state change (fired after created).
        assertEquals(160L, item.updatedAt)
        assertEquals(rule.toJson().toString(), item.payload.toString())
    }

    @Test
    fun `rule item falls back to createdAt when never fired`() {
        val rule = NotificationTriggerRule(id = "r2", label = "x", prompt = "p", createdAt = 77L)
        assertEquals(77L, SyncManager.triggerRuleItem(rule).updatedAt)
    }

    @Test
    fun `run item derives a stable id from rule and fire time`() {
        val run = NotificationTriggerRun(
            ruleId = "r1",
            ruleLabel = "kakao",
            firedAt = 999L,
            pkg = "com.kakao.talk",
            title = "hi",
            ok = true,
        )
        val item = SyncManager.triggerRunItem(run)
        assertEquals(SyncManager.KIND_TRIGGER_RUN, item.kind)
        assertEquals("r1:999", item.id)
        assertEquals(999L, item.updatedAt)
        assertEquals(run.toJson().toString(), item.payload.toString())
    }

    @Test
    fun `task item uses task id and latest of created and fired`() {
        val task = ScheduledTask(
            id = "t1",
            label = "standup",
            timeOfDayHour = 9,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.DAILY,
            prompt = "post standup",
            createdAt = 500L,
            lastFiredAt = 800L,
        )
        val item = SyncManager.scheduledTaskItem(task)
        assertEquals(SyncManager.KIND_SCHEDULED_TASK, item.kind)
        assertEquals("t1", item.id)
        assertEquals(800L, item.updatedAt)
        assertEquals(task.toJson().toString(), item.payload.toString())
    }

    @Test
    fun `batch body shape matches worker api`() {
        val items = listOf(
            SyncItem("trigger_rule", "r1", 1L, JSONObject()),
            SyncItem("trigger_rule", "r2", 2L, JSONObject()),
        )
        // The client posts {"items": [...]}; build the same body here to pin
        // the contract without any network.
        val body = JSONObject().apply {
            put("items", org.json.JSONArray().apply { items.forEach { put(it.toJson()) } })
        }
        val arr = body.getJSONArray("items")
        assertEquals(2, arr.length())
        assertTrue(arr.getJSONObject(0).has("payload"))
        assertEquals("r2", arr.getJSONObject(1).getString("id"))
    }
}
