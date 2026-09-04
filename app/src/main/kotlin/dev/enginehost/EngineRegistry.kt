package dev.enginehost

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/**
 * The shared engine-classification registry, read from the SAME
 * `engines-database.json` droidtop bundles and refreshes (v5, decided
 * 2026-09-02, recorded in droidtop's docs/SPEC.md §7e2b): one data file
 * is the classification authority for both apps, so what droidtop's
 * library scan says a game is and what enginehost decides at launch or
 * config time can never disagree. The file's own `comment` field
 * documents the rule semantics; this parser and [EngineRuleEvaluator]
 * implement them exactly as droidtop's `EngineRegistryParser`/
 * `EngineDetectRules` do: rules OR each other, the conditions inside a
 * rule AND together, and the first row in FILE ORDER with a matching
 * rule wins. Rows enginehost cannot use (no `enginehost` mapping, e.g.
 * unity/unreal) still classify — they surface as informational
 * detections — while rows droidtop cannot map (rpgmaker-mvmz,
 * flash-swf) are enginehost's alone.
 *
 * What this deliberately does NOT contain: version/execFile extraction.
 * That is enrichment ([EngineDetector]'s per-family code), runs after
 * classification, and can never change which engine a folder is.
 */
data class EngineRow(
    val id: String,
    val detect: List<DetectRule>,
    /** enginehost family from the row's `enginehost` object, or null for a row with no enginehost mapping. */
    val family: String?,
    val context: String?,
    /** The row's declared runtime-requirement prefills (e.g. vxace's ruby 1.9.2 — a prefill, not a pin: mkxp-z ships both Ruby branches and the config editor keeps this user-editable). */
    val extras: Map<String, String>,
)

data class DetectRule(val all: List<DetectCondition>)

sealed interface DetectCondition {
    data class DirExists(val path: String) : DetectCondition
    data class FileExists(val path: String) : DetectCondition
    data class AnyFileNameContains(val value: String) : DetectCondition
    data class AnyFileExtension(val value: String) : DetectCondition
    data class AnyFileExtensionDeep(val value: String, val maxDepth: Int) : DetectCondition
    data class AnyFileNameIn(val values: Set<String>) : DetectCondition
    data class DirNamePrefixCount(val prefix: String, val min: Int) : DetectCondition
    data class FileHeadRegex(val path: String, val regex: Regex) : DetectCondition
    data class Builtin(val name: String) : DetectCondition
}

object EngineRegistryParser {
    /**
     * Parses a v4+ registry. Throws on structural garbage (the caller's
     * validate-before-replace contract); an unknown condition type drops
     * its whole rule — never "condition ignored, rule matches anyway" —
     * so a NEWER database still loads soundly on an older app. Identical
     * contract to droidtop's parser.
     */
    fun parse(text: String): List<EngineRow> {
        val engines = JSONObject(text).getJSONArray("engines")
        val result = ArrayList<EngineRow>(engines.length())
        for (i in 0 until engines.length()) {
            val row = engines.getJSONObject(i)
            val enginehost = row.optJSONObject("enginehost")
            val extras = enginehost?.optJSONObject("extras")?.let { json ->
                buildMap { json.keys().forEach { key -> put(key, json.getString(key)) } }
            } ?: emptyMap()
            result += EngineRow(
                id = row.getString("id"),
                detect = parseDetect(row),
                family = enginehost?.getString("family"),
                context = enginehost?.takeUnless { it.isNull("context") }?.getString("context"),
                extras = extras,
            )
        }
        return result
    }

