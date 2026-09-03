package dev.enginehost

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile

/**
 * The game's own icon, for the launch screen: the icon the engine was
 * configured to show in its window title bar when the engine keeps that as
 * a plain file, otherwise the application icon of the game's Windows
 * executable. Every game enginehost runs was built for Windows first, so
 * the executable is always there even when nothing else is.
 *
 * Returns null when there is nothing usable; the launch screen then shows
 * the title alone. Never throws: this runs while the runtime is already
 * starting, and the only acceptable failure is a missing picture.
 */
object GameIcon {
    private const val MAX_ICON_FILE_BYTES = 2L shl 20

    /** Executables that are installers or redistributables, never the game. */
    private val NOT_THE_GAME = listOf("unins", "setup", "install", "dxwebsetup", "dxsetup", "vcredist", "vc_redist", "dotnet")

    fun load(gameFolder: File, config: EngineConfig): Bitmap? = try {
        configuredIcon(gameFolder, config) ?: executableIcon(gameFolder, config)
    } catch (_: Exception) {
        null
    }

    /**
     * Where each engine keeps the icon its games configure. Ren'Py's
     * `gui.window_icon` defaults to `gui/window_icon.png` under `game/`;
     * RPG Maker MV and MZ ship the NW.js window icon at `icon/icon.png`
     * (under `www/` when deployed unpacked); a Godot project exported
     * with its files unpacked keeps `config/icon` as `icon.png`.
     */
    private fun configuredIcon(folder: File, config: EngineConfig): Bitmap? {
        val candidates = when (config.engine) {
            "renpy" -> listOf("game/gui/window_icon.png")
            "rpgmaker" -> when (config.engineContext) {
                "mv", "mz" -> listOf("icon/icon.png", "www/icon/icon.png")
                else -> emptyList()
            }
            "godot" -> if (File(folder, "project.godot").isFile) listOf("icon.png") else emptyList()
            else -> emptyList()
        }
        for (relative in candidates) {
            val file = File(folder, relative)
            if (file.isFile && file.length() in 1..MAX_ICON_FILE_BYTES) {
                BitmapFactory.decodeFile(file.path)?.let { return it }
            }
        }
        return null
    }

    /**
     * The game's executable: the configured `execFile` when it is one,
     * otherwise the conventional engine launcher (`Game.exe`, `RPG_RT.exe`,
     * the folder's own name), otherwise the first executable in the folder
     * that is not an installer. Ren'Py's `-32` sibling sorts after the
     * 64-bit build so the two never disagree.
     */
    fun executable(folder: File, config: EngineConfig): File? {
        config.execFile?.let { File(folder, it) }
            ?.takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            ?.let { return it }
        val executables = folder.listFiles { f ->
            f.isFile && f.extension.equals("exe", ignoreCase = true) &&
                NOT_THE_GAME.none { f.nameWithoutExtension.lowercase().startsWith(it) }
        }?.toList() ?: return null
        if (executables.isEmpty()) return null
        val preferred = listOf("game.exe", "rpg_rt.exe", "${folder.name.lowercase()}.exe")
        preferred.forEach { name -> executables.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it } }
        return executables.sortedWith(compareBy({ it.nameWithoutExtension.endsWith("-32") }, { it.name.lowercase() })).first()
    }

    private fun executableIcon(folder: File, config: EngineConfig): Bitmap? {
        val exe = executable(folder, config) ?: return null
        val images = RandomAccessFile(exe, "r").use { PeIcon.extract(RandomAccessFileReadAt(it)) } ?: return null
        return PeIcon.best(images)?.let(::toBitmap)
    }

    fun toBitmap(image: IconImage): Bitmap? = when (image) {
        is IconImage.Png -> BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        is IconImage.Argb -> Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }
}
