package dev.enginehost

import java.io.File
import java.io.RandomAccessFile

/** What an executable's own first bytes say it is. */
enum class BinaryKind { PE, ELF, APPIMAGE, SCRIPT }

data class BinaryInfo(
    val kind: BinaryKind,
    /** x86_64, x86, arm64, arm, dos; null for a script or a machine nothing here names. */
    val architecture: String?,
    /** A PE image with a CLR header: managed code, which also needs a .NET runtime. */
    val dotNet: Boolean = false,
    /** A PE image built for the Windows GUI subsystem rather than a console. */
    val gui: Boolean = true,
)

/**
 * Header sniffing for native builds: a PE image (and whether it is 32 or
 * 64 bit, managed, GUI), an ELF binary and its machine, an AppImage, a
 * script. Only the first bytes and the headers they point at are read,
 * never a whole file; a short or hostile file answers null.
 */
object ExecutableProbe {
    private const val HEAD_BYTES = 64
    private const val SUBSYSTEM_OFFSET = 68
    private const val CLR_DIRECTORY = 14

    fun probe(source: ReadAt, fileName: String): BinaryInfo? {
        val head = source.read(0, HEAD_BYTES)
        if (head.size < 4) return null
        return when {
            head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte() -> pe(source)
            head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
                head[3] == 'F'.code.toByte() -> elf(head)
            head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte() -> BinaryInfo(BinaryKind.SCRIPT, null)
            fileName.lowercase().endsWith(".sh") && looksLikeText(head) -> BinaryInfo(BinaryKind.SCRIPT, null)
            else -> null
        }
    }

    private fun looksLikeText(head: ByteArray): Boolean = head.all { it.toInt() == 9 || it.toInt() == 10 || it.toInt() == 13 || it.toInt() in 32..126 }

    private fun pe(source: ReadAt): BinaryInfo {
        // An MZ file with no PE header behind it is a DOS program.
        val image = PeImage.parse(source) ?: return BinaryInfo(BinaryKind.PE, "dos", gui = false)
        val architecture = when (image.machine) {
            0x8664 -> "x86_64"
            0x014c -> "x86"
            0xAA64 -> "arm64"
            0x01c4 -> "arm"
            else -> null
        }
        val optional = image.optionalHeader
        val directories = image.dataDirectoriesAt
        val dotNet = directories != null && optional.size >= directories + (CLR_DIRECTORY + 1) * 8 &&
            optional.u32(directories + CLR_DIRECTORY * 8) != 0L
        // Subsystem 3 is a console program; everything else a person starts is a window.
        val gui = optional.size < SUBSYSTEM_OFFSET + 2 || optional.u16(SUBSYSTEM_OFFSET) != 3
        return BinaryInfo(BinaryKind.PE, architecture, dotNet, gui)
    }

    private fun elf(head: ByteArray): BinaryInfo? {
        if (head.size < 20) return null
        val little = head[5].toInt() == 1
        fun u16(at: Int): Int = if (little) head.u16(at) else ((head[at].toInt() and 0xff) shl 8) or (head[at + 1].toInt() and 0xff)
        // Executables and position-independent executables; a shared library is not a thing to start.
        val type = u16(16)
        if (type != 2 && type != 3) return null
        if (head[8] == 'A'.code.toByte() && head[9] == 'I'.code.toByte() && (head[10].toInt() == 1 || head[10].toInt() == 2)) {
            return BinaryInfo(BinaryKind.APPIMAGE, machine(u16(18)))
        }
        return BinaryInfo(BinaryKind.ELF, machine(u16(18)))
    }

    private fun machine(value: Int): String? = when (value) {
        62 -> "x86_64"
        3 -> "x86"
        183 -> "arm64"
        40 -> "arm"
        else -> null
    }
}

/** The best file to start in a folder that ships a native build, and what builds it holds. */
data class NativeBuild(
    val platforms: Int,
    val launch: String?,
    val architecture: String?,
    val dotNet: Boolean,
    /** The launch file is named like the folder: the strongest hint a lone executable is the game. */
    val matchesFolder: Boolean,
    val launchSize: Long,
    val executables: Int,
) {
    companion object {
        val NONE = NativeBuild(0, null, null, false, false, 0L, 0)
    }
}

/**
 * Finds a folder's launch candidates the way a person would: executables
 * and launcher scripts at its root (or in a conventional build subfolder),
 * minus installers, redistributables, crash handlers and uninstallers, the
 * rest ranked so the one named like the game wins. Each candidate costs one
 * 64-byte read; no more than [MAX_PROBES] are read per folder.
 */
