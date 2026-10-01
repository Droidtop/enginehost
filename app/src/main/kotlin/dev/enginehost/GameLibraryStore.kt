package dev.enginehost

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File

/**
 * The library's database: every game Enginehost knows, whether the person
 * added it or a scan found it, with the facts filtering and sorting need
 * as indexed columns, plus what a scan learned about each folder so the
 * next one only revisits what changed.
 *
 * It replaces the old path-only list (capped at 200 games, in preferences),
 * which cannot serve a library of thousands. That list is imported once,
 * on creation, in its order.
 */
internal class LibraryDb private constructor(private val appContext: Context) :
    SQLiteOpenHelper(appContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Readers (the list) must not wait for a scan's batches to commit.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE games (
                path TEXT PRIMARY KEY NOT NULL,
                parent TEXT NOT NULL,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL,
                kind INTEGER NOT NULL DEFAULT 0,
                engine TEXT,
                engine_context TEXT,
                version TEXT,
                requirements TEXT NOT NULL DEFAULT '',
                hosted INTEGER NOT NULL DEFAULT 0,
                platforms INTEGER NOT NULL DEFAULT 0,
                confidence INTEGER NOT NULL DEFAULT 1,
                launch TEXT,
                arch TEXT,
                dotnet INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT -1,
                added INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL DEFAULT 0,
                played_at INTEGER NOT NULL DEFAULT 0,
                found_at INTEGER NOT NULL DEFAULT 0,
                classified INTEGER NOT NULL DEFAULT 0,
                sig INTEGER,
                seen_run INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX games_added_name ON games (added, name_key)")
        db.execSQL("CREATE INDEX games_engine ON games (engine)")
        db.execSQL("CREATE INDEX games_parent ON games (parent)")
        db.execSQL("CREATE INDEX games_added_at ON games (added_at)")
        db.execSQL("CREATE INDEX games_played_at ON games (played_at)")
        db.execSQL("CREATE INDEX games_size ON games (size_bytes)")
        db.execSQL("CREATE INDEX games_unclassified ON games (classified)")
        // Folders a scan looked at and found not to be games, so they are not analysed again while unchanged.
        db.execSQL("CREATE TABLE scan_negatives (path TEXT PRIMARY KEY NOT NULL, sig INTEGER NOT NULL, seen_run INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE scan_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, root TEXT NOT NULL, started INTEGER NOT NULL, finished INTEGER, complete INTEGER NOT NULL DEFAULT 0)")
        importLegacyList(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The earlier library was a JSON list of paths, newest first, in preferences. */
    private fun importLegacyList(db: SQLiteDatabase) {
        val preferences = appContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        val paths = GameLibraryCodec.decode(preferences.getString(LEGACY_KEY, null))
        val now = System.currentTimeMillis()
        paths.forEachIndexed { index, path ->
            val file = File(path)
            val values = ContentValues().apply {
                put("path", path)
                put("parent", file.parent.orEmpty())
                put("name", file.name.ifBlank { path })
                put("name_key", file.name.ifBlank { path }.lowercase())
                put("added", 1)
                put("added_at", now - index)
                put("played_at", now - index)
                put("classified", 0)
            }
            db.insertWithOnConflict("games", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
        if (paths.isNotEmpty()) preferences.edit().remove(LEGACY_KEY).apply()
    }

    companion object {
        private const val NAME = "game-library.db"
        private const val VERSION = 1
        private const val LEGACY_PREFERENCES = "game-library-v1"
        private const val LEGACY_KEY = "paths"

        @Volatile
        private var instance: LibraryDb? = null

        fun get(context: Context): LibraryDb = instance ?: synchronized(this) {
            instance ?: LibraryDb(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * The library: games a person added (Home) and games a scan found, in one
 * table, queried by indexed filters off the main thread. Also the scanner's
 * [ScanStore], so a scan's results land where the list reads them.
 *
 * Every method touches the disk: call from a worker thread.
 */
class GameLibraryStore(context: Context) : ScanStore {
    private val helper = LibraryDb.get(context)

    // ---- Home's library ---------------------------------------------------------

    /**
     * Adds [folder] to Home. A folder a scan already described keeps what the
     * scan learned: the row is found under the path as given or as resolved,
     * and only a folder neither names gets a new one.
     */
    fun remember(folder: File) {
        val canonical = runCatching { folder.canonicalPath }.getOrDefault(folder.absolutePath)
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val known = linkedSetOf(folder.path, canonical).map { markAdded(db, it, now) }.any { it }
            if (!known) insertBare(db, canonical, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Adds every one of [paths] (as the scan stored them) to Home in one transaction. */
    fun addPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            paths.forEach { path -> if (!markAdded(db, path, now)) insertBare(db, path, now) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun markAdded(db: SQLiteDatabase, path: String, now: Long): Boolean =
        db.compileStatement("UPDATE games SET added_at = CASE WHEN added = 0 THEN ? ELSE added_at END, added = 1 WHERE path = ?").use { statement ->
            statement.bindLong(1, now)
            statement.bindString(2, path)
            statement.executeUpdateDelete() > 0
        }

    /** A game really started: it is in the library and is the most recently played. */
    fun played(folder: File) {
        val canonical = runCatching { folder.canonicalPath }.getOrDefault(folder.absolutePath)
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val known = linkedSetOf(folder.path, canonical).map { path ->
                val marked = markAdded(db, path, now)
                if (marked) db.execSQL("UPDATE games SET played_at = ? WHERE path = ?", arrayOf<Any>(now, path))
                marked
            }.any { it }
            if (!known) {
                insertBare(db, canonical, now)
                db.execSQL("UPDATE games SET played_at = ? WHERE path = ?", arrayOf<Any>(now, canonical))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Takes [folder] off Home. What a scan learned about it stays; a game only ever added by hand is forgotten. */
    fun forget(folder: File) {
        val canonical = runCatching { folder.canonicalPath }.getOrDefault(folder.absolutePath)
        val db = helper.writableDatabase
        for (path in setOf(canonical, folder.path)) {
            db.execSQL("UPDATE games SET added = 0 WHERE path = ?", arrayOf<Any>(path))
            db.execSQL("DELETE FROM games WHERE path = ? AND sig IS NULL", arrayOf<Any>(path))
        }
    }

    private fun insertBare(db: SQLiteDatabase, path: String, now: Long) {
        val file = File(path)
        val name = file.name.ifBlank { path }
        val values = ContentValues().apply {
            put("path", path)
            put("parent", file.parent.orEmpty())
            put("name", name)
            put("name_key", name.lowercase())
            put("added", 1)
            put("added_at", now)
            put("classified", 0)
        }
        db.insertWithOnConflict("games", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ---- Listing ----------------------------------------------------------------

    fun query(filter: LibraryFilter): List<GameRow> {
        val sql = LibraryQuery.build(filter)
        val result = ArrayList<GameRow>()
        helper.readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM games WHERE ${sql.where} ORDER BY ${sql.orderBy}",
            sql.args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += row(cursor)
        }
        return result
    }

    /** How many games the scope holds, before any filter. */
    fun count(scope: LibraryScope, root: String?): Int {
        val sql = LibraryQuery.build(LibraryFilter(scope = scope, root = root))
        return helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM games WHERE ${sql.where}", sql.args.toTypedArray())
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Engines present in the scope with their counts; a null engine is the builds with none recognised. */
    fun engineCounts(scope: LibraryScope, root: String?): List<Pair<String?, Int>> {
        val sql = LibraryQuery.build(LibraryFilter(scope = scope, root = root))
        val result = ArrayList<Pair<String?, Int>>()
        helper.readableDatabase.rawQuery(
            "SELECT engine, COUNT(*) FROM games WHERE ${sql.where} GROUP BY engine ORDER BY COUNT(*) DESC",
            sql.args.toTypedArray(),
        ).use { while (it.moveToNext()) result += (if (it.isNull(0)) null else it.getString(0)) to it.getInt(1) }
        return result
    }

    /** The busiest parent folders in the scope, for the folder filter. */
    fun folderCounts(scope: LibraryScope, root: String?, limit: Int = FOLDER_CHOICES): List<Pair<String, Int>> {
        val sql = LibraryQuery.build(LibraryFilter(scope = scope, root = root))
        val result = ArrayList<Pair<String, Int>>()
        helper.readableDatabase.rawQuery(
            "SELECT parent, COUNT(*) FROM games WHERE ${sql.where} GROUP BY parent ORDER BY COUNT(*) DESC, parent ASC LIMIT $limit",
            sql.args.toTypedArray(),
        ).use { while (it.moveToNext()) result += it.getString(0) to it.getInt(1) }
        return result
    }

    fun lastScanRoot(): String? = helper.readableDatabase
        .rawQuery("SELECT root FROM scan_runs ORDER BY id DESC LIMIT 1", null)
        .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun row(cursor: Cursor): GameRow = GameRow(
        path = cursor.getString(0),
        name = cursor.getString(1),
        kind = if (cursor.getInt(2) == 1) FindingKind.ARCHIVE else FindingKind.FOLDER,
        engine = if (cursor.isNull(3)) null else cursor.getString(3),
        engineContext = if (cursor.isNull(4)) null else cursor.getString(4),
        engineVersion = if (cursor.isNull(5)) null else cursor.getString(5),
        requirements = cursor.getString(6),
        hosted = cursor.getInt(7) == 1,
        platforms = cursor.getInt(8),
        confidence = Confidence.ofRank(cursor.getInt(9)),
        launch = if (cursor.isNull(10)) null else cursor.getString(10),
        architecture = if (cursor.isNull(11)) null else cursor.getString(11),
        dotNet = cursor.getInt(12) == 1,
        sizeBytes = cursor.getLong(13),
        added = cursor.getInt(14) == 1,
        addedAt = cursor.getLong(15),
        playedAt = cursor.getLong(16),
        classified = cursor.getInt(17) == 1,
    )

    // ---- Facts for games added by hand and sizes ---------------------------------

    /** Paths added without detection (a picked folder, a launch from another app) still waiting to be classified. */
    fun unclassified(limit: Int): List<String> = helper.readableDatabase
        .rawQuery("SELECT path FROM games WHERE classified = 0 LIMIT ?", arrayOf(limit.toString()))
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** Stores what is now known about a game that was added bare; [finding] null means nothing recognised it. */
    fun classify(path: String, finding: GameFinding?) {
        val db = helper.writableDatabase
        if (finding == null) {
            val values = ContentValues().apply {
                put("classified", 1)
                put("platforms", BuildPlatform.UNKNOWN.bit)
            }
            db.update("games", values, "path = ?", arrayOf(path))
        } else {
            db.beginTransaction()
            try {
                writeFinding(db, finding.copy(path = path), seenRun = 0, resetSize = false)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Paths whose size has not been measured, optionally only those under [root]. */
    fun unmeasured(root: String?, limit: Int): List<String> {
        val args = ArrayList<String>()
        var where = "size_bytes < 0"
        if (root != null) {
            where += " AND (path = ? OR (path >= ? AND path < ?))"
            args += root
            args += root.trimEnd('/') + "/"
            args += root.trimEnd('/') + "0"
        }
        args += limit.toString()
        return helper.readableDatabase.rawQuery("SELECT path FROM games WHERE $where LIMIT ?", args.toTypedArray())
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    fun setSize(path: String, bytes: Long) {
        helper.writableDatabase.execSQL("UPDATE games SET size_bytes = ? WHERE path = ?", arrayOf<Any>(bytes, path))
    }

    // ---- ScanStore --------------------------------------------------------------

    override fun known(root: String): ScanStore.Known {
        val args = arrayOf(root, root.trimEnd('/') + "/", root.trimEnd('/') + "0")
        val range = "(path = ? OR (path >= ? AND path < ?))"
        val games = HashMap<String, Long>()
        val negatives = HashMap<String, Long>()
        val db = helper.readableDatabase
        db.rawQuery("SELECT path, sig FROM games WHERE sig IS NOT NULL AND $range", args).use {
            while (it.moveToNext()) games[it.getString(0)] = it.getLong(1)
        }
        db.rawQuery("SELECT path, sig FROM scan_negatives WHERE $range", args).use {
            while (it.moveToNext()) negatives[it.getString(0)] = it.getLong(1)
        }
        return ScanStore.Known(games, negatives)
    }

    override fun beginRun(root: String): Long {
        val values = ContentValues().apply {
            put("root", root)
            put("started", System.currentTimeMillis())
        }
        return helper.writableDatabase.insert("scan_runs", null, values)
    }

    override fun save(runId: Long, findings: List<GameFinding>, unchanged: List<String>, negatives: List<Pair<String, Long>>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            findings.forEach { finding ->
                writeFinding(db, finding, runId, resetSize = true)
                // A game root is one game: anything the library holds inside it that was only ever found is a duplicate.
                if (finding.kind == FindingKind.FOLDER) {
                    val prefix = finding.path.trimEnd('/')
                    db.execSQL("DELETE FROM games WHERE added = 0 AND path >= ? AND path < ?", arrayOf<Any>("$prefix/", "$prefix" + "0"))
                }
            }
            unchanged.chunked(SQL_CHUNK).forEach { chunk ->
                val args = ArrayList<Any>().apply { add(runId); addAll(chunk) }
                db.execSQL("UPDATE games SET seen_run = ? WHERE path IN (${chunk.joinToString(",") { "?" }})", args.toTypedArray())
            }
            negatives.forEach { (path, signature) ->
                db.execSQL("INSERT OR REPLACE INTO scan_negatives (path, sig, seen_run) VALUES (?, ?, ?)", arrayOf<Any>(path, signature, runId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun remove(paths: List<String>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            paths.chunked(SQL_CHUNK).forEach { chunk ->
                db.execSQL("DELETE FROM games WHERE path IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray<Any>())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun finishRun(runId: Long, root: String, complete: Boolean) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE scan_runs SET finished = ?, complete = ? WHERE id = ?",
                arrayOf<Any>(System.currentTimeMillis(), if (complete) 1 else 0, runId),
            )
            if (complete) {
                // What a whole walk no longer finds under the root is gone. Games a person added stay on Home.
                val args = arrayOf<Any>(root, root.trimEnd('/') + "/", root.trimEnd('/') + "0", runId)
                db.execSQL("DELETE FROM games WHERE added = 0 AND sig IS NOT NULL AND (path = ? OR (path >= ? AND path < ?)) AND seen_run <> ?", args)
                db.execSQL("DELETE FROM scan_negatives WHERE (path = ? OR (path >= ? AND path < ?)) AND seen_run <> ?", args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- Writing ----------------------------------------------------------------

    /**
     * Writes one finding over its row, keeping what only the person decided:
     * whether it is on Home, when it was added and when it was played.
     */
    private fun writeFinding(db: SQLiteDatabase, finding: GameFinding, seenRun: Long, resetSize: Boolean) {
        val file = File(finding.path)
        val values = ContentValues().apply {
            put("parent", file.parent.orEmpty())
            put("name", finding.name)
            put("name_key", finding.name.lowercase())
            put("kind", if (finding.kind == FindingKind.ARCHIVE) 1 else 0)
            put("engine", finding.engine)
            put("engine_context", finding.engineContext)
            put("version", finding.engineVersion)
            put("requirements", finding.requirements.entries.joinToString(";") { "${it.key}=${it.value}" })
            put("hosted", if (finding.hosted) 1 else 0)
            put("platforms", finding.platforms)
            put("confidence", finding.confidence.rank)
            put("launch", finding.launch)
            put("arch", finding.architecture)
            put("dotnet", if (finding.dotNet) 1 else 0)
            put("classified", 1)
            put("sig", finding.signature)
            put("seen_run", seenRun)
            if (resetSize) put("size_bytes", -1)
        }
        if (db.update("games", values, "path = ?", arrayOf(finding.path)) == 0) {
            values.put("path", finding.path)
            values.put("found_at", System.currentTimeMillis())
            db.insertWithOnConflict("games", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    companion object {
        const val FOLDER_CHOICES = 200
        private const val SQL_CHUNK = 400
        private const val COLUMNS =
            "path, name, kind, engine, engine_context, version, requirements, hosted, platforms, confidence, " +
                "launch, arch, dotnet, size_bytes, added, added_at, played_at, classified"
    }
}

/** The earlier path-only library, read once when the database is first created. */
internal object GameLibraryCodec {
    fun decode(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf(String::isNotEmpty)
        }.distinct()
    }.getOrDefault(emptyList())
}
