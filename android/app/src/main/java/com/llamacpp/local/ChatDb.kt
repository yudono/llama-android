package com.llamacpp.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Satu-satunya penyimpanan: SQLite (conversations, session, context,
// kv settings termasuk hf_token). Tidak ada SharedPreferences.
class ChatDb(ctx: Context) : SQLiteOpenHelper(ctx, "llama.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                model TEXT NOT NULL, quant TEXT, ctx_window INTEGER,
                title TEXT, created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conv_id INTEGER NOT NULL, role TEXT NOT NULL,
                text TEXT NOT NULL, created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conv_id, id)")
        db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS kv(key TEXT PRIMARY KEY, value TEXT)")
            runCatching { db.execSQL("ALTER TABLE conversations ADD COLUMN quant TEXT") }
            runCatching { db.execSQL("ALTER TABLE conversations ADD COLUMN ctx_window INTEGER") }
        }
    }

    // ---------- key-value ----------
    fun get(key: String, def: String? = null): String? {
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else def
        }
    }

    fun set(key: String, value: String) {
        val cv = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getInt(key: String, def: Int): Int = get(key)?.toIntOrNull() ?: def

    // ---------- migrasi sekali dari SharedPreferences lama ----------
    fun migratePrefsOnce(ctx: Context) {
        if (get("migrated") == "1") return
        // prefs default (layar Settings) + prefs lama "llama_prefs".
        val stores = listOf(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx),
            ctx.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        )
        val w = writableDatabase
        w.beginTransaction()
        try {
            for (sp in stores) {
                for ((k, v) in sp.all) {
                    if (k == "migrated") continue
                    val cv = ContentValues().apply {
                        put("key", k); put("value", v.toString())
                    }
                    w.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            val cv = ContentValues().apply { put("key", "migrated"); put("value", "1") }
            w.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // ---------- conversations = session + context window ----------
    fun newConversation(model: String, quant: String, ctxWindow: Int): Long {
        val cv = ContentValues().apply {
            put("model", model); put("quant", quant); put("ctx_window", ctxWindow)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("conversations", null, cv)
    }

    fun addMessage(convId: Long, role: String, text: String) {
        val cv = ContentValues().apply {
            put("conv_id", convId); put("role", role)
            put("text", text); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("messages", null, cv)
    }

    fun rename(convId: Long, title: String) {
        val cv = ContentValues().apply { put("title", title) }
        writableDatabase.update("conversations", cv, "id=?", arrayOf("$convId"))
    }

    fun deleteLastMessage(convId: Long, role: String) {
        writableDatabase.execSQL(
            "DELETE FROM messages WHERE id=(SELECT MAX(id) FROM messages WHERE conv_id=? AND role=?)",
            arrayOf("$convId", role)
        )
    }

    data class Conv(
        val id: Long, val title: String?, val model: String,
        val quant: String, val createdAt: Long
    )

    // Daftar conversation terbaru dulu (untuk drawer).
    fun listConversations(): List<Conv> {
        val out = mutableListOf<Conv>()
        readableDatabase.rawQuery(
            "SELECT id, title, model, IFNULL(quant,''), created_at FROM conversations ORDER BY id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += Conv(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
            }
        }
        return out
    }

    // Hapus permanen: pesan + conversation.
    fun deleteConversation(convId: Long) {
        writableDatabase.delete("messages", "conv_id=?", arrayOf("$convId"))
        writableDatabase.delete("conversations", "id=?", arrayOf("$convId"))
    }

    fun lastConversation(): Triple<Long, String, String>? {
        readableDatabase.rawQuery(
            "SELECT id, model, IFNULL(quant,'') FROM conversations ORDER BY id DESC LIMIT 1", null
        ).use { c ->
            return if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.getString(2)) else null
        }
    }

    // (role, text) terurut untuk prompt + tampilan.
    fun messages(convId: Long): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT role, text FROM messages WHERE conv_id=? ORDER BY id ASC",
            arrayOf("$convId")
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        }
        return out
    }
}
