package dev.enginehost

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * The config a game folder writes for itself.
 *
 * Detection reads the engine, its version, the entry file and the game's
 * title and save name from the folder's own files. When all of that is
 * there, nothing remains for a person to type, so the config is written
 * and the launch continues; the editor is for the folders that leave a
 * question open. What is written is exactly what the editor would have
 * saved from the same detection, so the two paths cannot disagree.
 */
object DetectedConfig {
    private const val TAG = "enginehost"

    /** True when a complete `enginehost.json` was written into [gameFolder]. */
    fun write(context: Context, gameFolder: File, inlineJson: String?): Boolean {
        val detection = runCatching { EngineDetector.detect(EngineRegistryStore.rows(context), gameFolder) }
            .onFailure { Log.w(TAG, "Detection failed for ${gameFolder.name}", it) }
            .getOrNull() ?: return false
        val document = documentFor(detection, gameFolder.name, inlineJson) ?: return false
        return runCatching {
            File(gameFolder, CONFIG_FILE_NAME).writeText(document.toString(2) + "\n")
            true
        }.onFailure { Log.w(TAG, "Could not write $CONFIG_FILE_NAME into ${gameFolder.name}", it) }.getOrDefault(false)
    }

    /**
     * The document, or null when detection left a required field open.
     *
     * A caller's inline config supplies what identifies the game
     * ([CALLER_FACTS]), which is what a launcher knows better than a scan.
     * Nothing else of it is written: `dev.enginehost.LAUNCH` is open to
     * every app, and a written file outlives the launch, so a caller's
     * `options` (mkxp-z's `customScript` names a script to run) would
     * otherwise stay in the game's config for every later launch, whoever
     * starts it. The caller's full inline config still applies to the
     * launch it came with, as it does for a folder that has a config.
     */
    internal fun documentFor(detection: EngineDetection, folderName: String, inlineJson: String?): JSONObject? {
        val inline = inlineJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val document = JSONObject()
        CALLER_FACTS.forEach { key -> inline.opt(key)?.let { document.put(key, it) } }
        fun fill(key: String, value: String?) {
            if (!document.has(key) && value != null) document.put(key, value)
        }
        fill("engine", detection.engine)
        fill("engineContext", detection.engineContext)
        fill("engineVersion", detection.engineVersion)
        fill("execFile", detection.execFile)
        fill("title", detection.title)
        val engine = document.optString("engine")
        val engineContext = document.optString("engineContext").takeIf { it.isNotBlank() }
        fill("saveFolder", SaveFolders.defaultFor(engine, engineContext, detection.saveFolder, folderName))
        if (!document.has("runtimeRequirements") && detection.runtimeRequirements.isNotEmpty()) {
            document.put("runtimeRequirements", JSONObject(detection.runtimeRequirements))
        }
        return runCatching { EngineConfigReader.parseDocument(document.toString()); document }.getOrNull()
    }

    /** What droidtop's library sends, and all of a caller's config that is ever written down. */
    internal val CALLER_FACTS = listOf("engine", "engineContext", "engineVersion", "runtimeRequirements", "title")
}
