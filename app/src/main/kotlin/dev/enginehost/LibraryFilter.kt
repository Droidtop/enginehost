package dev.enginehost

/** How the library is ordered; every order is served by an index. */
enum class SortOrder { NAME, RECENTLY_ADDED, RECENTLY_PLAYED, SIZE }

/** Which rows a list draws from: the games on Home, or everything the scanner found under a folder. */
enum class LibraryScope { ADDED, UNDER_ROOT }

/**
 * One game as the library lists it: only stored facts, so drawing a row
 * never touches the disk. What this device can do with it ([Support]) is
 * decided per distinct engine line, not per game, by [SupportResolver].
 */
data class GameRow(
    val path: String,
    val name: String,
    val kind: FindingKind,
    val engine: String?,
    val engineContext: String?,
    val engineVersion: String?,
    /** "key=value;key=value", as stored; parsed only by whoever resolves a plugin. */
    val requirements: String,
    val hosted: Boolean,
    val platforms: Int,
    val confidence: Confidence,
    val launch: String?,
    val architecture: String?,
    val dotNet: Boolean,
    /** Bytes on disk once measured, else -1. */
    val sizeBytes: Long,
    val added: Boolean,
    val addedAt: Long,
    val playedAt: Long,
    val classified: Boolean,
    /** Marked by the person; the Favourites shelf and filter list these. */
    val favourite: Boolean = false,
)

data class LibraryFilter(
    val scope: LibraryScope = LibraryScope.ADDED,
    /** The scanned folder, for [LibraryScope.UNDER_ROOT]. */
    val root: String? = null,
    /** An engine id, or [NO_ENGINE] for builds with no recognised engine. */
    val engine: String? = null,
    val platform: BuildPlatform? = null,
    /** Applied after the query, per engine line, never per game. */
    val support: Support? = null,
    /** The exact parent folder of the game. */
    val folder: String? = null,
    val text: String = "",
    val sort: SortOrder = SortOrder.NAME,
    /** Only the games the person marked as favourites. */
    val favourites: Boolean = false,
) {
    /** True when nothing narrows the list. */
    val unfiltered: Boolean
        get() = engine == null && platform == null && support == null && folder == null && text.isBlank() && !favourites

    companion object {
        const val NO_ENGINE = "\u0000none"
    }
}

/**
 * The library's filters and sorts as SQL, kept free of Android so it can
 * be checked on the JVM. Every predicate is a column the schema indexes or
 * a range over the primary key; the text search is the one scan, and it
 * runs over a lowercased name column in memory-sized tables.
 */
object LibraryQuery {
    class Sql(val where: String, val args: List<String>, val orderBy: String)

    fun build(filter: LibraryFilter): Sql {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        when (filter.scope) {
            LibraryScope.ADDED -> clauses += "added = 1"
            LibraryScope.UNDER_ROOT -> {
                val root = filter.root
                if (root == null) {
                    clauses += "0"
                } else {
                    clauses += "(path = ? OR (path >= ? AND path < ?))"
                    args += root
                    args += root.trimEnd('/') + "/"
                    args += root.trimEnd('/') + "0"
                }
            }
        }
        filter.engine?.let {
            if (it == LibraryFilter.NO_ENGINE) {
                clauses += "engine IS NULL"
            } else {
                clauses += "engine = ?"
                args += it
            }
        }
        filter.platform?.let {
            clauses += "(platforms & ?) != 0"
            args += it.bit.toString()
        }
        filter.folder?.let {
            clauses += "parent = ?"
            args += it
        }
        if (filter.favourites) clauses += "favourite = 1"
        for (word in filter.text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            clauses += "name_key LIKE ? ESCAPE '\\'"
            args += "%" + escapeLike(word) + "%"
        }
        val order = when (filter.sort) {
            SortOrder.NAME -> "name_key ASC, path ASC"
            SortOrder.RECENTLY_ADDED -> "added_at DESC, name_key ASC, path ASC"
            SortOrder.RECENTLY_PLAYED -> "played_at DESC, name_key ASC, path ASC"
            SortOrder.SIZE -> "size_bytes DESC, name_key ASC, path ASC"
        }
        return Sql(clauses.joinToString(" AND "), args, order)
    }

    fun escapeLike(word: String): String = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