    private fun parseDetect(row: JSONObject): List<DetectRule> {
        val detect = row.optJSONArray("detect") ?: return emptyList()
        val rules = ArrayList<DetectRule>(detect.length())
        for (i in 0 until detect.length()) {
            val all = detect.getJSONObject(i).getJSONArray("all")
            val conditions = ArrayList<DetectCondition>(all.length())
            var unknown = false
            for (j in 0 until all.length()) {
                val c = all.getJSONObject(j)
                val parsed: DetectCondition? = when (c.getString("type")) {
                    "dirExists" -> DetectCondition.DirExists(c.getString("path"))
                    "fileExists" -> DetectCondition.FileExists(c.getString("path"))
                    "anyFileNameContains" -> DetectCondition.AnyFileNameContains(c.getString("value").lowercase())
                    "anyFileExtension" -> DetectCondition.AnyFileExtension(c.getString("value").lowercase())
                    "anyFileExtensionDeep" -> DetectCondition.AnyFileExtensionDeep(c.getString("value").lowercase(), c.getInt("maxDepth"))
                    "anyFileNameIn" -> DetectCondition.AnyFileNameIn(
                        buildSet {
                            val values = c.getJSONArray("values")
                            for (k in 0 until values.length()) add(values.getString(k).lowercase())
                        },
                    )
                    "dirNamePrefixCount" -> DetectCondition.DirNamePrefixCount(c.getString("prefix").lowercase(), c.getInt("min"))
                    "fileHeadRegex" -> DetectCondition.FileHeadRegex(
                        c.getString("path"),
                        Regex(c.getString("regex"), RegexOption.IGNORE_CASE),
                    )
                    "builtin" -> DetectCondition.Builtin(c.getString("name"))
                    else -> null
                }
                if (parsed == null) {
                    unknown = true
                    break
                }
                conditions += parsed
            }
            if (!unknown && conditions.isNotEmpty()) rules += DetectRule(conditions)
        }
        return rules
    }
}

/**
 * One scanned game tree the rules evaluate against — the seam that lets
 * the SAME rule semantics run over a `java.io.File` folder and a SAF
 * document tree (the two ways enginehost is handed a game). Paths are
 * relative to the tree root with '/' separators, original case; the
 * exact-path conditions (fileExists/dirExists/fileHeadRegex) are
 * case-sensitive on purpose, matching droidtop's filesystem-backed
 * evaluator so the two apps cannot diverge on an oddly-cased tree.
 */
interface GameTree {
    /** Relative path prefix from the originally supplied root ("" for the root tree; "Sub/" for a subtree). Enrichment prepends it so execFile stays caller-relative. */
    val pathPrefix: String
    val filePaths: List<String>
    val dirPaths: List<String>
    fun length(path: String): Long
    fun readHead(path: String, limit: Int): ByteArray

    /**
     * Up to [limit] bytes starting at [offset]. Random access is what
     * lets a container's own index be walked -- a Godot pack's file
     * directory, and from it one skeleton header in the middle of a
     * gigabyte -- without reading everything before it.
     */
    fun read(path: String, offset: Long, limit: Int): ByteArray

    /** The file's last [limit] bytes, or all of it when it is shorter. */
    fun readTail(path: String, limit: Int): ByteArray {
        val size = length(path)
        val count = minOf(limit.toLong(), size)
        return read(path, size - count, count.toInt())
    }

    fun subtree(dir: String): GameTree
}

/** Depth-capped [File] snapshot. The cap covers every shipped rule (deepest: unity's depth-3 builtin) while keeping per-candidate cost bounded on huge folders — the old detector's uncapped walkTopDown was strictly worse. */
class FileGameTree private constructor(
    private val root: File,
    override val pathPrefix: String,
    override val filePaths: List<String>,
    override val dirPaths: List<String>,
) : GameTree {
    override fun length(path: String): Long = File(root, path).length()

    override fun readHead(path: String, limit: Int): ByteArray = runCatching {
        File(root, path).inputStream().use { it.readNBytes(limit) }
    }.getOrDefault(ByteArray(0))

    override fun read(path: String, offset: Long, limit: Int): ByteArray = runCatching {
        val file = File(root, path)
        val count = minOf(limit.toLong(), (file.length() - offset).coerceAtLeast(0L)).toInt()
        if (count <= 0 || offset < 0) return@runCatching ByteArray(0)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            ByteArray(count).also { raf.readFully(it) }
        }
    }.getOrDefault(ByteArray(0))

    override fun subtree(dir: String): GameTree {
        val prefix = "$dir/"
        return FileGameTree(
            File(root, dir),
            pathPrefix + prefix,
            filePaths.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) },
            dirPaths.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) },
        )
    }

    companion object {
        private const val MAX_DEPTH = 4

        fun scan(folder: File): FileGameTree {
            val files = mutableListOf<String>()
            val dirs = mutableListOf<String>()
            val queue = ArrayDeque<Pair<File, String>>()
            queue.add(folder to "")
            while (queue.isNotEmpty()) {
                val (dir, prefix) = queue.removeFirst()
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    val path = prefix + child.name
                    val isDirectory = runCatching { child.isDirectory }.getOrDefault(false)
                    if (isDirectory) {
                        dirs += path
                        if (path.count { it == '/' } < MAX_DEPTH) queue.add(child to "$path/")
                    } else {
                        files += path
                    }
                }
            }
            return FileGameTree(folder, "", files, dirs)
        }
    }
}

