package dev.enginehost

import android.content.ContentResolver
import android.net.Uri

/** Metadata inferred from a scanned game tree; fields may remain unknown. */
data class EngineDetection(
    val engine: String,
    val engineContext: String? = null,
    val engineVersion: String? = null,
    val execFile: String? = null,
    val evidence: String,
    val runtimeRequirements: Map<String, String> = emptyMap(),
)

/**
 * Game-engine detection used to prefill the config editor and classify
 * the library scanner's finds.
 *
 * CLASSIFICATION — which engine and context a folder holds — comes from
 * the shared `engines-database.json` registry ([EngineRegistryStore]),
 * the same file droidtop scans libraries with, evaluated with the same
 * semantics ([EngineRuleEvaluator]). That is the fix for a real
 * failure: this file used to carry its own hardcoded rule set, extended
 * independently of droidtop's, and the two could classify one game
 * differently — a library entry saying one thing and the launch doing
 * another. Detection rules now ship as a database update to both apps
 * at once.
 *
 * ENRICHMENT — engine version, exec file, evidence text, runtime
 * requirement prefills — stays code here, keyed on the row the registry
 * classified (the per-format parsing below: the `Game.ini` RGSS line,
 * `RPGMAKER_VERSION`, Ren'Py's `vc_version.py`/`version_tuple`, GDPC
 * pack headers, Twine's creator-version, SWF headers). Enrichment runs
 * strictly after classification and can never change it.
 */
object EngineDetector {
    private const val TEXT_READ_WINDOW = 256 * 1024

    /** Direct-path detection (exported CONFIGURE action, library scanner). */
    fun detect(rows: List<EngineRow>, folder: java.io.File): EngineDetection? =
        detect(rows, FileGameTree.scan(folder))

    /** SAF-tree detection (config editor's folder picker). */
    fun detect(rows: List<EngineRow>, resolver: ContentResolver, treeUri: Uri): EngineDetection? =
        detect(rows, SafGameTree.scan(resolver, treeUri))

    /**
     * Root first, then one level of subdirectories in name order — the
     * same nested-wrapper handling droidtop's `detectGame` applies for
     * distribution zips that wrap the real game in a version-named
     * folder, name-ordered so the answer never depends on listing order.
     */
    internal fun detect(rows: List<EngineRow>, tree: GameTree): EngineDetection? {
        classify(rows, tree)?.let { return it }
        return tree.dirPaths.filter { '/' !in it }.sorted().firstNotNullOfOrNull { dir ->
            classify(rows, tree.subtree(dir))
        }
    }

    private fun classify(rows: List<EngineRow>, tree: GameTree): EngineDetection? {
        val row = rows.firstOrNull { it.detect.isNotEmpty() && EngineRuleEvaluator.matches(it.detect, tree) }
            ?: return null
        return enrich(row, tree)
    }

    // ---- Enrichment ------------------------------------------------------

    private fun enrich(row: EngineRow, tree: GameTree): EngineDetection {
        val family = row.family ?: return unmapped(row, tree)
        return when {
            family == "rpgmaker" && (row.context == "xp" || row.context == "vx" || row.context == "vxace") -> rgss(row, tree)
            family == "rpgmaker" && (row.context == "mv" || row.context == "mz") -> mvmz(row, tree)
            family == "rpgmaker" && row.id == "rpgmaker-2000-2003" -> EngineDetection(
                family,
                evidence = "Found RPG Maker 2000/2003 data; choose the exact context",
            )
            family == "rpgmaker" -> EngineDetection(
                family,
                evidence = "Found an RPG Maker MV/MZ web runtime without its core script; choose context mv or mz",
            )
            family == "renpy" -> renpy(row, tree)
            family == "godot" -> godot(row, tree)
            family == "twine" -> twine(row, tree)
            family == "flash_air" && row.context == "swf" -> swf(row, tree)
            family == "flash_air" -> EngineDetection(family, row.context, evidence = "Found an Adobe AIR package (META-INF + mimetype)")
            family == "kirikiri2" -> EngineDetection(family, row.context, evidence = "Found KiriKiri XP3/TJS assets")
            family == "catsystem2" -> scriptDetection(row, tree, "cst", "Found a CatSystem2 CST script; runtime version still needs confirmation")
            family == "cmvs" && row.context != null -> scriptDetection(row, tree, row.context, "Found a CMVS ${row.context.uppercase()} script; runtime version still needs confirmation")
            family == "cmvs" -> EngineDetection(family, evidence = "Found the CMVS runtime; choose the ps2 or ps3 context")
            family == "buriko" -> EngineDetection(family, row.context, evidence = "Found Buriko engine evidence; runtime version still needs confirmation")
            else -> EngineDetection(family, row.context, evidence = "Matched the ${row.id} registry row", runtimeRequirements = row.extras)
        }
    }

