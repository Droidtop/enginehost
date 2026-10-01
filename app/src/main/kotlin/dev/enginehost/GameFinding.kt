package dev.enginehost

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * What a game folder (or an archive that still needs unpacking) was built
 * for. A game can carry several builds, so a finding holds a mask of these
 * bits; filters ask "has a Windows build", not "is Windows".
 *
 * [PORTABLE] is engine data a plugin runs on Android (a Ren'Py or RPG
 * Maker MV project, whatever launcher .exe sits beside it). The others
 * describe native builds Enginehost cannot run itself but still lists, so
 * droidtop's Wine or Linux runners can pick them up.
 */
enum class BuildPlatform(val bit: Int) {
    PORTABLE(1),
    WINDOWS(2),
    LINUX(4),
    WEB(8),
    ARCHIVE(16),
    UNKNOWN(32),
    ;

    companion object {
        fun mask(vararg platforms: BuildPlatform): Int = platforms.fold(0) { mask, platform -> mask or platform.bit }

        fun of(mask: Int): List<BuildPlatform> = values().filter { mask and it.bit != 0 }
    }
}

/** How sure a finding is: a registry rule or a pack header is high, a lone big executable is not. */
enum class Confidence(val rank: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    ;

    companion object {
        fun ofRank(rank: Int): Confidence = values().firstOrNull { it.rank == rank } ?: LOW
    }
}

/** What this device can do with a game, as the library's status filter names it. */
enum class Support { RUNS_HERE, NEEDS_PLUGIN, NOT_SUPPORTED }

enum class FindingKind { FOLDER, ARCHIVE }

/**
 * One game the scanner found: an engine (or none, for a native build with no
 * known engine), the builds it ships, a confidence, and the best file to
 * launch. [path] is the game root folder, or the archive file itself.
 *
 * Deliberately only what filtering, sorting and listing need; the library
 * never reads a game's disk to draw a row.
 */
data class GameFinding(
    val path: String,
    val name: String,
    val kind: FindingKind,
    val engine: String?,
    val engineContext: String?,
    val engineVersion: String?,
    val requirements: Map<String, String>,
    /** False when the registry knows the engine but no Enginehost family runs it, and for native builds with no engine. */
    val hosted: Boolean,
    val platforms: Int,
    val confidence: Confidence,
    /** The file to start, relative to [path]; null for an archive or an engine whose launch needs none. */
    val launch: String?,
    /** x86_64, x86, arm64, arm or dos, from the launch file's own header. */
    val architecture: String?,
    val dotNet: Boolean,
    val signature: Long,
)

/** One entry of a directory listing with the facts a scan needs, read in one stat. */
data class DirEntry(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

object DirEntries {
    /**
     * Lists [dir] with one attribute read per child, or null when the
     * directory cannot be read at all. Shared storage is served through a
     * FUSE layer that has hung on file names legal on ext4 but not on exFAT,
     * so every failure here is a skipped entry or a null, never a throw.
     */
    fun list(dir: File): List<DirEntry>? = runCatching {
        val entries = ArrayList<DirEntry>()
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (child in stream) {
                val attributes = runCatching { Files.readAttributes(child, BasicFileAttributes::class.java) }
                    .getOrNull() ?: continue
                entries += DirEntry(
                    child.fileName.toString(),
                    attributes.isDirectory,
                    if (attributes.isDirectory) 0L else attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                )
            }
        }
        entries
    }.getOrNull()

    /**
     * A fingerprint of a directory's direct contents: names, kinds, file
     * sizes and modification times (a child directory's time moves when its
     * own entries do, so a change one level down shows up here too).
     * Order-independent, so listing order never matters, and linear in the
     * entry count. [salt] folds in whatever else decides the answer (the
     * scanner's version, the registry), so a changed rule set invalidates it.
     */
    fun signature(entries: List<DirEntry>, salt: Long): Long {
        var sum = salt
        for (entry in entries) {
            var x = entry.name.hashCode().toLong()
            x = x * 31 + (if (entry.isDirectory) 1 else 0)
            x = x * 31 + entry.size
            x = x * 31 + entry.modified
            sum += mix(x)
        }
        return sum xor (entries.size.toLong() shl 40)
    }

    private fun mix(value: Long): Long {
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
