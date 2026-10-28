package dev.enginehost

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Lines already translated, per game (docs/text-services.md, "Translating"): a line read once is
 * never sent again, and a replay or a reload costs nothing. Keyed by the game, the line's id, a
 * digest of its text (an edited line is translated afresh), the target and the service. Enginehost's
 * own storage only; nothing goes into the game folder. Main process.
 */
class TranslationCache(context: Context) : SQLiteOpenHelper(context.applicationContext, "translations.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE translations (game TEXT NOT NULL, line TEXT NOT NULL, source TEXT NOT NULL, " +
                "target TEXT NOT NULL, service TEXT NOT NULL, text TEXT NOT NULL, " +
                "PRIMARY KEY (game, line, source, target, service))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun get(game: String, line: String, source: String, target: String, service: String): String? =
        readableDatabase.query(
            "translations", arrayOf("text"),
            "game = ? AND line = ? AND source = ? AND target = ? AND service = ?",
            arrayOf(game, line, digest(source), target, service), null, null, null,
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(game: String, line: String, source: String, target: String, service: String, text: String) {
        writableDatabase.insertWithOnConflict(
            "translations", null,
            ContentValues().apply {
                put("game", game)
                put("line", line)
                put("source", digest(source))
                put("target", target)
                put("service", service)
                put("text", text)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}
