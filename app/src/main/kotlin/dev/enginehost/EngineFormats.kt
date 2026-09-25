package dev.enginehost

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/*
 * Readers for the engine facts a game's own files state, for the engines
 * whose files say it somewhere other than a text line: which Unity player
 * a game ships and for what machine, which AGS editor compiled a game's
 * data, which LOVE version a game was written for, which GameMaker
 * bytecode a data.win carries. [EngineDetector] calls them as enrichment,
 * after the registry has classified the folder; none of them can change
 * which engine a folder is. Every reader tolerates a short, truncated or
 * hostile file by answering null: these bytes come off a folder the person
 * controls.
 */

private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

private fun rootFiles(tree: GameTree): List<String> = tree.filePaths.filter { '/' !in it }

private fun ByteArray.indexOf(pattern: ByteArray, from: Int = 0): Int {
    if (pattern.isEmpty() || size < pattern.size) return -1
    outer@ for (i in from..size - pattern.size) {
        for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}

/**
 * The machine a native executable or library is built for, from its own
 * header: the COFF `Machine` field of a PE image, or `e_machine` of an ELF
 * file. Named the way Android names ABIs, since that is the question it
 * answers: whether this device could load it.
 */
internal object ExecutableArchitecture {
    fun of(tree: GameTree, path: String): String? {
        val head = tree.readHead(path, 20)
        if (head.size >= 2 && head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()) {
            val image = PeImage.parse { offset, size -> tree.read(path, offset, size) } ?: return null
            return when (image.machine) {
                0x8664 -> "x86_64"
                0x014c -> "x86"
                0xAA64 -> "arm64"
                0x01c4 -> "arm"
                else -> null
            }
        }
        if (head.size >= 20 && head[0] == 0x7f.toByte() && String(head, 1, 3, Charsets.US_ASCII) == "ELF") {
            // EI_DATA 1 is little-endian, the only order these targets use.
            if (head[5].toInt() != 1) return null
            return when (little(head).getShort(18).toInt() and 0xffff) {
                62 -> "x86_64"
                3 -> "x86"
                183 -> "arm64"
                40 -> "arm"
                else -> null
            }
        }
        return null
    }
}

/**
 * A Unity player build. Unity writes its scripting backend into the layout:
 * IL2CPP builds carry `GameAssembly.dll` beside the player and metadata
 * under `<Name>_Data/il2cpp_data/`; Mono builds carry the managed
 * assemblies under `<Name>_Data/Managed/`. The Unity version is written as
 * a plain string near the start of every serialized file, so the first
 * bytes of `globalgamemanagers` (5.x and later), `data.unity3d` (a build
 * with compressed data, whose UnityFS header names it too) or `mainData`
 * (4.x and earlier) state it.
 */
internal object UnityBuild {
    data class Facts(val scripting: String?, val architecture: String?, val version: String?)

    private val PLAYERS = setOf("unityplayer.dll", "unityplayer.so")
    private val DATA_FILES = listOf("globalgamemanagers", "data.unity3d", "maindata")
    private val VERSION = Regex("(?<![0-9.])(\\d{1,4}\\.\\d{1,2}\\.\\d{1,3}[abfpx]\\d{1,3}(?:c\\d{1,3})?)(?![0-9.])")

    fun read(tree: GameTree): Facts {
        val paths = tree.filePaths.map { it to it.lowercase() }
        val scripting = when {
            paths.any { (_, lower) -> lower.substringAfterLast('/') == "gameassembly.dll" || "_data/il2cpp_data/" in lower } -> "il2cpp"
            paths.any { (_, lower) -> "_data/managed/" in lower } -> "mono"
            else -> null
        }
        val player = paths.filter { (_, lower) -> lower.substringAfterLast('/') in PLAYERS }
            .minByOrNull { (path, _) -> path.count { it == '/' } }?.first
        val version = DATA_FILES.firstNotNullOfOrNull { name ->
            paths.filter { (_, lower) -> lower.endsWith("_data/$name") }
                .minByOrNull { (path, _) -> path.count { it == '/' } }
                ?.let { (path, _) -> VERSION.find(String(tree.readHead(path, 512), Charsets.ISO_8859_1))?.groupValues?.get(1) }
        }
        return Facts(scripting, player?.let { ExecutableArchitecture.of(tree, it) }, version)
    }
}

/**
 * An Adventure Game Studio game's main data, and the editor version that
 * compiled it. Read the way the engine reads it (ags Common/data/multifilelib.cpp,
 * Common/game/main_game_file.cpp, 3.6.3):
 *
 * The game's assets are a CLIB library. A 3.5+ game ships it as `<name>.ags`,
 * starting with `CLIB`; an older game appends it to its own `.exe`, which then
 * ends in the 12-byte `CLIB\x01\x02\x03\x04SIGE` tail preceded by the library's
 * offset, 64-bit since 3.5 and 32-bit before. The main game data inside it
 * starts with `Adventure Creator Game File v2`, a 32-bit data format version
 * and, from format 12 (2.30), the compiling editor's version as a 32-bit
 * length and that many characters ("3.6.1.14").
 */
internal object AgsGameData {
    data class Found(val path: String, val compiledWith: String?)

    private val HEAD_SIGNATURE = "CLIB".toByteArray(Charsets.US_ASCII)
    private val TAIL_SIGNATURE = "CLIB\u0001\u0002\u0003\u0004SIGE".toByteArray(Charsets.US_ASCII)
    private val GAME_SIGNATURE = "Adventure Creator Game File v2".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_230 = 12
    private const val SEARCH_BYTES = 4 * 1024 * 1024
    private const val MAX_EXECUTABLES = 4

    fun find(tree: GameTree): Found? {
        val root = rootFiles(tree).sorted()
        val candidates = root.filter { it.lowercase().endsWith(".ags") } +
            root.filter { it.equals("ac2game.dat", ignoreCase = true) } +
            root.filter { it.lowercase().endsWith(".exe") && !it.equals("winsetup.exe", ignoreCase = true) }
                .take(MAX_EXECUTABLES)
        for (path in candidates) {
            val start = libraryStart(tree, path) ?: continue
            return Found(path, compiledWith(tree, path, start))
        }
        return null
    }

    private fun libraryStart(tree: GameTree, path: String): Long? {
        if (tree.readHead(path, 4).contentEquals(HEAD_SIGNATURE)) return 0L
        val size = tree.length(path)
        if (size < TAIL_SIGNATURE.size + 8) return null
        if (!tree.read(path, size - TAIL_SIGNATURE.size, TAIL_SIGNATURE.size).contentEquals(TAIL_SIGNATURE)) return null
        val offsets = tree.read(path, size - TAIL_SIGNATURE.size - 8, 8)
        if (offsets.size != 8) return null
        val wide = little(offsets).long
        val narrow = little(offsets).getInt(4).toLong() and 0xffffffffL
        return listOf(wide, narrow).firstOrNull { offset ->
            offset in 1 until size - TAIL_SIGNATURE.size && tree.read(path, offset, 4).contentEquals(HEAD_SIGNATURE)
        }
    }

    private fun compiledWith(tree: GameTree, path: String, start: Long): String? {
        val window = tree.read(path, start, SEARCH_BYTES)
        val at = window.indexOf(GAME_SIGNATURE)
        if (at < 0 || at + GAME_SIGNATURE.size + 8 > window.size) return null
        val fields = little(window)
        val formatAt = at + GAME_SIGNATURE.size
        if (fields.getInt(formatAt) < FORMAT_230) return null
        val length = fields.getInt(formatAt + 4)
        if (length !in 1..32 || formatAt + 8 + length > window.size) return null
        val text = String(window, formatAt + 8, length, Charsets.US_ASCII).trimEnd('\u0000')
        return text.takeIf { it.matches(Regex("\\d+(?:\\.\\d+)+")) }
    }
}

/**
 * The entries of a zip archive, including one appended to an executable:
 * a fused LOVE game is `love.exe` with the game's .love zip concatenated
 * after it, so the zip's own offsets are relative to where the zip starts,
 * which is found from the end-of-central-directory record (the difference
 * between where the directory is and where it says it is).
 */
internal object ZipArchive {
    private const val EOCD = 0x06054b50
    private const val CENTRAL = 0x02014b50
    private const val LOCAL = 0x04034b50
    private const val EOCD_BYTES = 22
    private const val MAX_COMMENT = 0xffff
    private const val MAX_DIRECTORY = 4 * 1024 * 1024

    private class Directory(val base: Long, val bytes: ByteArray)

    private fun directory(tree: GameTree, path: String): Directory? {
        val size = tree.length(path)
        if (size < EOCD_BYTES) return null
        val tailSize = minOf(size, (EOCD_BYTES + MAX_COMMENT).toLong()).toInt()
        val tail = tree.read(path, size - tailSize, tailSize)
        val buffer = little(tail)
        var at = tail.size - EOCD_BYTES
        while (at >= 0 && buffer.getInt(at) != EOCD) at--
        if (at < 0) return null
        val directorySize = buffer.getInt(at + 12).toLong() and 0xffffffffL
        val directoryOffset = buffer.getInt(at + 16).toLong() and 0xffffffffL
        val eocdPosition = size - tailSize + at
        val base = eocdPosition - directorySize - directoryOffset
        if (base < 0 || directorySize > MAX_DIRECTORY) return null
        val bytes = tree.read(path, base + directoryOffset, directorySize.toInt())
        return Directory(base, bytes).takeIf { bytes.size.toLong() == directorySize }
    }

    /** Whether [path] ends in a zip archive at all. */
    fun present(tree: GameTree, path: String): Boolean = directory(tree, path) != null

    /** The bytes of entry [name], at most [limit] of them, or null when it is absent or unreadable. */
    fun entry(tree: GameTree, path: String, name: String, limit: Int): ByteArray? {
        val directory = directory(tree, path) ?: return null
        val buffer = little(directory.bytes)
        var at = 0
        while (at + 46 <= directory.bytes.size && buffer.getInt(at) == CENTRAL) {
            val method = buffer.getShort(at + 10).toInt() and 0xffff
            val compressed = buffer.getInt(at + 20).toLong() and 0xffffffffL
            val nameLength = buffer.getShort(at + 28).toInt() and 0xffff
            val extraLength = buffer.getShort(at + 30).toInt() and 0xffff
            val commentLength = buffer.getShort(at + 32).toInt() and 0xffff
            val localOffset = buffer.getInt(at + 42).toLong() and 0xffffffffL
            if (at + 46 + nameLength > directory.bytes.size) return null
            val entryName = String(directory.bytes, at + 46, nameLength, Charsets.UTF_8)
            if (entryName == name) return data(tree, path, directory.base + localOffset, method, compressed, limit)
            at += 46 + nameLength + extraLength + commentLength
        }
        return null
    }

    private fun data(tree: GameTree, path: String, local: Long, method: Int, compressed: Long, limit: Int): ByteArray? {
        val header = tree.read(path, local, 30)
        if (header.size < 30 || little(header).getInt(0) != LOCAL) return null
        val start = local + 30 + (little(header).getShort(26).toInt() and 0xffff) + (little(header).getShort(28).toInt() and 0xffff)
        val raw = tree.read(path, start, minOf(compressed, MAX_DIRECTORY.toLong()).toInt())
        return when (method) {
            0 -> raw.copyOf(minOf(raw.size, limit))
            8 -> runCatching {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(raw)
                    val out = ByteArray(limit)
                    var count = 0
                    while (count < limit && !inflater.finished() && !inflater.needsInput()) {
                        val n = inflater.inflate(out, count, limit - count)
                        if (n == 0 && (inflater.needsDictionary() || inflater.needsInput())) break
                        count += n
                    }
                    out.copyOf(count)
                } finally {
                    inflater.end()
                }
            }.getOrNull()
            else -> null
        }
    }
}