object NativeBuilds {
    private const val MAX_PROBES = 12
    /** An extensionless file is a Linux binary only when it is big enough to be one; scripts carry .sh. */
    private const val MIN_EXTENSIONLESS_BYTES = 64 * 1024L

    private val BINARY_EXTENSIONS = listOf("exe", "appimage", "x86_64", "x64", "x86", "run", "elf", "bin", "sh")
    private val LAUNCH_SUBDIRS = setOf(
        "bin", "binaries", "game", "app", "win", "win32", "win64", "windows", "x86", "x64", "x86_64",
        "linux", "lin", "pc", "release",
    )

    /** Installers, redistributables, crash reporters, updaters, uninstallers and unrelated tools bundled in game folders. */
    private val NOISE = Regex(
        "^(unins\\w*|uninst\\w*|uninstall\\w*|setup\\w*|install\\w*|vc_?redist\\w*|dxsetup|dxwebsetup|dotnetfx\\w*|ndp\\d.*|" +
            "oalinst|directx\\w*|physx\\w*|xnafx\\w*|\\w*crash\\w*|bugsplat\\w*|notification_helper|\\w*updater\\w*|" +
            "7z\\w*|python\\w*|zsync\\w*|redist\\w*|dosbox\\w*|wine\\w*|uninstaller\\w*|installscript\\w*)$",
    )

    /** Real executables, but companions to the game rather than the game. */
    private val SIDE_TOOL = Regex(
        "^(config\\w*|configure\\w*|settings\\w*|launcher\\w*|patch\\w*|editor\\w*|register\\w*|activat\\w*|benchmark\\w*|readme\\w*)$",
    )

    private val REDIST_DIRECTORIES = setOf("redist", "_commonredist", "directx", "dotnet", "vcredist", "support", "crashpad", "__macosx")

    fun isNoise(fileName: String): Boolean = NOISE.matches(stem(fileName))

    fun isRedistDirectory(name: String): Boolean = name.lowercase() in REDIST_DIRECTORIES

    private fun stem(fileName: String): String = fileName.lowercase().substringBeforeLast('.', fileName.lowercase())

    private fun extension(fileName: String): String = fileName.lowercase().substringAfterLast('.', "")

    /** Letters and digits only, so "Game-Name_1.0" and "game name 1 0" compare equal. */
    fun normalize(name: String): String = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private class Candidate(val path: String, val info: BinaryInfo, val size: Long, val score: Int, val matches: Boolean)

    fun read(dir: File, entries: List<DirEntry>, folderName: String): NativeBuild {
        val candidates = ArrayList<Candidate>(probe(dir, "", entries, folderName, 0))
        if (candidates.isEmpty()) {
            for (sub in entries) {
                if (!sub.isDirectory || sub.name.lowercase() !in LAUNCH_SUBDIRS || isRedistDirectory(sub.name)) continue
                val folder = File(dir, sub.name)
                val inner = DirEntries.list(folder) ?: continue
                candidates += probe(folder, sub.name + "/", inner, folderName, 5)
            }
        }
        if (candidates.isEmpty()) return NativeBuild.NONE
        var platforms = 0
        for (candidate in candidates) {
            platforms = platforms or if (candidate.info.kind == BinaryKind.PE) BuildPlatform.WINDOWS.bit else BuildPlatform.LINUX.bit
        }
        val best = candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.path }).first()
        return NativeBuild(
            platforms,
            best.path,
            best.info.architecture,
            best.info.dotNet,
            candidates.any { it.matches },
            best.size,
            candidates.size,
        )
    }

    private fun probe(dir: File, prefix: String, entries: List<DirEntry>, folderName: String, depthPenalty: Int): List<Candidate> {
        val folderKey = normalize(folderName)
        val eligible = entries
            .filter { !it.isDirectory && !it.name.startsWith(".") && !isNoise(it.name) && eligible(it) }
            .sortedWith(compareBy<DirEntry>({ order(extension(it.name)) }, { it.name }))
            .take(MAX_PROBES)
        val found = ArrayList<Candidate>()
        for (entry in eligible) {
            val info = runCatching {
                RandomAccessFile(File(dir, entry.name), "r").use { ExecutableProbe.probe(RandomAccessFileReadAt(it), entry.name) }
            }.getOrNull() ?: continue
            val key = normalize(stem(entry.name))
            val matches = key.isNotEmpty() && folderKey.isNotEmpty() && (key == folderKey || folderKey.contains(key) && key.length >= 4)
            var score = when (info.kind) {
                BinaryKind.APPIMAGE -> 40
                BinaryKind.PE -> if (info.gui) 30 else 20
                BinaryKind.ELF -> 30
                BinaryKind.SCRIPT -> 10
            }
            if (matches) score += 40
            if (SIDE_TOOL.matches(stem(entry.name))) score -= 25
            if (info.architecture == "dos") score -= 10
            score += minOf(20, 63 - java.lang.Long.numberOfLeadingZeros(maxOf(entry.size, 1L)))
            score -= depthPenalty
            found += Candidate(prefix + entry.name, info, entry.size, score, matches)
        }
        return found
    }

    private fun eligible(entry: DirEntry): Boolean {
        val ext = extension(entry.name)
        return ext in BINARY_EXTENSIONS || (ext.isEmpty() && '.' !in entry.name && entry.size >= MIN_EXTENSIONLESS_BYTES)
    }

    private fun order(ext: String): Int = BINARY_EXTENSIONS.indexOf(ext).let { if (it < 0) BINARY_EXTENSIONS.size else it }
}

