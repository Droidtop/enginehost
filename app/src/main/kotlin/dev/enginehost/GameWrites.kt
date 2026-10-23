package dev.enginehost

import org.json.JSONArray

/**
 * A core's `gameWrites`: the game sub-folders it writes (docs/engine-bundle-format.md).
 * Each is a plain relative path inside the game folder; anything that could name a path
 * outside it is refused, and the bundle with it.
 */
object GameWrites {
    fun parse(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.onEach {
            require(isSubFolder(it)) { "gameWrites must name sub-folders of the game folder: $it" }
        }

    internal fun isSubFolder(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
