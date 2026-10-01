package dev.enginehost

import android.content.Context
import java.io.File

/**
 * Decides what this device can do with a library row, per distinct engine
 * line rather than per game: a library of thousands holds a handful of
 * (engine, context, version, requirements) combinations, and each is
 * resolved against the installed plugins once. Nothing here reads a game's
 * folder.
 *
 * Call off the main thread: the first lookup lists the installed plugins.
 */
class SupportResolver(private val context: Context) {
    private val plugins: List<InstalledPlugin> by lazy {
        runCatching { PluginRegistry.discover(context) }.getOrDefault(emptyList())
    }
    private val trust: PluginTrustStore by lazy { PluginTrustStore(context) }
    private val memo = HashMap<String, Support>()

    fun of(row: GameRow): Support {
        val engine = row.engine
        if (row.kind == FindingKind.ARCHIVE || engine == null || !row.hosted) return Support.NOT_SUPPORTED
        val key = "$engine|${row.engineContext}|${row.engineVersion}|${row.requirements}"
        return memo.getOrPut(key) { resolve(engine, row) }
    }

    private fun resolve(engine: String, row: GameRow): Support {
        val version = row.engineVersion?.let { runCatching { Version.parse(it) }.getOrNull() }
        // A game whose version was not read can still run once its setup states one:
        // it is supported when any installed plugin serves the engine at all.
        if (version == null) {
            return if (plugins.any { it.info.runs(engine) }) Support.RUNS_HERE else Support.NEEDS_PLUGIN
        }
        val requirements = parseRequirements(row.requirements)
        val resolved = runCatching {
            PluginResolver.resolve(
                plugins = plugins,
                engine = engine,
                engineContext = row.engineContext,
                engineVersion = version,
                runtimeRequirements = requirements,
                pluginVersionAllowlist = null,
                trustOf = trust::state,
            )
        }.getOrNull()
        return if (resolved != null) Support.RUNS_HERE else Support.NEEDS_PLUGIN
    }

    companion object {
        /** The stored "key=value;key=value" form back into what plugin resolution takes. */
        fun parseRequirements(stored: String): Map<String, Version> = buildMap {
            for (pair in stored.split(';')) {
                val key = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (key.isEmpty() || value.isEmpty()) continue
                runCatching { Version.parse(value) }.getOrNull()?.let { put(key, it) }
            }
        }
    }
}

/**
 * Fills in what a game added by hand (a picked folder, a launch from
 * another app) has no scan to say: the folder's own configuration if it has
 * one, otherwise the same analysis a scan would run. Once, off the main
 * thread; the result is stored, so the list never reads a folder to draw.
 */
object LibraryClassifier {
    private const val BATCH = 200

    /** Classifies up to a batch of waiting games; true when it changed anything worth redrawing. */
    fun classifyPending(context: Context): Boolean {
        val library = GameLibraryStore(context)
        val waiting = library.unclassified(BATCH)
        if (waiting.isEmpty()) return false
        val analyzer = FolderAnalyzer(EngineRegistryStore.rows(context))
        var changed = false
        for (path in waiting) {
            val folder = File(path)
            // Storage that is not mounted right now says nothing about the game; ask again later.
            if (!folder.isDirectory) continue
            val finding = fromConfig(folder) ?: runCatching {
                analyzer.analyze(folder, DirEntries.list(folder).orEmpty(), 0L)
            }.getOrNull()
            library.classify(path, finding)
            changed = true
        }
        return changed
    }

    /** A folder's own enginehost.json is what Play runs, so it is also what the library says. */
    private fun fromConfig(folder: File): GameFinding? {
        val config = runCatching { EngineConfigReader.resolve(folder, null) }.getOrNull() ?: return null
        val web = config.engine == "html"
        return GameFinding(
            path = folder.path,
            name = config.title ?: folder.name.ifBlank { folder.path },
            kind = FindingKind.FOLDER,
            engine = config.engine,
            engineContext = config.engineContext,
            engineVersion = config.engineVersion.toString(),
            requirements = config.runtimeRequirements.mapValues { it.value.toString() },
            hosted = true,
            platforms = if (web) BuildPlatform.mask(BuildPlatform.PORTABLE, BuildPlatform.WEB) else BuildPlatform.PORTABLE.bit,
            confidence = Confidence.HIGH,
            launch = config.execFile,
            architecture = null,
            dotNet = false,
            signature = 0L,
        )
    }
}
