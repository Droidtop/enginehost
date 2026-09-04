package dev.enginehost

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Where saves go. One shared root is the default for every engine, and an
 * engine family can be given its own root instead: someone may want Ren'Py
 * saves synced from one folder and RPG Maker saves on the card with the
 * games, without every other engine following. Whichever root applies, the
 * saves themselves live in a `saves` folder beneath it, so a folder chosen
 * for one purpose is never littered at its top level.
 */
class SaveLocationStore(context: Context) {
    private val preferences = context.getSharedPreferences("save-location-v1", Context.MODE_PRIVATE)

    fun defaultRoot(): File = File(Environment.getExternalStorageDirectory(), "Enginehost")

    /** The shared root every engine uses unless it has its own. */
    fun root(): File = preferences.getString(KEY_ROOT, null)?.let(::File) ?: defaultRoot()

    /** The shared save folder, created and checked writable. */
    fun saveRoot(): File = ensureUsable(File(root(), "saves"))

    /** An engine family's own root, when one was chosen. */
    fun overrideFor(engine: String): File? = preferences.getString(KEY_ENGINE_PREFIX + engine, null)?.let(::File)

    /** The root an engine family's saves go under: its own, else the shared one. */
    fun rootFor(engine: String): File = overrideFor(engine) ?: root()

    /** The save folder handed to an engine family's runtime, created and checked writable. */
    fun saveRootFor(engine: String): File = ensureUsable(File(rootFor(engine), "saves"))

    /**
     * The folder a game's runtime saves into: the engine's save root, or
     * the game's own folder beneath it when the config names one.
     */
    fun saveFolderFor(config: EngineConfig): File {
        val root = saveRootFor(config.engine)
        val name = config.saveFolder ?: return root
        require(SaveFolders.isPlainName(name)) { "A save folder must be a single folder name" }
        return ensureUsable(File(root, name))
    }

    /** Every engine family that has its own root, by engine id. */
    fun overrides(): Map<String, File> = preferences.all
        .filterKeys { it.startsWith(KEY_ENGINE_PREFIX) }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix(KEY_ENGINE_PREFIX) to File(it) } }
        .toMap()

    fun selectRoot(folder: File) {
        preferences.edit().putString(KEY_ROOT, usableCanonical(folder).path).apply()
    }

    fun selectRootFor(engine: String, folder: File) {
        require(engine.isNotBlank()) { "An engine id is required" }
        preferences.edit().putString(KEY_ENGINE_PREFIX + engine, usableCanonical(folder).path).apply()
    }

    fun clearOverride(engine: String) {
        preferences.edit().remove(KEY_ENGINE_PREFIX + engine).apply()
    }

    private fun usableCanonical(folder: File): File {
        val canonical = folder.canonicalFile
        require(canonical.isDirectory || canonical.mkdirs()) { "Could not create the selected folder" }
        require(canonical.canWrite()) { "The selected folder is not writable" }
        return canonical
    }

    private fun ensureUsable(folder: File): File = folder.also {
        require(it.isDirectory || it.mkdirs()) { "Could not create Enginehost's save folder" }
        require(it.canWrite()) { "Enginehost's save folder is not writable" }
    }

    /** Legacy location used before the configurable shared save root existed. */
    @Suppress("DEPRECATION")
    fun legacyRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "Enginehost/saves",
    )

    fun migrate(source: File, destination: File = saveRoot()): MigrationResult {
        val from = source.canonicalFile
        val to = destination.canonicalFile
        if (!from.exists() || from == to) return MigrationResult(0, 0, 0)
        require(!to.toPath().startsWith(from.toPath())) { "The destination cannot be inside the source" }

        var copied = 0
        var conflicts = 0
        var failures = 0
        from.walkBottomUp().forEach { item ->
            val relative = item.relativeTo(from)
            val target = File(to, relative.path)
            if (item.isDirectory) {
                if (!target.exists() && !target.mkdirs()) failures++
            } else {
                try {
                    target.parentFile?.mkdirs()
                    if (target.exists()) {
                        if (sameContents(item, target)) item.delete() else conflicts++
                    } else {
                        FileInputStream(item).use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                        if (sameContents(item, target)) {
                            copied++
                            item.delete()
                        } else {
                            target.delete()
                            failures++
                        }
                    }
                } catch (_: Exception) {
                    failures++
                }
            }
        }
        from.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        return MigrationResult(copied, conflicts, failures)
    }

    private fun sameContents(left: File, right: File): Boolean {
        if (left.length() != right.length()) return false
        FileInputStream(left).use { a ->
            FileInputStream(right).use { b ->
                val ab = ByteArray(DEFAULT_BUFFER_SIZE)
                val bb = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val ac = a.read(ab)
                    val bc = b.read(bb)
                    if (ac != bc) return false
                    if (ac < 0) return true
                    if (!ab.copyOf(ac).contentEquals(bb.copyOf(bc))) return false
                }
            }
        }
    }

    data class MigrationResult(val copied: Int, val conflicts: Int, val failures: Int)

    companion object {
        private const val KEY_ROOT = "root"
        private const val KEY_ENGINE_PREFIX = "root."
    }
}

/**
 * How a game's save folder is named when the runtime cannot name it.
 *
 * Engines that keep saves beside the game on a desktop, or in a browser's
 * storage, have no identity of their own to offer; two such games would
 * share one folder and one localStorage. The name comes from what the
 * engine would have used: a Twine story is stored under its story title,
 * an RPG Maker MV/MZ or AIR game under its install folder. Both are the
 * same for everyone who has the game, so saves move between devices and
 * survive a reinstall. Engines that name their own saves (Ren'Py, RGSS,
 * Godot) get no folder here and keep doing what they do.
 */
object SaveFolders {
    private val NAMED_BY_THE_HOST = setOf("html", "flash_air")

    /** Whether this engine and context need Enginehost to name the save folder. */
    fun applies(engine: String, engineContext: String?): Boolean =
        engine in NAMED_BY_THE_HOST || (engine == "rpgmaker" && (engineContext == "mv" || engineContext == "mz"))

    /**
     * The default folder name: [detectedName] (a story's own title) when
     * there is one, else the game folder's name, made safe for a
     * filesystem. Null for engines that name their own saves.
     */
    fun defaultFor(engine: String, engineContext: String?, detectedName: String?, folderName: String): String? {
        if (!applies(engine, engineContext)) return null
        return sanitize(detectedName?.takeIf { it.isNotBlank() } ?: folderName)
    }

    /** One folder name: no separators, no traversal, nothing a filesystem rejects. */
    fun isPlainName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it in "/\\\u0000" }

    fun sanitize(name: String): String {
        val cleaned = name.trim().map { if (it in "/\\:*?\"<>|\u0000" || it.code < 32) '_' else it }.joinToString("")
            .trimEnd('.', ' ')
        return cleaned.ifBlank { "game" }
    }
}
