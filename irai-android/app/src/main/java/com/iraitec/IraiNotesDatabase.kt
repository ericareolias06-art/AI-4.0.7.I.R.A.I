package com.iraitec

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class IraiNote(
    val id: Long,
    val title: String,
    val content: String,
    val updatedAt: Long
)

class IraiNotesDatabase(context: Context) :
    SQLiteOpenHelper(context, "irai_notes.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema changes will be handled here without deleting existing notes.
    }

    fun saveNote(id: Long?, title: String, content: String): Long {
        val values = ContentValues().apply {
            put("title", title.trim().ifBlank { "Untitled note" })
            put("content", content)
            put("updated_at", System.currentTimeMillis())
        }

        return if (id == null) {
            writableDatabase.insertOrThrow("notes", null, values)
        } else {
            val changed = writableDatabase.update(
                "notes", values, "id = ?", arrayOf(id.toString())
            )
            if (changed == 0) {
                writableDatabase.insertOrThrow("notes", null, values)
            } else {
                id
            }
        }
    }

    fun findNote(title: String): IraiNote? {
        val cursor = readableDatabase.query(
            "notes",
            arrayOf("id", "title", "content", "updated_at"),
            "LOWER(title) = LOWER(?)",
            arrayOf(title.trim()),
            null, null, "updated_at DESC", "1"
        )
        return cursor.use {
            if (!it.moveToFirst()) null else noteFromCursor(it)
        }
    }

    fun getNotes(): List<IraiNote> {
        val cursor = readableDatabase.query(
            "notes",
            arrayOf("id", "title", "content", "updated_at"),
            null, null, null, null, "updated_at DESC"
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(noteFromCursor(it))
            }
        }
    }

    fun deleteNote(id: Long): Boolean {
        return writableDatabase.delete(
            "notes", "id = ?", arrayOf(id.toString())
        ) > 0
    }

    private fun noteFromCursor(cursor: android.database.Cursor) = IraiNote(
        id = cursor.getLong(0),
        title = cursor.getString(1),
        content = cursor.getString(2),
        updatedAt = cursor.getLong(3)
    )
}
