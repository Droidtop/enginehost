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

        if (names.any { it.endsWith("rpg_rt.ldb") } && names.any { it.endsWith("rpg_rt.lmt") }) {
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
            return EngineDetection("catsystem2", "cst", "2.0", it.path, "Found a CatSystem2 CST script")
        }
        files.values.firstOrNull { it.path.endsWith(".ps3", true) }?.let {
            return EngineDetection("cmvs", "ps3", "3.0", it.path, "Found a CMVS PS3 script")
        }
        files.values.firstOrNull { it.path.endsWith(".ps2", true) }?.let {
            return EngineDetection("cmvs", "ps2", "2.0", it.path, "Found a CMVS PS2 script")
        }
        if (names.any { it.endsWith("data01000.arc") }) {
            return EngineDetection("buriko", "compiled-script-v1", "1.0", evidence = "Found a Buriko archive set")
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
