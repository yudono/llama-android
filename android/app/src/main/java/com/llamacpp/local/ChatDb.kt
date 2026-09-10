package com.llamacpp.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// SQLite minimal: tiap New Chat = conversation baru = session +
// context window baru. Semua pesan tersimpan & bisa dibuka lagi.
class ChatDb(ctx: Context) : SQLiteOpenHelper(ctx, "llama.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                model TEXT NOT NULL, title TEXT,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conv_id INTEGER NOT NULL, role TEXT NOT NULL,
                text TEXT NOT NULL, created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conv_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun newConversation(model: String): Long {
        val cv = ContentValues().apply {
            put("model", model)
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

    fun lastConversation(): Pair<Long, String>? {
        readableDatabase.rawQuery(
            "SELECT id, model FROM conversations ORDER BY id DESC LIMIT 1", null
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
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
