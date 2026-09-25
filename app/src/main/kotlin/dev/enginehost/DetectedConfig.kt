package dev.enginehost

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * The config a game folder's own files imply, for a folder that has no
 * `enginehost.json`.
 *
 * Detection reads the engine, its version, the entry file and the game's
 * title and save name from the folder's own files. When all of that is
 * there, nothing remains for a person to type, and the launch runs on that
 * document in memory, as it would on a caller's inline config. It is never
 * written into the game folder: `enginehost.json` is the person's own
 * authoritative config, and only Game setup (ConfigEditorActivity) writes
 * it, when the person saves there (the user, 2026-09-25). The editor
 * prefills from [documentFor] too, so what a launch runs on and what the
 * editor would save cannot disagree. Detection runs again on each launch.
 */
object DetectedConfig {
    private const val TAG = "enginehost"

    /** What a folder with no config of its own amounts to at launch. */
    sealed class Launch {
        /** The config the launch runs on, as an inline document. */
        class Document(val json: String) : Launch()

        /** An engine Enginehost recognised and has nothing to run with; Play says so ([UnhostedEngine]). */
        class Unhosted(val detection: EngineDetection) : Launch()

        /** Detection left a required field open; the launch goes to Game setup. */
        object Open : Launch()
    }

    /**
     * The config a launch of [gameFolder] runs on when the folder has no
     * config of its own: the detected document, with the caller's
     * [inlineJson] filling what it leaves open (the same precedence a folder
     * config has over an inline one). A caller that names the engine itself
     * is believed over an unhosted detection, as it is over any other.
     */
    fun forLaunch(context: Context, gameFolder: File, inlineJson: String?): Launch {
        val detection = runCatching { EngineDetector.detect(EngineRegistryStore.rows(context), gameFolder) }
            .onFailure { Log.w(TAG, "Detection failed for ${gameFolder.name}", it) }
            .getOrNull() ?: return Launch.Open
        return launchFor(detection, gameFolder.name, inlineJson)
    }

    /** [forLaunch] after detection; split out so it is testable without a Context. */
    internal fun launchFor(detection: EngineDetection, folderName: String, inlineJson: String?): Launch {
        val callerNamesEngine = inlineJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optString("engine")?.isNotBlank() == true
        if (!detection.hosted && !callerNamesEngine) return Launch.Unhosted(detection)
        return launchDocument(detection, folderName, inlineJson)?.let(Launch::Document) ?: Launch.Open
    }

    /** The launch document for [detection], or null when a required field is still open. */
    internal fun launchDocument(detection: EngineDetection, folderName: String, inlineJson: String?): String? {
        val document = documentFor(detection, folderName, inlineJson) ?: return null
        val inline = inlineJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        return EngineConfigReader.mergeAuthoritative(document, inline).toString()
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
     * launch it came with ([launchDocument]), as it does for a folder that
     * has a config.
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