/**
 * A LOVE game, in whichever of its shipped shapes (https://love2d.org/wiki/Game_Distribution):
 * an unpacked folder with main.lua at its root, a `.love` zip, or a fused
 * executable (love.exe with the .love appended). The version it was
 * written for is what its conf.lua sets `t.version` to
 * (https://love2d.org/wiki/Config_Files); LOVE itself compares that value
 * with its own version at startup, so it is the game's own statement of
 * its runtime line.
 */
internal object LoveGame {
    data class Found(val execFile: String?, val version: String?)

    private const val CONF_BYTES = 64 * 1024
    private const val MAX_EXECUTABLES = 4
    private val CONF_FUNCTION = Regex("function\\s+love\\.conf\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)")

    fun find(tree: GameTree): Found {
        val root = rootFiles(tree).sorted()
        if (root.any { it == "main.lua" }) {
            val conf = root.firstOrNull { it == "conf.lua" }
            return Found(null, conf?.let { version(String(tree.readHead(it, CONF_BYTES), Charsets.UTF_8)) })
        }
        val archives = root.filter { it.lowercase().endsWith(".love") } +
            root.filter { it.lowercase().endsWith(".exe") }.take(MAX_EXECUTABLES)
        for (archive in archives) {
            if (!ZipArchive.present(tree, archive)) continue
            val conf = ZipArchive.entry(tree, archive, "conf.lua", CONF_BYTES)
            return Found(archive, conf?.let { version(String(it, Charsets.UTF_8)) })
        }
        return Found(null, null)
    }

