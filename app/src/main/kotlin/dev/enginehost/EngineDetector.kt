package dev.enginehost

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.ArrayDeque

/** Metadata inferred from a complete SAF tree scan; fields may remain unknown. */
data class EngineDetection(
    val engine: String,
    val engineContext: String? = null,
    val engineVersion: String? = null,
    val execFile: String? = null,
    val evidence: String,
    val runtimeRequirements: Map<String, String> = emptyMap(),
)

/** Conservative game-engine detection used only to prefill the config editor. */
object EngineDetector {
    private data class Entry(val uri: Uri, val path: String, val directory: Boolean)

    /** Direct-path equivalent used by the exported CONFIGURE action. */
    fun detect(folder: java.io.File): EngineDetection? {
        val files = folder.walkTopDown().filter(java.io.File::isFile).associateBy {
            it.relativeTo(folder).invariantSeparatorsPath.lowercase()
        }
        fun suffix(vararg names: String) = files.entries.firstOrNull { (path, _) ->
            names.any { path == it || path.endsWith("/$it") }
        }?.value
        fun relative(file: java.io.File) = file.relativeTo(folder).invariantSeparatorsPath
        fun text(file: java.io.File) = file.inputStream().buffered().use { input ->
            String(input.readNBytes(256 * 1024), Charsets.UTF_8)
        }

        files["game.ini"]?.let { iniFile ->
            Regex("(?im)^Library\\s*=\\s*(?:.*[\\\\/])?RGSS(\\d)(\\d{2})[A-Z]?\\.dll\\s*$")
                .find(text(iniFile))?.let { match ->
                    val line = match.groupValues[1].toInt()
                    val context = mapOf(1 to "xp", 2 to "vx", 3 to "vxace")[line]
                    if (context != null) return EngineDetection(
                        "rpgmaker", context, "$line.${match.groupValues[2].toInt()}", files["game.exe"]?.let(::relative),
                        "Game.ini names RGSS$line${match.groupValues[2]}",
                        if (line == 3) mapOf("ruby" to "1.9.2") else emptyMap(),
                    )
                }
        }
        // The RGSS archive generation is itself the context evidence.
        files.values.firstOrNull { it.extension.equals("rgss3a", true) }?.let {
            return EngineDetection(
                "rpgmaker", "vxace", evidence = "Found an RGSS3 archive (.rgss3a)",
                runtimeRequirements = mapOf("ruby" to "1.9.2"),
            )
        }
        files.values.firstOrNull { it.extension.equals("rgss2a", true) }?.let {
            return EngineDetection("rpgmaker", "vx", evidence = "Found an RGSS2 archive (.rgss2a)")
        }
        files.values.firstOrNull { it.extension.equals("rgssad", true) }?.let {
            return EngineDetection("rpgmaker", "xp", evidence = "Found an RGSS archive (.rgssad)")
        }
        suffix("js/rmmz_core.js")?.let { core ->
            val version = Regex("RPGMAKER_VERSION\\s*=\\s*[\"'](\\d+(?:\\.\\d+)+)").find(text(core))?.groupValues?.get(1)
            return EngineDetection("rpgmaker", "mz", version, relative(core).substringBeforeLast("js/rmmz_core.js") + "index.html", "Found rmmz_core.js")
        }
        suffix("js/rpg_core.js")?.let { core ->
            val version = Regex("RPGMAKER_VERSION\\s*=\\s*[\"'](\\d+(?:\\.\\d+)+)").find(text(core))?.groupValues?.get(1)
            return EngineDetection("rpgmaker", "mv", version, relative(core).substringBeforeLast("js/rpg_core.js") + "index.html", "Found rpg_core.js")
        }
        if (files.keys.any { it.endsWith("js/main.js") } && files.keys.any { it.endsWith("index.html") }) {
            return EngineDetection(
                "rpgmaker",
                evidence = "Found an RPG Maker MV/MZ web runtime without its core script; choose context mv or mz",
            )
        }
        if (
            files.keys.any { it.endsWith("rpg_rt.ldb") } &&
            (files.keys.any { it.endsWith("rpg_rt.lmt") } || files.keys.any { it.endsWith("rpg_rt.exe") })
        ) {
            return EngineDetection("rpgmaker", evidence = "Found RPG Maker 2000/2003 data; choose the exact context")
        }
        suffix("renpy/__init__.py")?.let { init ->
            // Modern Ren'Py writes the full version into vc_version.py; older
            // builds keep only a build stamp there and the real version_tuple
            // in the runtime's own __init__.py.
            val version = suffix("renpy/vc_version.py")
                ?.let { Regex("(?m)^version\\s*=\\s*[\"'](\\d+(?:\\.\\d+)+)").find(text(it))?.groupValues?.get(1) }
                ?: Regex("version_tuple\\s*=\\s*\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")
                    .find(text(init))?.groupValues?.drop(1)?.joinToString(".")
            return EngineDetection("renpy", "standard", version, evidence = "Found the bundled Ren'Py runtime")
        }
        if (files.keys.any { it.endsWith(".rpyc") || it.endsWith(".rpa") }) return EngineDetection("renpy", "standard", evidence = "Found compiled Ren'Py game files")
        files["project.godot"]?.let { project ->
            val version = Regex("(?m)^config/features=.*?[\"'](\\d+(?:\\.\\d+)+)").find(text(project))?.groupValues?.get(1)
            return EngineDetection("godot", "standard", version, "project.godot", "Found a Godot project")
        }
        files.values.firstOrNull { it.extension.equals("pck", true) }?.let { pack ->
            godotPackVersion(pack)?.let { version ->
                return EngineDetection("godot", "standard", version, relative(pack), "Found a Godot pack (GDPC)")
            }
        }
        files.values.filter { it.extension.equals("exe", true) }.take(4).forEach { executable ->
            godotEmbeddedPackVersion(executable)?.let { version ->
                return EngineDetection(
                    "godot", "standard", version, relative(executable),
                    "Found a Godot pack embedded in the executable",
                )
            }
        }
        files.values.firstOrNull { it.extension.equals("html", true) }?.let { html ->
            val source = text(html)
            if (source.contains("<tw-storydata", true)) {
                val version = Regex("creator-version=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.takeIf(::isNumericVersion)
                return EngineDetection("twine", "compiled-html", version, relative(html), "Found Twine story metadata")
            }
        }
        files.values.firstOrNull { it.extension.equals("swf", true) }?.let { swf ->
            val header = swf.inputStream().use { it.readNBytes(4) }
            if (header.size == 4 && String(header, 0, 3) in setOf("FWS", "CWS", "ZWS")) return EngineDetection("flash_air", "swf", "${header[3].toInt() and 0xff}.0", relative(swf), "Found a SWF header")
        }
        if (files.containsKey("data.xp3") || files.containsKey("startup.tjs")) return EngineDetection("kirikiri2", "default", evidence = "Found KiriKiri XP3/TJS assets")
        files.values.firstOrNull { it.extension.equals("cst", true) }?.let { return EngineDetection("catsystem2", "cst", execFile = relative(it), evidence = "Found a CatSystem2 CST script; runtime version still needs confirmation") }
        files.values.firstOrNull { it.extension.equals("ps3", true) }?.let { return EngineDetection("cmvs", "ps3", execFile = relative(it), evidence = "Found a CMVS PS3 script; runtime version still needs confirmation") }
        files.values.firstOrNull { it.extension.equals("ps2", true) }?.let { return EngineDetection("cmvs", "ps2", execFile = relative(it), evidence = "Found a CMVS PS2 script; runtime version still needs confirmation") }
        if (files.keys.any { it.endsWith("data01000.arc") }) return EngineDetection("buriko", "compiled-script-v1", evidence = "Found a Buriko archive set; runtime version still needs confirmation")
        if (files.keys.any { it.contains("_data/") }) {
            if (files.containsKey("gameassembly.dll") || files.keys.any { it.contains("il2cpp_data/") }) {
                return EngineDetection("unity", "il2cpp", evidence = "Found a Unity IL2CPP player; no Enginehost plugin runs Unity yet")
            }
            if (files.keys.any { it.contains("_data/managed/") }) {
                return EngineDetection("unity", "mono", evidence = "Found a Unity Mono player; no Enginehost plugin runs Unity yet")
            }
        }
        return null
    }

    /** A standalone Godot pack opens with GDPC, format version, then engine major/minor/patch. */
    private fun godotPackVersion(file: java.io.File): String? = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            if (raf.length() < 20) return@use null
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "GDPC") return@use null
            readGodotHeaderVersion(raf)
        }
    }.getOrNull()

    /**
     * A pack appended to an executable ends with an 8-byte little-endian pack
     * size followed by GDPC at EOF; the pack itself begins at
     * length - 12 - packSize with the same header as a standalone pack.
     */
    private fun godotEmbeddedPackVersion(file: java.io.File): String? = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 32) return@use null
            raf.seek(raf.length() - 12)
            val sizeBytes = ByteArray(8)
            raf.readFully(sizeBytes)
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "GDPC") return@use null
            val packSize = java.nio.ByteBuffer.wrap(sizeBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            val start = raf.length() - 12 - packSize
            if (start < 0 || start > raf.length() - 20) return@use null
            raf.seek(start)
            val startMagic = ByteArray(4)
            raf.readFully(startMagic)
            if (String(startMagic, Charsets.US_ASCII) != "GDPC") return@use null
            readGodotHeaderVersion(raf)
        }
    }.getOrNull()

    private fun readGodotHeaderVersion(raf: java.io.RandomAccessFile): String? {
        val header = ByteArray(16)
        raf.readFully(header)
        val buffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.int
        val major = buffer.int
        val minor = buffer.int
        val patch = buffer.int
        if (major !in 1..9 || minor !in 0..99 || patch !in 0..999) return null
        return "$major.$minor.$patch"
    }

    fun detect(resolver: ContentResolver, treeUri: Uri): EngineDetection? {
        val entries = scanTree(resolver, treeUri)
        val files = entries.filterNot { it.directory }.associateBy { it.path.lowercase() }
        val names = files.keys

        files["game.ini"]?.let { gameIni ->
            val ini = readText(resolver, gameIni.uri)
            Regex("(?im)^Library\\s*=\\s*(?:.*[\\\\/])?RGSS(\\d)(\\d{2})[A-Z]?\\.dll\\s*$")
                .find(ini)?.let { match ->
                    val line = match.groupValues[1].toInt()
                    val patch = match.groupValues[2].toInt()
                    val context = when (line) {
                        1 -> "xp"
                        2 -> "vx"
                        3 -> "vxace"
                        else -> null
                    }
                    if (context != null) {
                        return EngineDetection(
                            "rpgmaker",
                            context,
                            "$line.$patch",
                            files["game.exe"]?.path,
                            "Game.ini names RGSS$line${match.groupValues[2]}",
                            runtimeRequirements = if (line == 3) mapOf("ruby" to "1.9.2") else emptyMap(),
                        )
                    }
                }
        }

        findSuffix(files, "/js/rmmz_core.js", "js/rmmz_core.js")?.let { core ->
            return EngineDetection(
                "rpgmaker",
                "mz",
                javascriptVersion(resolver, core.uri),
                core.path.substringBeforeLast("js/rmmz_core.js") + "index.html",
                "Found rmmz_core.js",
            )
        }
        findSuffix(files, "/js/rpg_core.js", "js/rpg_core.js")?.let { core ->
            return EngineDetection(
                "rpgmaker",
                "mv",
                javascriptVersion(resolver, core.uri),
                core.path.substringBeforeLast("js/rpg_core.js") + "index.html",
                "Found rpg_core.js",
            )
        }

        files.values.firstOrNull { it.path.endsWith(".rgss3a", true) }?.let {
            return EngineDetection(
                "rpgmaker", "vxace", evidence = "Found an RGSS3 archive (.rgss3a)",
                runtimeRequirements = mapOf("ruby" to "1.9.2"),
            )
        }
        files.values.firstOrNull { it.path.endsWith(".rgss2a", true) }?.let {
            return EngineDetection("rpgmaker", "vx", evidence = "Found an RGSS2 archive (.rgss2a)")
        }
        files.values.firstOrNull { it.path.endsWith(".rgssad", true) }?.let {
            return EngineDetection("rpgmaker", "xp", evidence = "Found an RGSS archive (.rgssad)")
        }
        if (names.any { it.endsWith("js/main.js") } && names.any { it.endsWith("index.html") }) {
            return EngineDetection(
                "rpgmaker",
                evidence = "Found an RPG Maker MV/MZ web runtime without its core script; choose context mv or mz",
            )
        }
        if (names.any { it.endsWith("rpg_rt.ldb") } && (names.any { it.endsWith("rpg_rt.lmt") } || names.any { it.endsWith("rpg_rt.exe") })) {
            return EngineDetection(
                "rpgmaker",
                evidence = "Found the RPG Maker 2000/2003 database and map tree; choose context 2000 or 2003",
            )
        }

        findSuffix(files, "/renpy/__init__.py", "renpy/__init__.py")?.let { init ->
            val versionFile = findSuffix(files, "/renpy/vc_version.py", "renpy/vc_version.py")
            val version = versionFile?.let { readText(resolver, it.uri) }
                ?.let { Regex("(?m)^version\\s*=\\s*[\"'](\\d+(?:\\.\\d+)+)").find(it) }
                ?.groupValues?.get(1)
                ?: Regex("version_tuple\\s*=\\s*\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")
                    .find(readText(resolver, init.uri))?.groupValues?.drop(1)?.joinToString(".")
            return EngineDetection("renpy", "standard", version, evidence = "Found the bundled Ren'Py runtime")
        }
        if (names.any { it.endsWith(".rpyc") } || names.any { it.endsWith(".rpa") }) {
            return EngineDetection("renpy", "standard", evidence = "Found compiled Ren'Py game files")
        }

        files["project.godot"]?.let { project ->
            val text = readText(resolver, project.uri)
            val version = Regex("(?m)^config/features=.*?[\"'](\\d+(?:\\.\\d+)+)")
                .find(text)?.groupValues?.get(1)
            return EngineDetection("godot", "standard", version, "project.godot", "Found a Godot project")
        }

        files.values.firstOrNull { it.path.endsWith(".html", true) }?.let { html ->
            val text = readText(resolver, html.uri)
            if (text.contains("<tw-storydata", ignoreCase = true)) {
                val version = Regex("creator-version=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.takeIf(::isNumericVersion)
                return EngineDetection("twine", "compiled-html", version, html.path, "Found Twine story metadata")
            }
        }

        files.values.firstOrNull { it.path.endsWith(".pck", true) }?.let { pack ->
            val header = readBytes(resolver, pack.uri, 20)
            if (header.size >= 20 && String(header, 0, 4, Charsets.US_ASCII) == "GDPC") {
                val buffer = java.nio.ByteBuffer.wrap(header, 4, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.int
                val major = buffer.int
                val minor = buffer.int
                val patch = buffer.int
                if (major in 1..9 && minor in 0..99 && patch in 0..999) {
                    return EngineDetection("godot", "standard", "$major.$minor.$patch", pack.path, "Found a Godot pack (GDPC)")
                }
            }
        }
        if (names.any { it.contains("_data/") }) {
            if (files.containsKey("gameassembly.dll") || names.any { it.contains("il2cpp_data/") }) {
                return EngineDetection("unity", "il2cpp", evidence = "Found a Unity IL2CPP player; no Enginehost plugin runs Unity yet")
            }
            if (names.any { it.contains("_data/managed/") }) {
                return EngineDetection("unity", "mono", evidence = "Found a Unity Mono player; no Enginehost plugin runs Unity yet")
            }
        }
        files.values.firstOrNull { it.path.endsWith(".swf", true) }?.let { swf ->
            val header = readBytes(resolver, swf.uri, 4)
            if (header.size >= 4 && String(header, 0, 3) in setOf("FWS", "CWS", "ZWS")) {
                return EngineDetection("flash_air", "swf", "${header[3].toInt() and 0xff}.0", swf.path, "Found a SWF header")
            }
        }

        if (names.any { it.endsWith("data.xp3") } || names.any { it.endsWith("startup.tjs") }) {
            return EngineDetection("kirikiri2", "default", evidence = "Found KiriKiri XP3/TJS assets")
        }
        files.values.firstOrNull { it.path.endsWith(".cst", true) }?.let {
            return EngineDetection("catsystem2", "cst", execFile = it.path, evidence = "Found a CatSystem2 CST script; runtime version still needs confirmation")
        }
        files.values.firstOrNull { it.path.endsWith(".ps3", true) }?.let {
            return EngineDetection("cmvs", "ps3", execFile = it.path, evidence = "Found a CMVS PS3 script; runtime version still needs confirmation")
        }
        files.values.firstOrNull { it.path.endsWith(".ps2", true) }?.let {
            return EngineDetection("cmvs", "ps2", execFile = it.path, evidence = "Found a CMVS PS2 script; runtime version still needs confirmation")
        }
        if (names.any { it.endsWith("data01000.arc") }) {
            return EngineDetection("buriko", "compiled-script-v1", evidence = "Found a Buriko archive set; runtime version still needs confirmation")
        }
        return null
    }

    private fun scanTree(resolver: ContentResolver, treeUri: Uri): List<Entry> {
        val result = mutableListOf<Entry>()
        val pending = ArrayDeque<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        pending.add(DocumentsContract.getTreeDocumentId(treeUri) to "")
        while (pending.isNotEmpty()) {
            val (parentId, parentPath) = pending.removeFirst()
            if (!seen.add(parentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(id)
                    val displayName = cursor.getString(name)
                    val path = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
                    val directory = cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    result += Entry(uri, path, directory)
                    if (directory) pending.add(documentId to path)
                }
            }
        }
        return result
    }

    private fun findSuffix(files: Map<String, Entry>, vararg suffixes: String): Entry? =
        files.entries.firstOrNull { (path, _) -> suffixes.any { path == it || path.endsWith(it) } }?.value

    private fun javascriptVersion(resolver: ContentResolver, uri: Uri): String? =
        Regex("RPGMAKER_VERSION\\s*=\\s*[\"'](\\d+(?:\\.\\d+)+)")
            .find(readText(resolver, uri))?.groupValues?.get(1)

    private fun readText(resolver: ContentResolver, uri: Uri): String =
        String(readBytes(resolver, uri, 256 * 1024), Charsets.UTF_8)

    private fun readBytes(resolver: ContentResolver, uri: Uri, limit: Int): ByteArray =
        resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: byteArrayOf()

    private fun isNumericVersion(value: String): Boolean =
        value.isNotBlank() && value.split('.').all { it.isNotEmpty() && it.all(Char::isDigit) }
}
