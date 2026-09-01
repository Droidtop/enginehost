package dev.enginehost

import android.content.Context
import org.json.JSONArray
import java.io.File

/** A small path-only library for people who launch Enginehost directly. */
class GameLibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun games(): List<File> = GameLibraryCodec.decode(preferences.getString(KEY_PATHS, null))
        .map(::File)

    fun remember(folder: File) {
        val path = folder.canonicalPath
        val paths = listOf(path) + games().map { it.path }.filterNot { it == path }
        preferences.edit().putString(KEY_PATHS, GameLibraryCodec.encode(paths.take(MAX_GAMES))).apply()
    }

    fun forget(folder: File) {
        val path = runCatching { folder.canonicalPath }.getOrDefault(folder.absolutePath)
        val paths = games().map { it.path }.filterNot { current ->
            runCatching { File(current).canonicalPath }.getOrDefault(current) == path
        }
        preferences.edit().putString(KEY_PATHS, GameLibraryCodec.encode(paths)).apply()
    }

    companion object {
        private const val PREFERENCES = "game-library-v1"
        private const val KEY_PATHS = "paths"
        private const val MAX_GAMES = 200
    }
}

internal object GameLibraryCodec {
    fun decode(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf(String::isNotEmpty)
        }.distinct()
    }.getOrDefault(emptyList())

    fun encode(paths: List<String>): String = JSONArray(paths.distinct()).toString()
}