    internal fun version(conf: String): String? {
        val table = CONF_FUNCTION.find(conf)?.groupValues?.get(1) ?: return null
        return Regex("\\b${Regex.escape(table)}\\.version\\s*=\\s*[\"'](\\d+\\.\\d+(?:\\.\\d+)?)[\"']")
            .find(conf)?.groupValues?.get(1)
    }
}

/**
 * A GameMaker runner's data file (data.win, game.unx, game.ios): an IFF
 * `FORM` whose first chunk, `GEN8`, holds the bytecode version at byte 1 and
 * the IDE version as four 32-bit words at byte 44 of the chunk, the layout
 * UndertaleModTool reads (UndertaleModLib/Models/UndertaleGeneralInfo.cs).
 * GameMaker Studio 2 writes 2.0.0.0 there whatever the IDE release, so the
 * bytecode version is the finer fact.
 */
internal object GameMakerData {
    data class Facts(val path: String, val bytecode: Int, val ideVersion: String)

    private val NAMES = setOf("data.win", "game.unx", "game.ios")

    fun read(tree: GameTree): Facts? {
        val path = rootFiles(tree).sorted().firstOrNull { it.lowercase() in NAMES } ?: return null
        val head = tree.readHead(path, 80)
        if (head.size < 80) return null
        if (String(head, 0, 4, Charsets.US_ASCII) != "FORM" || String(head, 8, 4, Charsets.US_ASCII) != "GEN8") return null
        val chunk = little(head)
        val version = (0 until 4).map { chunk.getInt(16 + 44 + it * 4) }
        if (version.any { it !in 0..99_999 }) return null
        return Facts(path, head[17].toInt() and 0xff, version.joinToString("."))
    }
}
