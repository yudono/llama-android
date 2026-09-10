package com.llamacpp.local

import androidx.preference.PreferenceDataStore

// DataStore preferensi di atas SQLite (kv) — layar Settings otomatis permanen.
class DbDataStore(private val db: ChatDb) : PreferenceDataStore() {
    override fun getBoolean(key: String, def: Boolean) =
        db.get(key)?.let { it == "1" || it == "true" } ?: def
    override fun putBoolean(key: String, value: Boolean) {
        db.set(key, if (value) "1" else "0")
    }
    override fun getFloat(key: String, def: Float) =
        db.get(key)?.toFloatOrNull() ?: def
    override fun putFloat(key: String, value: Float) {
        db.set(key, value.toString())
    }
    override fun getInt(key: String, def: Int) = db.getInt(key, def)
    override fun putInt(key: String, value: Int) {
        db.set(key, value.toString())
    }
    override fun getLong(key: String, def: Long) =
        db.get(key)?.toLongOrNull() ?: def
    override fun putLong(key: String, value: Long) {
        db.set(key, value.toString())
    }
    override fun getString(key: String, def: String?) = db.get(key, def)
    override fun putString(key: String, value: String?) {
        db.set(key, value ?: "")
    }
    override fun getStringSet(key: String, def: Set<String>?): Set<String>? = def
    override fun putStringSet(key: String, values: Set<String>?) {}
}