/** SAF snapshot: the full tree listing (the picked tree is one game, not a card), reads through the resolver. */
class SafGameTree private constructor(
    private val resolver: ContentResolver,
    override val pathPrefix: String,
    private val files: Map<String, Entry>,
    override val dirPaths: List<String>,
) : GameTree {
    data class Entry(val uri: Uri, val size: Long)

    override val filePaths: List<String> = files.keys.toList()

    override fun length(path: String): Long = files[path]?.size ?: 0L

    override fun readHead(path: String, limit: Int): ByteArray {
        val entry = files[path] ?: return ByteArray(0)
        return runCatching {
            resolver.openInputStream(entry.uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() < limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
    }

    override fun read(path: String, offset: Long, limit: Int): ByteArray {
        val entry = files[path] ?: return ByteArray(0)
        if (offset < 0) return ByteArray(0)
        return runCatching {
            resolver.openFileDescriptor(entry.uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    val count = minOf(limit.toLong(), (input.channel.size() - offset).coerceAtLeast(0L)).toInt()
                    if (count <= 0) return@use ByteArray(0)
                    input.channel.position(offset)
                    val buffer = java.nio.ByteBuffer.allocate(count)
                    while (buffer.hasRemaining() && input.channel.read(buffer) >= 0) Unit
                    buffer.array()
                }
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
    }

    override fun subtree(dir: String): GameTree {
        val prefix = "$dir/"
        return SafGameTree(
            resolver,
            pathPrefix + prefix,
            files.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) },
            dirPaths.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) },
        )
    }

    companion object {
        fun scan(resolver: ContentResolver, treeUri: Uri): SafGameTree {
            val files = mutableMapOf<String, Entry>()
            val dirs = mutableListOf<String>()
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
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(id)
                        val displayName = cursor.getString(name)
                        val path = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
                        if (cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            dirs += path
                            pending.add(documentId to path)
                        } else {
                            files[path] = Entry(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                            )
                        }
                    }
                }
            }
            return SafGameTree(resolver, "", files, dirs)
        }
    }
}

/** Evaluates the database's rules against a [GameTree] — the exact semantics droidtop's `EngineDetectRules` implements against a raw folder. */
object EngineRuleEvaluator {
    private const val FILE_HEAD_BYTES = 4096

    fun matches(rules: List<DetectRule>, tree: GameTree): Boolean =
        rules.any { rule -> rule.all.all { condition -> holds(condition, tree) } }

    private fun rootFiles(tree: GameTree): List<String> = tree.filePaths.filter { '/' !in it }

    private fun extension(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun holds(condition: DetectCondition, tree: GameTree): Boolean = when (condition) {
        is DetectCondition.DirExists -> condition.path in tree.dirPaths
        is DetectCondition.FileExists -> condition.path in tree.filePaths
        is DetectCondition.AnyFileNameContains -> rootFiles(tree).any { it.lowercase().contains(condition.value) }
        is DetectCondition.AnyFileExtension -> rootFiles(tree).any { extension(it) == condition.value }
        is DetectCondition.AnyFileExtensionDeep ->
            tree.filePaths.any { it.count { c -> c == '/' } <= condition.maxDepth && extension(it) == condition.value }
        is DetectCondition.AnyFileNameIn -> rootFiles(tree).any { it.lowercase() in condition.values }
        is DetectCondition.DirNamePrefixCount ->
            tree.dirPaths.count { '/' !in it && it.lowercase().startsWith(condition.prefix) } >= condition.min
        is DetectCondition.FileHeadRegex ->
            condition.path in tree.filePaths &&
                condition.regex.containsMatchIn(String(tree.readHead(condition.path, FILE_HEAD_BYTES), Charsets.ISO_8859_1))
        is DetectCondition.Builtin -> EngineBuiltinProbes.holds(condition.name, tree)
    }
}

/** The named byte-magic probes the database's `builtin` conditions reference. An unknown name fails its rule (soundness over optimism), same as droidtop. */
object EngineBuiltinProbes {
    private val GODOT_EXECUTABLE_SUFFIXES = setOf("exe", "x86_64", "x86", "")
    private val UNITY_PLAYER_FILENAMES = setOf("UnityPlayer.dll", "UnityPlayer.so", "UnityPlayer.dylib")
    private val SWF_SIGNATURES = setOf("FWS", "CWS", "ZWS")

