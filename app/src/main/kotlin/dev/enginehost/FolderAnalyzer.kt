package dev.enginehost

import java.io.File

/**
 * Cheap, name-only screening over one directory listing, derived from the
 * registry itself so it can never lag behind it: for every rule it keeps
 * ONE condition the rule cannot match without (the most selective one it
 * has), so a folder passes only when some rule's necessary condition holds
 * at its root. It may say yes to a non-game (detection then says no and the
 * walk goes on); its only job is to keep the full detector off folders with
 * no engine evidence at all.
 */
class RegistryScreen(rows: List<EngineRow>) {
    private val names = HashSet<String>()
    private val extensions = HashSet<String>()
    private val contains = ArrayList<String>()
    private val prefixes = ArrayList<String>()
    private val directorySuffixes = ArrayList<String>()
    private var deepRules = false
    private var bareBinaries = false

    init {
        for (row in rows) {
            for (rule in row.detect) pick(rule)
        }
    }

    private fun top(path: String): String = path.substringBefore('/').lowercase()

    private fun pick(rule: DetectRule) {
        val conditions = rule.all
        conditions.filterIsInstance<DetectCondition.AnyFileNameIn>().firstOrNull()?.let { names += it.values; return }
        conditions.filterIsInstance<DetectCondition.FileExists>().firstOrNull { '/' !in it.path }?.let { names += top(it.path); return }
        conditions.filterIsInstance<DetectCondition.AnyFileExtension>().firstOrNull()?.let { extensions += it.value; return }
        conditions.filterIsInstance<DetectCondition.AnyFileNameContains>().firstOrNull()?.let { contains += it.value; return }
        conditions.filterIsInstance<DetectCondition.DirNamePrefixCount>().firstOrNull()?.let { prefixes += it.prefix; return }
        conditions.filterIsInstance<DetectCondition.FileHeadRegex>().firstOrNull { '/' !in it.path }?.let { names += top(it.path); return }
        conditions.filterIsInstance<DetectCondition.Builtin>().firstOrNull()?.let { builtin(it.name); return }
        conditions.filterIsInstance<DetectCondition.DirExists>().firstOrNull { '/' !in it.path }?.let { names += top(it.path); return }
        conditions.filterIsInstance<DetectCondition.FileExists>().firstOrNull()?.let { names += top(it.path); return }
        conditions.filterIsInstance<DetectCondition.FileHeadRegex>().firstOrNull()?.let { names += top(it.path); return }
        conditions.filterIsInstance<DetectCondition.DirExists>().firstOrNull()?.let { names += top(it.path); return }
        if (conditions.any { it is DetectCondition.AnyFileExtensionDeep }) {
            conditions.filterIsInstance<DetectCondition.AnyFileExtensionDeep>().forEach { extensions += it.value }
            deepRules = true
        }
    }

    /** The registry's named probes, each reduced to the files it can only be true for. */
    private fun builtin(name: String) {
        when (name) {
            // A pack beside the binary, or a binary carrying one: any executable, or a big extensionless file.
            "godot" -> {
                extensions += listOf("pck", "exe", "x86_64", "x86_32", "x86", "arm64", "bin")
                bareBinaries = true
            }
            "html" -> extensions += listOf("html", "htm")
            "unity" -> {
                names += listOf("unityplayer.dll", "unityplayer.so", "unityplayer.dylib")
                directorySuffixes += "_data"
            }
            "swf" -> extensions += "swf"
        }
    }

    fun matches(entries: List<DirEntry>): Boolean {
        for (entry in entries) {
            val lower = entry.name.lowercase()
            if (entry.isDirectory) {
                if (lower in names || prefixes.any { lower.startsWith(it) } || directorySuffixes.any { lower.endsWith(it) }) return true
                if (deepRules && lower in CONVENTIONAL_DIRECTORIES) return true
            } else {
                if (lower in names || contains.any { lower.contains(it) }) return true
                val dot = lower.lastIndexOf('.')
                if (dot >= 0 && lower.substring(dot + 1) in extensions) return true
                if (bareBinaries && dot < 0 && entry.size >= BARE_BINARY_BYTES) return true
            }
        }
        return false
    }

    private companion object {
        const val BARE_BINARY_BYTES = 1024L * 1024
        /** Where a deep-extension rule's files conventionally sit, since the screen only sees one directory. */
        val CONVENTIONAL_DIRECTORIES = setOf("game", "data", "www", "resources", "assets", "content", "scenario", "bin")
    }
}

/**
 * Decides whether one folder is a game root and what it is. Classification
 * is [EngineDetector]'s alone (the shared registry), so scanning adds no
 * second engine taxonomy; this adds the platform layer around it: for a
 * recognised engine Enginehost cannot run, which native builds it ships;
 * for a folder the registry does not know, whether its executables make it
 * a game at all.
 */
class FolderAnalyzer(private val rows: List<EngineRow>) {
    private val screen = RegistryScreen(rows)

    /** Folds the rule set into cached signatures, so a changed registry rescans what it classified. */
    val salt: Long = rows.fold(SCAN_VERSION) { hash, row -> hash * 31 + row.id.hashCode() + row.detect.size }