    /** A row with no enginehost mapping (unity/unreal): informational only, so the scanner and editor can still say what they saw. */
    private fun unmapped(row: EngineRow, tree: GameTree): EngineDetection {
        if (row.id == "unity") {
            val paths = tree.filePaths.map { it.lowercase() }
            val context = when {
                paths.any { it.substringAfterLast('/') == "gameassembly.dll" } || paths.any { it.contains("il2cpp_data/") } -> "il2cpp"
                paths.any { it.contains("_data/managed/") } -> "mono"
                else -> null
            }
            return EngineDetection("unity", context, evidence = "Found a Unity player; no Enginehost plugin runs Unity yet")
        }
        return EngineDetection(row.id, evidence = "Recognized ${row.id}; no Enginehost plugin runs it")
    }

    private fun text(tree: GameTree, path: String): String =
        String(tree.readHead(path, TEXT_READ_WINDOW), Charsets.UTF_8)

    private fun rootFile(tree: GameTree, lowercaseName: String): String? =
        tree.filePaths.firstOrNull { '/' !in it && it.lowercase() == lowercaseName }

    private fun findSuffix(tree: GameTree, suffix: String): String? =
        tree.filePaths.firstOrNull { it == suffix || it.endsWith("/$suffix") }

    private fun rgss(row: EngineRow, tree: GameTree): EngineDetection {
        val ini = rootFile(tree, "game.ini")
        val match = ini?.let {
            Regex("(?im)^Library\\s*=\\s*(?:.*[\\\\/])?RGSS(\\d)(\\d{2})[A-Z]?\\.dll\\s*$").find(text(tree, it))
        }
        val version = match?.let { "${it.groupValues[1].toInt()}.${it.groupValues[2].toInt()}" }
        val evidence = when {
            match != null -> "Game.ini names RGSS${match.groupValues[1]}${match.groupValues[2]}"
            else -> "Found RGSS ${row.context} evidence (archive/project marker)"
        }
        return EngineDetection(
            "rpgmaker",
            row.context,
            version,
            rootFile(tree, "game.exe")?.let { tree.pathPrefix + it },
            evidence,
            row.extras,
        )
    }

    private fun mvmz(row: EngineRow, tree: GameTree): EngineDetection {
        val coreName = if (row.context == "mz") "rmmz_core.js" else "rpg_core.js"
        val core = findSuffix(tree, "js/$coreName")
        val version = core?.let {
            Regex("RPGMAKER_VERSION\\s*=\\s*[\"'](\\d+(?:\\.\\d+)+)").find(text(tree, it))?.groupValues?.get(1)
        }
        val execFile = core?.let { tree.pathPrefix + it.substringBeforeLast("js/$coreName") + "index.html" }
        return EngineDetection("rpgmaker", row.context, version, execFile, "Found $coreName")
    }

    private fun renpy(row: EngineRow, tree: GameTree): EngineDetection {
        // Modern Ren'Py writes the full version into vc_version.py; older
        // builds keep only a build stamp there and the real version_tuple
        // in the runtime's own __init__.py.
        val version = findSuffix(tree, "renpy/vc_version.py")
            ?.let { Regex("(?m)^version\\s*=\\s*[\"'](\\d+(?:\\.\\d+)+)").find(text(tree, it))?.groupValues?.get(1) }
            ?: findSuffix(tree, "renpy/__init__.py")?.let {
                Regex("version_tuple\\s*=\\s*\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")
                    .find(text(tree, it))?.groupValues?.drop(1)?.joinToString(".")
            }
        val evidence = if ("renpy" in tree.dirPaths) "Found the bundled Ren'Py runtime" else "Found compiled Ren'Py game files"
        return EngineDetection("renpy", row.context, version, evidence = evidence, runtimeRequirements = row.extras)
    }

