package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Where a game's config comes from, and the one place every reader asks.
 *
 * The owner, 2026-10-23: "Unless explicitly tagged to be added to the game
 * folder, it should go in enginehost's data. Users should be allowed to save
 * it there later, but the point is, the creation UI for configs is a HELPER";
 * and "Import them and leave them in place. The game folder one takes
 * precedence, and saves made in the UI will not overwrite the one in the game
 * folder unless the user explicitly flags to do so, and with a warning".
 *
 * So a game's config is, in order:
 * 1. the game folder's own `enginehost.json`, when there is one (its author's,
 *    never changed by Enginehost unless the person explicitly saves there);
 * 2. else the config the person made in Game setup, kept in Enginehost's own
 *    storage ([GameConfigStore]);
 * 3. else whatever the launch was handed or detection supplies (the callers'
 *    business, as before).
 * The person's per-game overrides still sit above all of these
 * ([EngineConfigReader.resolve]).
 *
 * A folder's file is also copied into Enginehost's storage the first time it
 * is read and nothing is stored for that game yet, and is left in place, so
 * the person's game keeps its config in Enginehost even if the file later goes.
 */
object GameConfigs {
    enum class Source { GAME_FOLDER, ENGINEHOST }

    /** A game's config document and where it came from. */
    class Document(val json: JSONObject, val text: String, val source: Source)

    /** The config that applies to [gameFolder] (see the class comment), or null when it has none of its own. */
    fun document(context: Context, gameFolder: File): Document? {
        val store = GameConfigStore.of(context)
        val folderFile = File(gameFolder, CONFIG_FILE_NAME)
        if (folderFile.isFile) {
            val text = runCatching { folderFile.readText() }.getOrNull()
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (text != null && json != null) {
                if (store.get(gameFolder) == null) runCatching { store.set(gameFolder, json) }
                return Document(json, text, Source.GAME_FOLDER)
            }
            // An unreadable folder file still wins: the resolver reports it, rather than
            // a stored config silently standing in for a file the person can see.
            return text?.let { Document(JSONObject(), it, Source.GAME_FOLDER) }
        }
        return store.get(gameFolder)?.let { Document(it, it.toString(2), Source.ENGINEHOST) }
    }

    /** The stored config for [EngineConfigReader.resolve], which reads the folder's own file itself. */
    fun storedJson(context: Context, gameFolder: File): String? =
        runCatching { GameConfigStore.of(context).get(gameFolder)?.toString() }.getOrNull()

    /** [EngineConfigReader.resolve] with this game's stored config supplied. */
    fun resolve(
        context: Context,
        gameFolder: File,
        inlineJson: String?,
        testingJson: String? = null,
        overridesJson: String? = null,
    ): EngineConfig {
        // Reading the document first imports a folder file that was never stored.
        document(context, gameFolder)
        return EngineConfigReader.resolve(gameFolder, inlineJson, testingJson, overridesJson, storedJson(context, gameFolder))
    }
}

/**
 * The configs people made in Game setup, in Enginehost's own storage, one
 * file per game keyed by its folder's canonical path ([TestingConfigStore]'s
 * layout). Never written into the game folder.
 */
class GameConfigStore(private val directory: File) {
    fun get(gameFolder: File): JSONObject? {
        val stored = runCatching { JSONObject(file(gameFolder).readText()) }.getOrNull() ?: return null
        if (stored.optString(PATH) != key(gameFolder)) return null
        return stored.optJSONObject(CONFIG)
    }

    /** Stores [config] for [gameFolder] after validating it, so a stored config is always one Enginehost can read. */
    fun set(gameFolder: File, config: JSONObject) {
        EngineConfigReader.parseDocument(config.toString())
        directory.mkdirs()
        val stored = JSONObject().put(PATH, key(gameFolder)).put(CONFIG, JSONObject(config.toString()))
        val target = file(gameFolder)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(stored.toString())
        check(temporary.renameTo(target)) { "Could not store the game's configuration" }
    }

    private fun key(gameFolder: File): String = runCatching { gameFolder.canonicalPath }.getOrDefault(gameFolder.absolutePath)

    private fun file(gameFolder: File): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key(gameFolder).toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    companion object {
        private const val PATH = "path"
        private const val CONFIG = "config"

        fun of(context: Context): GameConfigStore = GameConfigStore(File(context.filesDir, "game-configs"))
    }
}
