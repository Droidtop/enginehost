package dev.enginehost

import java.io.File
import kotlin.math.exp

/** A game's own loading art, as found in its folder: what [LoadingImages.find] returns. */
sealed class LoadingArt {
    /** One picture, shown whole; [background] is the colour behind it when the game names one. */
    data class Still(val file: File, val background: Int? = null) : LoadingArt()

    /**
     * Ren'Py's progress presplash: [background] drawn whole, [foreground]
     * drawn over it from the left up to the fraction loaded.
     */
    data class Progress(val background: File, val foreground: File) : LoadingArt()
}

/**
 * Where each engine family keeps the picture its own loading screen shows,
 * for the host to draw while the core starts (docs/SPEC.md, "A game's own
 * loading screen"). One place, one table: an engine is added by naming its
 * location here.
 *
 * Only plain files in the game folder are read. Anything inside an engine's
 * own pack (Godot's .pck, KiriKiri's .xp3, an encrypted RPG Maker .rpgmvp) is
 * skipped, because reading a pack here would be the host doing the engine's
 * job; such a game gets the box art instead. Pure file lookups, no Android
 * types, so they are unit-tested on the JVM.
 *
 * The files are read by the host, in the runtime process, before the sandbox
 * closes and before any core code runs ([LoadingCover.prepare]).
 */
object LoadingImages {
    private val EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "avif")

    /** The loading art of [engine] in [gameFolder], or null when the game has none the host can read. */
    fun find(gameFolder: File, engine: String, context: String?): LoadingArt? = try {
        when (engine) {
            "renpy" -> renpy(gameFolder)
            "godot" -> godot(gameFolder)
            "rpgmaker" -> if (context == "mv" || context == "mz") rpgMaker(gameFolder) else null
            // KiriKiri keeps its start-up and loading pictures inside its .xp3 archives; it
            // has no plain-file location to read, so it shows the box art.
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Ren'Py. `android-presplash.*` beside the game is the Android build's
     * own; otherwise the desktop presplash in `game/`, which Ren'Py draws as
     * a progress bar when it has both a background and a foreground picture
     * (its source names them `presplash_background` and `presplash_foreground`;
     * the hyphenated spelling is read too), else as `presplash`.
     */
    private fun renpy(folder: File): LoadingArt? {
        named(folder, "android-presplash")?.let { return LoadingArt.Still(it) }
        val game = child(folder, "game")?.takeIf { it.isDirectory } ?: return null
        val background = named(game, "presplash_background") ?: named(game, "presplash-background")
        val foreground = named(game, "presplash_foreground") ?: named(game, "presplash-foreground")
        if (background != null && foreground != null) return LoadingArt.Progress(background, foreground)
        return named(game, "presplash")?.let { LoadingArt.Still(it) }
    }

    /**
     * Godot: the project's `application/boot_splash/image`, which project.godot
     * records as `res://...`. Read only when project.godot is a plain file
     * beside the game and the image is one too; in a game shipped as a .pck
     * neither is, and it is skipped. `boot_splash/show_image=false` turns the
     * image off, and `boot_splash/bg_color` is the colour behind it.
     */
    private fun godot(folder: File): LoadingArt? {
        val project = File(folder, "project.godot").takeIf { it.isFile && it.length() in 1..MAX_PROJECT_BYTES } ?: return null
        val settings = GodotProjectSettings.section(project.readText(), "application")
        if (settings["boot_splash/show_image"] == "false") return null
        val resource = settings["boot_splash/image"]?.let(GodotProjectSettings::unquote)?.takeIf { it.startsWith("res://") } ?: return null
        val segments = resource.removePrefix("res://").split('/')
        if (segments.any { it.isEmpty() || it == ".." || it == "." }) return null
        val image = File(folder, segments.joinToString("/")).takeIf { it.isFile && it.length() > 0 } ?: return null
        return LoadingArt.Still(image, settings["boot_splash/bg_color"]?.let(GodotProjectSettings::color))
    }

    /** RPG Maker MV and MZ: `img/system/Loading.png`, under `www/` when deployed that way. */
    private fun rpgMaker(folder: File): LoadingArt? {
        for (root in listOf(folder, child(folder, "www"))) {
            val system = root?.let { child(it, "img") }?.let { child(it, "system") }?.takeIf { it.isDirectory } ?: continue
            named(system, "Loading", listOf("png"))?.let { return LoadingArt.Still(it) }
        }
        return null
    }

    /** [name] in [dir], found whatever its case: games built on Windows do not agree with Android's file system. */
    private fun child(dir: File, name: String): File? {
        File(dir, name).takeIf { it.exists() }?.let { return it }
        return dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** The first regular, non-empty `base.<ext>` in [dir], by [extensions] order. */
    private fun named(dir: File, base: String, extensions: List<String> = EXTENSIONS): File? {
        val present = dir.listFiles() ?: return null
        for (extension in extensions) {
            val wanted = "$base.$extension"
            val file = present.firstOrNull { it.name == wanted && it.isFile && it.length() > 0 }
                ?: present.firstOrNull { it.name.equals(wanted, ignoreCase = true) && it.isFile && it.length() > 0 }
            if (file != null) return file
        }
        return null
    }

    private const val MAX_PROJECT_BYTES = 4L shl 20
}

/** The little of project.godot's INI-like text the loading screen needs. */
internal object GodotProjectSettings {
    /** `key=value` lines of `[name]`, values as written. */
    fun section(text: String, name: String): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        var inside = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inside = line.substring(1, line.length - 1).trim() == name
            } else if (inside && !line.startsWith(";") && '=' in line) {
                found[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
            }
        }
        return found
    }

    fun unquote(value: String): String? =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else null

    private val COLOR = Regex("""Color\(\s*([-0-9.eE]+)\s*,\s*([-0-9.eE]+)\s*,\s*([-0-9.eE]+)\s*(?:,\s*([-0-9.eE]+)\s*)?\)""")

    /** Godot's `Color(r, g, b[, a])`, channels 0..1, as an opaque ARGB int; null for anything else. */
    fun color(value: String): Int? {
        val parts = COLOR.matchEntire(value.trim())?.groupValues ?: return null
        val channels = (1..3).map { (parts[it].toFloatOrNull() ?: return null).coerceIn(0f, 1f) }
        fun byte(c: Float) = Math.round(c * 255)
        return (0xFF shl 24) or (byte(channels[0]) shl 16) or (byte(channels[1]) shl 8) or byte(channels[2])
    }
}

/**
 * How far along Ren'Py's progress bar is drawn. Ren'Py's own bar counts the
 * script files loaded, which the host cannot see from outside the core, so
 * this is an estimate that eases towards the end and never reaches it; the
 * cover is lifted by the core's first frame, not by the bar filling.
 */
internal object LoadingProgress {
    private const val EASE_MS = 6000.0

    fun estimate(elapsedMs: Long): Float = (0.95 * (1 - exp(-elapsedMs.coerceAtLeast(0) / EASE_MS))).toFloat()
}