    private fun godot(row: EngineRow, tree: GameTree): EngineDetection {
        rootFile(tree, "project.godot")?.let { project ->
            val version = Regex("(?m)^config/features=.*?[\"'](\\d+(?:\\.\\d+)+)").find(text(tree, project))?.groupValues?.get(1)
            return EngineDetection("godot", row.context, version, tree.pathPrefix + project, "Found a Godot project")
        }
        tree.filePaths.firstOrNull { '/' !in it && it.lowercase().endsWith(".pck") }?.let { pack ->
            val header = tree.readHead(pack, 20)
            godotHeaderVersion(header, 0)?.let { version ->
                return EngineDetection("godot", row.context, version, tree.pathPrefix + pack, "Found a Godot pack (GDPC)")
            }
        }
        tree.filePaths.filter { '/' !in it && it.lowercase().endsWith(".exe") }.take(4).forEach { executable ->
            godotEmbeddedPackVersion(tree, executable)?.let { version ->
                return EngineDetection(
                    "godot", row.context, version, tree.pathPrefix + executable,
                    "Found a Godot pack embedded in the executable",
                )
            }
        }
        return EngineDetection("godot", row.context, evidence = "Found a Godot export")
    }

    /** A pack header: GDPC, format version, then engine major/minor/patch, all little-endian. */
    private fun godotHeaderVersion(bytes: ByteArray, offset: Int): String? {
        if (bytes.size < offset + 20) return null
        if (String(bytes, offset, 4, Charsets.US_ASCII) != "GDPC") return null
        val buffer = java.nio.ByteBuffer.wrap(bytes, offset + 4, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.int
        val major = buffer.int
        val minor = buffer.int
        val patch = buffer.int
        if (major !in 1..9 || minor !in 0..99 || patch !in 0..999) return null
        return "$major.$minor.$patch"
    }

    /**
     * A pack appended to an executable ends with an 8-byte little-endian
     * pack size followed by GDPC at EOF; the pack itself begins at
     * length - 12 - packSize with the same header as a standalone pack.
     * Reading the whole tail (packSize + 12 bytes capped at the trailer
     * region) is avoided: only the 12-byte trailer and the 20-byte pack
     * head are fetched.
     */
    private fun godotEmbeddedPackVersion(tree: GameTree, path: String): String? {
        val size = tree.length(path)
        if (size < 32) return null
        val trailer = tree.readTail(path, 12)
        if (trailer.size != 12 || String(trailer, 8, 4, Charsets.US_ASCII) != "GDPC") return null
        val packSize = java.nio.ByteBuffer.wrap(trailer, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
        val start = size - 12 - packSize
        if (start < 0 || start > size - 20) return null
        // readTail gives the last N bytes; the pack head sits N-from-end where N = size - start.
        val fromEnd = size - start
        if (fromEnd > Int.MAX_VALUE) return null
        val tail = tree.readTail(path, fromEnd.toInt())
        if (tail.size.toLong() != fromEnd) return null
        return godotHeaderVersion(tail, 0)
    }

    private fun twine(row: EngineRow, tree: GameTree): EngineDetection {
        val html = tree.filePaths.firstOrNull { '/' !in it && it.lowercase().endsWith(".html") }
        val version = html?.let {
            Regex("creator-version=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                .find(String(tree.readHead(it, 512 * 1024), Charsets.UTF_8))
                ?.groupValues?.get(1)?.takeIf(::isNumericVersion)
        }
        return EngineDetection("twine", row.context, version, html?.let { tree.pathPrefix + it }, "Found Twine story metadata")
    }

    private fun swf(row: EngineRow, tree: GameTree): EngineDetection {
        val swf = tree.filePaths.firstOrNull { '/' !in it && it.lowercase().endsWith(".swf") }
        val header = swf?.let { tree.readHead(it, 4) }
        val version = header?.takeIf { it.size == 4 }?.let { "${it[3].toInt() and 0xff}.0" }
        return EngineDetection("flash_air", row.context, version, swf?.let { tree.pathPrefix + it }, "Found a SWF header")
    }

    private fun scriptDetection(row: EngineRow, tree: GameTree, extension: String, evidence: String): EngineDetection {
        val script = tree.filePaths.firstOrNull { '/' !in it && it.lowercase().endsWith(".$extension") }
        return EngineDetection(
            row.family!!,
            row.context,
            execFile = script?.let { tree.pathPrefix + it },
            evidence = evidence,
            runtimeRequirements = row.extras,
        )
    }

    private fun isNumericVersion(value: String): Boolean =
        value.isNotBlank() && value.split('.').all { it.isNotEmpty() && it.all(Char::isDigit) }
}
