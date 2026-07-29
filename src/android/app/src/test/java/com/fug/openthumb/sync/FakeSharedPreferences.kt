package com.fug.openthumb.sync

import android.content.SharedPreferences

/**
 * [T-thumb-sync-v1] In-memory SharedPreferences so the prefs-backed sync
 * classes run under plain JUnit (no Robolectric, no device). Only what
 * [SyncSettings] exercises is meaningfully implemented; the rest throws on
 * accidental use.
 */
class FakeSharedPreferences(
    private val map: MutableMap<String, Any?> = mutableMapOf(),
) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        throw UnsupportedOperationException("not needed by sync tests")

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableListOf<String>()
        private var cleared = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals.add(key) }
        override fun clear() = apply { cleared = true }

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            throw UnsupportedOperationException("not needed by sync tests")

        override fun commit(): Boolean {
            applyToMap()
            return true
        }

        override fun apply() {
            applyToMap()
        }

        private fun applyToMap() {
            if (cleared) map.clear()
            removals.forEach { map.remove(it) }
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}
