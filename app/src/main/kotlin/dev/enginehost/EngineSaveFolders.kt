package dev.enginehost

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * The folder each game's engine reported it saves in
 * ([dev.enginehost.api.EngineHost.reportSaveDirectory]): for an engine that
 * names its own folder inside the save root (Godot's `user://`, Ren'Py's save
 * directory), the one place that name is known.
 *
 * Written by the runtime process when the engine reports it and read by the
 * launch screen when the game ends, so it is a small file per game in
 * Enginehost's own storage rather than preferences, which are not shared
 * between processes. It survives a crash: an engine reports at start, before
 * the game can save.
 */
object EngineSaveFolders {
    private const val DIRECTORY = "engine-save-folders"

    /** Records [reported] for [gameFolder] when it is [saveRoot] or inside it; anything else is ignored. */
    fun record(context: Context, gameFolder: File, saveRoot: File, reported: File): Boolean {
        val folder = within(saveRoot, reported) ?: return false
        val file = fileFor(context, gameFolder)
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".partial")
        partial.writeText(folder.path)
        return partial.renameTo(file)
    }

    /** The folder recorded for [gameFolder], when it is still [saveRoot] or inside it. */
    fun recorded(context: Context, gameFolder: File, saveRoot: File): File? {
        val text = runCatching { fileFor(context, gameFolder).readText().trim() }.getOrNull() ?: return null
        if (text.isEmpty()) return null
        return within(saveRoot, File(text))
    }

    /** [candidate], canonical, when it is [root] or inside it; else null. */
    internal fun within(root: File, candidate: File): File? {
        if (!candidate.isAbsolute) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        return canonical.takeIf { it == canonicalRoot || it.path.startsWith(prefix) }
    }

    private fun fileFor(context: Context, gameFolder: File): File {
        val path = runCatching { gameFolder.canonicalPath }.getOrDefault(gameFolder.absolutePath)
        val id = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, DIRECTORY), id)
    }
}