    fun analyze(dir: File, entries: List<DirEntry>, signature: Long): GameFinding? {
        val registry = if (screen.matches(entries)) runCatching { EngineDetector.detect(rows, dir) }.getOrNull() else null
        return if (registry != null) fromRegistry(dir, entries, registry, signature) else fromNative(dir, entries, signature)
    }

    private fun fromRegistry(dir: File, entries: List<DirEntry>, det: EngineDetection, signature: Long): GameFinding {
        val native = if (det.hosted) NativeBuild.NONE else nativeIncludingWrapper(dir, entries)
        val platforms = when {
            det.hosted && det.engine == "html" -> BuildPlatform.mask(BuildPlatform.PORTABLE, BuildPlatform.WEB)
            det.hosted -> BuildPlatform.PORTABLE.bit
            native.platforms != 0 -> native.platforms
            else -> BuildPlatform.UNKNOWN.bit
        }
        // A bare HTML page is weak evidence; a Twine story states itself.
        val confidence = if (det.engine == "html" && det.title == null) Confidence.LOW else Confidence.HIGH
        return GameFinding(
            path = dir.path,
            name = det.title ?: dir.name.ifBlank { dir.path },
            kind = FindingKind.FOLDER,
            engine = det.engine,
            engineContext = det.engineContext,
            engineVersion = det.engineVersion,
            requirements = det.runtimeRequirements,
            hosted = det.hosted,
            platforms = platforms,
            confidence = confidence,
            launch = det.execFile ?: native.launch,
            architecture = det.architecture ?: native.architecture,
            dotNet = native.dotNet,
            signature = signature,
        )
    }

    /** A recognised game one wrapper folder down still ships its builds there. */
    private fun nativeIncludingWrapper(dir: File, entries: List<DirEntry>): NativeBuild {
        val here = NativeBuilds.read(dir, entries, dir.name)
        if (here.platforms != 0) return here
        var looked = 0
        for (sub in entries.filter { it.isDirectory && !it.name.startsWith(".") && !NativeBuilds.isRedistDirectory(it.name) }.sortedBy { it.name }) {
            if (looked++ >= MAX_WRAPPER_PEEKS) break
            val folder = File(dir, sub.name)
            val inner = DirEntries.list(folder) ?: continue
            val build = NativeBuilds.read(folder, inner, dir.name)
            if (build.platforms != 0) return build.copy(launch = build.launch?.let { sub.name + "/" + it })
        }
        return NativeBuild.NONE
    }

    private fun fromNative(dir: File, entries: List<DirEntry>, signature: Long): GameFinding? {
        if (entries.none { !it.isDirectory } ) return null
        val native = NativeBuilds.read(dir, entries, dir.name)
        if (native.platforms == 0) return null
        // A folder of many unrelated programs is a tools folder, not a game.
        if (native.executables > MAX_EXECUTABLES && !native.matchesFolder) return null
        val directories = entries.count { it.isDirectory }
        val dataFolder = entries.any { it.isDirectory && isDataFolder(it.name) }
        val plausible = native.matchesFolder || native.launchSize >= MIN_GAME_EXECUTABLE || directories >= 2 || entries.size >= 4
        if (!plausible) return null
        return GameFinding(
            path = dir.path,
            name = dir.name.ifBlank { dir.path },
            kind = FindingKind.FOLDER,
            engine = null,
            engineContext = null,
            engineVersion = null,
            requirements = emptyMap(),
            hosted = false,
            platforms = native.platforms,
            confidence = if (native.matchesFolder || dataFolder) Confidence.MEDIUM else Confidence.LOW,
            launch = native.launch,
            architecture = native.architecture,
            dotNet = native.dotNet,
            signature = signature,
        )
    }

    private fun isDataFolder(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith("_data") || lower in DATA_FOLDERS
    }

    fun archiveSignature(entry: DirEntry): Long = salt xor (entry.size * 31 + entry.modified)

    /** An archive that still needs unpacking, listed as it is; the scanner drops it when its unpacked folder is also a game. */
    fun archive(dir: File, entry: DirEntry): GameFinding = GameFinding(
        path = File(dir, entry.name).path,
        name = ArchiveFiles.stem(entry.name),
        kind = FindingKind.ARCHIVE,
        engine = null,
        engineContext = null,
        engineVersion = null,
        requirements = emptyMap(),
        hosted = false,
        platforms = BuildPlatform.ARCHIVE.bit,
        confidence = Confidence.LOW,
        launch = null,
        architecture = null,
        dotNet = false,
        signature = archiveSignature(entry),
    )

    companion object {
        /** Bumped when the analysis itself changes, so cached findings from the older logic are looked at again. */
        const val SCAN_VERSION = 1L
        private const val MAX_WRAPPER_PEEKS = 4
        private const val MAX_EXECUTABLES = 8
        private const val MIN_GAME_EXECUTABLE = 200L * 1024
        private val DATA_FOLDERS = setOf("data", "assets", "resources", "content", "lib", "libs", "bin", "res", "plugins")
    }
}
