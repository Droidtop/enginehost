package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A game's testing configuration: what Game setup's Test ran it with, kept
 * apart from the game's working `enginehost.json` until the person decides.
 *
 * The user, 2026-09-25: Test "should save a TESTING configuration. That way
 * we don't risk nuking a working config if their test config doesn't work,
 * they can roll it back." So Test never writes into the game folder (only
 * Save does, see [FolderConfigFile]); it stores the editor's document here,
 * in Enginehost's own storage keyed by the game folder, and launches with it.
 * Afterwards the person keeps it ([keep]: it becomes the folder's
 * enginehost.json) or discards it ([discard]: the working config was never
 * touched and is what runs again). A new Test replaces the pending one.
 * Every other launch (Play, another app's LAUNCH) runs the working config;
 * only a launch Game setup's Test starts reads this.
 */
class TestingConfigStore(private val directory: File) {
    /** One pending testing configuration and when it was stored. */
    data class Pending(val config: JSONObject, val savedAt: Long)

    fun get(gameFolder: File): Pending? {
        val stored = runCatching { JSONObject(file(gameFolder).readText()) }.getOrNull() ?: return null
        if (stored.optString(PATH) != key(gameFolder)) return null
        val config = stored.optJSONObject(CONFIG) ?: return null
        return Pending(config, stored.optLong(SAVED_AT))
    }

    fun set(gameFolder: File, config: JSONObject, now: Long = System.currentTimeMillis()) {
        directory.mkdirs()
        val stored = JSONObject().put(PATH, key(gameFolder)).put(SAVED_AT, now).put(CONFIG, JSONObject(config.toString()))
        val target = file(gameFolder)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(stored.toString())
        check(temporary.renameTo(target)) { "Could not store the testing configuration" }
    }

    fun discard(gameFolder: File) {
        file(gameFolder).delete()
    }

    /**
     * Makes the pending testing configuration the game's working config: it
     * is written as the folder's enginehost.json (the person chose to keep
     * it, which is a save) and is then no longer pending.
     */
    fun keep(gameFolder: File) {
        val pending = checkNotNull(get(gameFolder)) { "No testing configuration is pending for this game" }
        FolderConfigFile.write(gameFolder, pending.config)
        discard(gameFolder)
    }

    private fun key(gameFolder: File): String = runCatching { gameFolder.canonicalPath }.getOrDefault(gameFolder.absolutePath)

    private fun file(gameFolder: File): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key(gameFolder).toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    companion object {
        private const val PATH = "path"
        private const val SAVED_AT = "savedAt"
        private const val CONFIG = "config"

        fun of(context: Context): TestingConfigStore = TestingConfigStore(File(context.filesDir, "testing-configs"))
    }
}

/**
 * The one writer of a game folder's `enginehost.json`, used only when the
 * person saves in Game setup (or keeps a tested configuration). The document
 * is validated first, so a folder never receives a config Enginehost itself
 * could not read.
 */
object FolderConfigFile {
    fun write(gameFolder: File, document: JSONObject) {
        EngineConfigReader.parseDocument(document.toString())
        val configFile = File(gameFolder, CONFIG_FILE_NAME)
        require(!configFile.exists() || configFile.isFile) { "$CONFIG_FILE_NAME is not a file" }
        configFile.writeText(document.toString(2) + "\n")
    }
}
