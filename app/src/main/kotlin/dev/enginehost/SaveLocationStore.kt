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
     * The folder handed to a game's runtime as what its system locations
     * mean: the engine's save root, or the game's own folder beneath it for
     * an engine whose system location has no per-game name ([SaveFolders]).
     * A `saveFolder` an older config names for any other engine is not used.
     */
    fun saveFolderFor(config: EngineConfig): File {
        val root = saveRootFor(config.engine)
        if (!SaveFolders.applies(config.engine, config.engineContext)) return root
        val name = config.saveFolder ?: return root
        require(SaveFolders.isPlainName(name)) { "A save folder must be a single folder name" }
        return ensureUsable(File(root, name))
    }

    /**
     * Saves an earlier Enginehost kept for this game in a folder of its own
     * that the game folder does not have: for an engine that saves beside
     * the game and was given a host folder instead until the save policy
     * of 2026-09-17 ([SaveFolders.formerlyNamed]). Null when there are none,
     * or when the person said to leave them where they are.
     */
    fun earlierSavesFor(config: EngineConfig, gameFolder: File): EarlierSaves? {
        if (!SaveFolders.formerlyNamed(config.engine, config.engineContext)) return null
        val name = config.saveFolder ?: SaveFolders.sanitize(gameFolder.name)
        val folder = File(File(rootFor(config.engine), "saves"), name)
        if (!folder.isDirectory || preferences.getBoolean(KEY_EARLIER_LEFT_PREFIX + folder.absolutePath, false)) return null
        return EarlierSaves.find(folder, gameFolder)
    }

    /** The person wants [saves] left where they are, and not to be asked again. */
    fun leaveEarlierSaves(saves: EarlierSaves) {
        preferences.edit().putBoolean(KEY_EARLIER_LEFT_PREFIX + saves.from.absolutePath, true).apply()
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
        if (!it.isDirectory && !it.mkdirs()) throw UnusableSaveFolderException(it, "Could not create Enginehost's save folder")
        if (!it.canWrite()) throw UnusableSaveFolderException(it, "Enginehost's save folder is not writable")
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

    data class MigrationResult(val copied: Int, val conflicts: Int, val failures: Int)

    companion object {
        private const val KEY_ROOT = "root"
        private const val KEY_ENGINE_PREFIX = "root."
        private const val KEY_EARLIER_LEFT_PREFIX = "earlier-left."
    }
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

/**
 * Saves in a folder an earlier Enginehost gave a game ([from]) that are not
 * in the game folder ([to]) the engine now saves in. Each engine that had
 * such a folder used it exactly where the game folder is now (EasyRPG's
 * `--save-path`, CMVS's root for the save subfolder its boot script names),
 * so a file goes to the same relative path in the game folder.
 *
 * Nothing here deletes or overwrites anything: a file the game folder
 * already has is the game's own and is left as it is, and every file stays
 * in [from] as well after it is copied.
 */
class EarlierSaves(val from: File, val to: File, val missing: List<String>) {
    data class Copied(val copied: Int, val failed: Int)

    /** Copies each missing file through a temporary name, so a half-written copy never looks like a save. */
    fun copy(): Copied {
        var copied = 0
        var failed = 0
        missing.forEach { path ->
            val source = File(from, path)
            val target = File(to, path)
            val partial = File(target.parentFile, ".${target.name}.enginehost-copy")
            val done = runCatching {
                if (target.exists()) return@runCatching false
                target.parentFile?.mkdirs()
                source.inputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                sameContents(source, partial) && !target.exists() && partial.renameTo(target)
            }.getOrDefault(false)
            partial.delete()
            if (done) copied++ else if (!target.exists()) failed++
        }
        return Copied(copied, failed)
    }

    companion object {
        fun find(from: File, to: File): EarlierSaves? {
            val missing = from.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(from).invariantSeparatorsPath }
                .filter { !File(to, it).exists() }
                .sorted()
                .toList()
            return if (missing.isEmpty()) null else EarlierSaves(from, to, missing)
        }
    }
}

/**
 * A save folder that cannot be created or written: a memory card that is not
 * mounted, a root chosen on storage that has since gone, or no all-files
 * grant. [folder] is the one that failed, for the sentence the person gets.
 */
class UnusableSaveFolderException(val folder: File, message: String) : IllegalArgumentException(message)

/**
 * Which games Enginehost names a save folder for.
 *
 * Enginehost does not change where an engine saves; it only makes the
 * engine's SYSTEM locations (a user profile, AppData, a browser's storage)
 * mean a folder the person chose (DECISIONS 2026-09-17). An engine that
 * saves beside the game on its desktop original keeps doing so, and gets
 * nothing named here: KiriKiri, Buriko, CMVS, NScripter, and RPG Maker
 * 2000/2003 and XP/VX/VX Ace.
 *
 * A system location that has no per-game name of its own does need one,
 * or two games would share one store: a browser's localStorage (HTML,
 * Flash/AIR, and RPG Maker MV/MZ as its web runtime runs here), and
 * CatSystem2's shipped startup.xml, which saves under the user profile. The
 * name comes from what the engine would have used: a Twine story's title,
 * else the game's folder name, stable across devices, so saves move between
 * devices and survive a reinstall. Engines that name their own namespace
 * inside the system location (Ren'Py, Godot) get no folder here either.
 */
object SaveFolders {
    /** Families whose system location has no per-game name of its own. */
    private val NAMED_BY_THE_HOST = setOf("html", "flash_air", "catsystem2")

    /** RPG Maker lines whose runtime here is a browser, with its one localStorage. */
    private val WEB_RPG_MAKER = setOf("mv", "mz")

    /**
     * Families that save beside the game but were given a host-named folder
     * before 2026-09-17; what an engine among them saved there is offered
     * back to the game folder ([EarlierSaves]).
     */
    private val FORMERLY_NAMED = setOf("kirikiri2", "buriko", "cmvs")

    /** Where a game's saves really are, which is what the in-game menu must say. */
    enum class Place {
        /** In the game's own folder, as on the engine's desktop original. */
        BESIDE_THE_GAME,

        /** In the per-game folder Enginehost named under the save root ([applies]). */
        NAMED_BY_THE_HOST,

        /** In a folder the engine names itself, inside the save root (Ren'Py, Godot). */
        ENGINE_NAMESPACE,
    }

    /** Engines that save beside the game on their desktop original, whatever an earlier Enginehost did. */
    private val BESIDE_THE_GAME = setOf("kirikiri2", "buriko", "cmvs", "nscripter")

    fun placeOf(engine: String, engineContext: String?): Place = when {
        applies(engine, engineContext) -> Place.NAMED_BY_THE_HOST
        engine in BESIDE_THE_GAME || (engine == "rpgmaker" && engineContext !in WEB_RPG_MAKER) -> Place.BESIDE_THE_GAME
        else -> Place.ENGINE_NAMESPACE
    }

    /** Whether this engine and context need Enginehost to name the save folder. */
    fun applies(engine: String, engineContext: String?): Boolean =
        engine in NAMED_BY_THE_HOST || (engine == "rpgmaker" && engineContext in WEB_RPG_MAKER)

    /** Whether an earlier Enginehost named a save folder this engine no longer gets. */
    fun formerlyNamed(engine: String, engineContext: String?): Boolean =
        engine in FORMERLY_NAMED || (engine == "rpgmaker" && engineContext !in WEB_RPG_MAKER)

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
