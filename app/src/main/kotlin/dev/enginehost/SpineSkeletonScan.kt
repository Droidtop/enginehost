package dev.enginehost

/**
 * Which Spine export line a Godot game's own skeleton assets are, read
 * out of its pack without launching anything.
 *
 * WHY THIS IS NOT A CONFIG FIELD ALONE. A game whose skeletons were
 * exported from a Spine version its runtime does not accept fails in the
 * worst possible way: spine-godot's `SpineSkeletonFileResourceFormatLoader`
 * (spine-runtimes 4.2, `spine-godot/spine_godot/SpineSkeletonFileResource.cpp`)
 * calls `load_from_file`, throws away its `ERR_INVALID_DATA`, sets
 * `*error = OK` and returns an empty resource. `SpineSkeletonDataResource`
 * then returns early on `EMPTY(json) && EMPTY(binary)` with no message at
 * all. Nothing is logged and nothing is drawn -- the black screen this
 * whole area exists to prevent. Selecting the right runtime before launch
 * is the only place the mismatch is still visible.
 *
 * WHAT COUNTS AS A MATCH. Every spine-runtimes loader gates on the
 * exported version being a prefix match on its own major.minor and
 * nothing finer: `SkeletonBinary.cpp` and `SkeletonJson.cpp` both do
 * `if (!skeletonData->_version.startsWith(SPINE_VERSION_STRING))`, where
 * `Version.h` defines `SPINE_VERSION_STRING` as "4.2". spine-godot repeats
 * the same test in `checkVersion`. So a whole 4.2.x line is one
 * compatibility unit -- Goodbye Eternity's own skeletons range over
 * 4.2.38-4.2.43 and load on the same runtime -- and the requirement this
 * derives is deliberately the major.minor line, never the patch.
 *
 * WHERE THE VERSION IS. In a 4.x binary skeleton the first 8 bytes are
 * the skeleton hash and the version string follows as a varint length
 * (string length + 1) then that many bytes minus the terminator; verified
 * against spine-runtimes' own exports on the 4.0, 4.1, 4.2 and 4.3
 * branches. Before 4.0 the hash was itself a length-prefixed string and
 * the version came after it, which [legacyBinaryVersion] handles so a 3.x
 * game states a requirement no bundle can meet rather than none at all. A
 * JSON skeleton carries it as `"spine"` inside its `skeleton` object.
 */
object SpineSkeletonScan {
    /** The component name a Godot engine bundle advertises Spine under. */
    const val COMPONENT = "spine-godot"

    private const val MAX_SKELETONS = 32
    private const val BINARY_HEAD_BYTES = 128
    private const val JSON_HEAD_BYTES = 8 * 1024
    private const val MAX_VERSION_BYTES = 64

    private val BINARY_SUFFIXES = listOf(".spskel", ".skel")
    private val JSON_SUFFIXES = listOf(".spjson", ".spine-json")
    private val VERSION_LINE = Regex("^(\\d+)\\.(\\d+)(?:[.\\-].*)?$")
    private val JSON_SPINE_FIELD = Regex("\"spine\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * What the game's skeletons say. [lines] holds the distinct
     * major.minor compatibility units found; a game with more than one
     * cannot be served by any single runtime, and the caller must not
     * pick one of them.
     */
    data class Result(
        val lines: Set<String>,
        val exporterVersions: Set<String>,
        val skeletonsRead: Int,
    ) {
        /** The single line every skeleton read belongs to, or null. */
        val requiredLine: String? get() = lines.singleOrNull()
    }

    /** Scan the pack [header] describes for skeleton assets. */
    fun scan(tree: GameTree, path: String, header: GodotPack.Header): Result {
        val entries = GodotPack.entries(tree, path, header, MAX_SKELETONS) { entry ->
            val lower = entry.lowercase()
            BINARY_SUFFIXES.any(lower::endsWith) || JSON_SUFFIXES.any(lower::endsWith)
        }
        val exporterVersions = mutableSetOf<String>()
        var read = 0
        for (entry in entries) {
            if (entry.encrypted || entry.size <= 0) continue
            val json = JSON_SUFFIXES.any { entry.path.lowercase().endsWith(it) }
            val window = if (json) JSON_HEAD_BYTES else BINARY_HEAD_BYTES
            val head = tree.read(path, entry.offset, minOf(entry.size, window.toLong()).toInt())
            if (head.isEmpty()) continue
            val version = if (json) jsonVersion(head) else binaryVersion(head)
            if (version != null) {
                exporterVersions += version
                read++
            }
        }
        return Result(exporterVersions.mapNotNull(::line).toSet(), exporterVersions, read)
    }

    /**
     * The requirement map to merge into a game's config: empty unless
     * every skeleton read agrees on one compatibility line.
     */
    fun requirement(tree: GameTree, path: String, header: GodotPack.Header): Map<String, String> {
        val line = scan(tree, path, header).requiredLine ?: return emptyMap()
        return mapOf(COMPONENT to line)
    }

    /**
     * The requirement for a whole game folder: [preferred] (a config's
     * `execFile`) is probed first because it names the carrier the
     * plugin will actually hand to Godot, then the usual candidates.
     */
    fun requirementFor(tree: GameTree, preferred: String?): Map<String, String> {
        val candidates = (listOfNotNull(preferred?.takeIf { it.isNotBlank() }) + GodotPack.candidates(tree)).distinct()
        for (candidate in candidates) {
            val header = GodotPack.open(tree, candidate) ?: continue
            val requirement = requirement(tree, candidate, header)
            if (requirement.isNotEmpty()) return requirement
        }
        return emptyMap()
    }

    /** "4.2.38" and "4.3.75-beta" both reduce to their major.minor line. */
    fun line(exporterVersion: String): String? =
        VERSION_LINE.matchEntire(exporterVersion.trim())?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }

    private fun binaryVersion(head: ByteArray): String? =
        versionAt(head, 8) ?: legacyBinaryVersion(head)

    /** Pre-4.0: a length-prefixed hash string, then the version string. */
    private fun legacyBinaryVersion(head: ByteArray): String? {
        val hash = varint(head, 0) ?: return null
        val afterHash = hash.second + (hash.first - 1)
        if (hash.first <= 0 || afterHash > head.size) return null
        return versionAt(head, afterHash)
    }

    private fun versionAt(head: ByteArray, offset: Int): String? {
        val (length, start) = varint(head, offset) ?: return null
        if (length <= 1 || length > MAX_VERSION_BYTES) return null
        val end = start + length - 1
        if (end > head.size) return null
        val text = String(head, start, length - 1, Charsets.US_ASCII)
        return text.takeIf { VERSION_LINE.matches(it) }
    }

    /** A Spine varint and the offset just past it, or null past the end. */
    private fun varint(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        var index = offset
        var value = 0
        var shift = 0
        while (shift <= 28) {
            if (index >= bytes.size) return null
            val byte = bytes[index].toInt() and 0xFF
            index++
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private fun jsonVersion(head: ByteArray): String? =
        JSON_SPINE_FIELD.find(String(head, Charsets.UTF_8))?.groupValues?.get(1)
            ?.takeIf { VERSION_LINE.matches(it) }
}