/** Archives that still need unpacking: recognised by extension and magic, never opened. */
object ArchiveFiles {
    /** Smaller archives are patches, saves and asset packs, not games. */
    const val MIN_BYTES = 8L * 1024 * 1024

    private val EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst")
    private val LATER_PART = Regex("\\.part0*(?:[2-9]|\\d{2,})\\.rar$|\\.r\\d\\d$|\\.(?:7z|zip|rar)\\.0*(?:[2-9]|\\d{2,})$")
    private val FIRST_VOLUME = Regex("[.](?:7z|zip|rar)[.]0*1$")
    private val TRAILING_NOISE = listOf(
        "windows", "win64", "win32", "win", "linux", "pc", "x64", "x86", "64bit", "32bit", "portable", "full", "release", "final", "mac", "all",
    )

    /** An archive worth listing: right extension, big enough, the first volume of a split set. */
    fun candidate(entry: DirEntry): Boolean {
        if (entry.isDirectory || entry.size < MIN_BYTES) return false
        val lower = entry.name.lowercase()
        if (LATER_PART.containsMatchIn(lower)) return false
        return lower.substringAfterLast('.', "") in EXTENSIONS || Regex("\\.(?:7z|zip|rar)\\.0*1$").containsMatchIn(lower)
    }

    /** The compression container's magic, read from the first bytes (a tar's sits at 257). */
    fun hasMagic(source: ReadAt): Boolean {
        val head = source.read(0, 8)
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            (head[2].toInt() == 3 || head[2].toInt() == 5) && (head[3].toInt() == 4 || head[3].toInt() == 6)
        ) return true
        if (head.size >= 6 && String(head, 0, 4, Charsets.ISO_8859_1) == "Rar!" && head[4].toInt() == 0x1a) return true
        if (head.size >= 6 && head[0] == '7'.code.toByte() && head[1] == 'z'.code.toByte() &&
            head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte()
        ) return true
        if (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return true
        if (head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "BZh") return true
        if (head.size >= 6 && head[0] == 0xFD.toByte() && String(head, 1, 4, Charsets.ISO_8859_1) == "7zXZ") return true
        if (head.size >= 4 && head[0] == 0x28.toByte() && head[1] == 0xB5.toByte() && head[2] == 0x2F.toByte() && head[3] == 0xFD.toByte()) return true
        val tar = source.read(257, 5)
        return tar.size == 5 && String(tar, Charsets.ISO_8859_1) == "ustar"
    }

    /** The archive's name without its extensions, and without a split-volume marker. */
    fun stem(fileName: String): String {
        var name = fileName
        for (suffix in listOf(".001", ".part1.rar", ".part01.rar", ".part001.rar")) {
            if (name.lowercase().endsWith(suffix)) name = name.dropLast(suffix.length)
        }
        while (true) {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in EXTENSIONS || ext == "tar") name = name.substringBeforeLast('.') else break
        }
        return name
    }

    /** A name reduced to what two copies of the same release share, platform tags dropped. */
    fun releaseKey(name: String): String {
        var key = NativeBuilds.normalize(name)
        var trimmed = true
        while (trimmed) {
            trimmed = false
            for (tag in TRAILING_NOISE) {
                if (key.length > tag.length && key.endsWith(tag)) {
                    key = key.dropLast(tag.length)
                    trimmed = true
                }
            }
        }
        return key
    }
}