    fun holds(name: String, tree: GameTree): Boolean = when (name) {
        "godot" -> isGodot(tree)
        "html" -> isHtml(tree)
        "unity" -> isUnity(tree)
        "swf" -> isSwf(tree)
        else -> false
    }

    private fun ext(path: String): String = path.substringAfterLast('.', "").lowercase()

    private fun rootFiles(tree: GameTree): List<String> = tree.filePaths.filter { '/' !in it }

    private fun isGodot(tree: GameTree): Boolean {
        val roots = rootFiles(tree)
        if (roots.any { ext(it) == "pck" }) return true
        return roots.any { ext(it) in GODOT_EXECUTABLE_SUFFIXES && hasEmbeddedPckTrailer(tree, it) }
    }

    /** The executable's last 12 bytes: an 8-byte little-endian offset then the ASCII magic GDPC — Godot's own embedded-pack export trailer. */
    private fun hasEmbeddedPckTrailer(tree: GameTree, path: String): Boolean {
        val size = tree.length(path)
        if (size < 12) return false
        val tail = tree.readTail(path, 12)
        if (tail.size != 12) return false
        if (String(tail, 8, 4, Charsets.US_ASCII) != "GDPC") return false
        var offset = 0L
        for (i in 7 downTo 0) offset = (offset shl 8) or (tail[i].toLong() and 0xFF)
        return offset in 1 until size
    }

    /**
     * A page at the root is an HTML game. This row sits last in the
     * registry: engines that also ship a root page (Godot and Unity web
     * exports) have had their say by the time it is asked.
     */
    private fun isHtml(tree: GameTree): Boolean = rootFiles(tree).any { ext(it) == "html" }

    private fun isUnity(tree: GameTree): Boolean =
        tree.filePaths.any { path ->
            path.count { it == '/' } <= 3 && path.substringAfterLast('/') in UNITY_PLAYER_FILENAMES
        }

    private fun isSwf(tree: GameTree): Boolean =
        rootFiles(tree).filter { ext(it) == "swf" }.any { swf ->
            val header = tree.readHead(swf, 4)
            header.size == 4 && String(header, 0, 3, Charsets.US_ASCII) in SWF_SIGNATURES
        }
}

/**
 * Loads the registry: a validated filesDir copy if one has been fetched,
 * else the bundled seed — the same bundled-seed + refresh-from-URL +
 * validate-before-replace model droidtop's `EnginesDatabase` uses, from
 * the same droidtop-platforms file, which is the whole point: one
 * detection update ships to both apps.
 */
object EngineRegistryStore {
    private const val DB_FILE_NAME = "engines-database.json"
    const val DEFAULT_URL = "https://raw.githubusercontent.com/droidtop/droidtop-platforms/main/$DB_FILE_NAME"

    @Volatile
    private var cached: List<EngineRow>? = null

    fun rows(context: Context): List<EngineRow> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val updated = File(context.filesDir, DB_FILE_NAME)
                .takeIf { it.isFile }
                ?.let { file -> runCatching { EngineRegistryParser.parse(file.readText()) }.getOrNull() }
                ?.takeIf { rows -> rows.any { it.detect.isNotEmpty() } }
            val loaded = updated
                ?: runCatching {
                    EngineRegistryParser.parse(
                        context.assets.open(DB_FILE_NAME).bufferedReader().use { it.readText() },
                    )
                }.getOrElse { emptyList() }
            cached = loaded
            return loaded
        }
    }

    /** Fetch + validate + atomically replace. Returns the row count; throws with a readable message on any failure. */
    fun update(context: Context, url: String = DEFAULT_URL): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val text = try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} from $url" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val parsed = EngineRegistryParser.parse(text)
        check(parsed.any { it.detect.isNotEmpty() }) { "Downloaded registry carries no detection rules" }

        val dest = File(context.filesDir, DB_FILE_NAME)
        val temp = File(context.filesDir, "$DB_FILE_NAME.downloading")
        temp.writeText(text)
        check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
            "Couldn't move the downloaded registry into place"
        }
        cached = null
        return parsed.size
    }
}
