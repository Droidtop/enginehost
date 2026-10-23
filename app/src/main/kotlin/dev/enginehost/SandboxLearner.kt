package dev.enginehost

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a learning run would have had refused, per core build: the profile
 * a person reviews before a core is confined (docs/engine-sandbox.md,
 * "Learning mode"). Paths are written relative to the launch's own folders
 * ([roots], longest first), so one profile serves every game of a core;
 * calls are named; every entry is counted. Free of Android, so it is
 * tested on the JVM. Not thread-safe: [SandboxLearner] holds the lock.
 */
class SandboxProfile(
    val core: String,
    val build: String,
    val archive: String,
    val abi: String,
    /** Placeholder to real folder, such as "\$GAME" to the game folder; longest folder wins. */
    private val roots: List<Pair<String, String>>,
) {
    private data class PathKey(val op: String, val path: String, val need: String)
    private data class CallKey(val call: String, val would: String)

    private val paths = LinkedHashMap<PathKey, Int>()
    private val calls = LinkedHashMap<CallKey, Int>()
    private val examples = LinkedHashMap<CallKey, MutableList<String>>()
    private val games = LinkedHashSet<String>()
    private var runs = 0

    /** A new run of [game] begins; its records add to the same profile. */
    fun startRun(game: String) {
        runs++
        games += game
    }

    /** One record from the broker; answers whether it was new (worth writing at once). */
    fun add(op: String, asked: String, resolved: String, need: Int, verdict: Int): Boolean {
        val would = errnoName(-verdict)
        if (op == "syscall") {
            val key = CallKey(asked.substringBefore('('), would)
            val count = calls[key] ?: 0
            calls[key] = count + 1
            val seen = examples.getOrPut(key) { mutableListOf() }
            if (seen.size < MAX_EXAMPLES && asked !in seen) seen += asked
            return count == 0
        }
        val key = PathKey(op, generalise(resolved.ifEmpty { asked }), needName(need))
        val count = paths[key] ?: 0
        paths[key] = count + 1
        return count == 0
    }

    /** [path] with the longest matching root replaced by its placeholder. */
    fun generalise(path: String): String {
        val match = roots.filter { (_, folder) -> path == folder || path.startsWith("$folder/") }
            .maxByOrNull { it.second.length } ?: return path
        return match.first + path.removePrefix(match.second)
    }

    /** Adds an earlier profile of the same build: its runs, games and counts. */
    fun merge(json: JSONObject) {
        runs += json.optInt("runs")
        json.optJSONArray("games")?.let { array -> for (i in 0 until array.length()) games += array.getString(i) }
        json.optJSONArray("paths")?.let { array ->
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val key = PathKey(entry.getString("op"), entry.getString("path"), entry.getString("need"))
                paths[key] = (paths[key] ?: 0) + entry.getInt("count")
            }
        }
        json.optJSONArray("calls")?.let { array ->
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val key = CallKey(entry.getString("call"), entry.getString("would"))
                calls[key] = (calls[key] ?: 0) + entry.getInt("count")
                val seen = examples.getOrPut(key) { mutableListOf() }
                entry.optJSONArray("examples")?.let { e ->
                    for (j in 0 until e.length()) if (seen.size < MAX_EXAMPLES && e.getString(j) !in seen) seen += e.getString(j)
                }
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("core", core).put("build", build).put("archive", archive).put("abi", abi)
        .put("runs", runs).put("games", JSONArray(games.toList()))
        .put(
            "paths",
            JSONArray(
                paths.entries.sortedWith(compareBy({ it.key.path }, { it.key.op })).map { (key, count) ->
                    JSONObject().put("op", key.op).put("path", key.path).put("need", key.need).put("count", count)
                },
            ),
        )
        .put(
            "calls",
            JSONArray(
                calls.entries.sortedBy { it.key.call }.map { (key, count) ->
                    JSONObject().put("call", key.call).put("would", key.would).put("count", count)
                        .put("examples", JSONArray(examples[key].orEmpty()))
                },
            ),
        )

    companion object {
        private const val MAX_EXAMPLES = 3

        fun needName(need: Int): String = when {
            need and 2 != 0 -> "write"
            need and 1 != 0 -> "read"
            else -> "lookup"
        }

        fun errnoName(errno: Int): String = when (errno) {
            13 -> "EACCES"
            1 -> "EPERM"
            else -> "errno $errno"
        }
    }
}

/**
 * The broker's recorder for one learning run, in the main process:
 * [record] is called from the broker's native thread
 * (runtime_sandbox.c), and the profile is written a moment after the last
 * new record, since the broker does not say when a run ends. The file is
 * `Android/data/<package>/files/sandbox-learned/<core>-<archive, 12 hex>.json`,
 * and a later run of the same build adds to it.
 */
@Keep
class SandboxLearner(private val file: File, private val profile: SandboxProfile) {
    private val lock = Any()

    @Keep
    fun record(op: ByteArray, asked: ByteArray, resolved: ByteArray, need: Int, flags: Int, verdict: Int) {
        synchronized(lock) {
            profile.add(String(op), String(asked), String(resolved), need, verdict)
        }
        scheduleWrite()
    }

    /** Writes the profile a moment from now, once records stop arriving; a run with none still counts. */
    fun scheduleWrite() {
        writer.removeCallbacks(write)
        writer.postDelayed(write, WRITE_AFTER_MS)
    }

    private val write = Runnable {
        val text = synchronized(lock) { profile.toJson().toString(2) }
        runCatching {
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".partial")
            partial.writeText(text)
            if (!partial.renameTo(file)) file.writeText(text)
        }.onFailure { Log.w(TAG, "Could not write the learned profile ${file.name}", it) }
    }

    companion object {
        private const val TAG = "EnginehostSandbox"
        private const val WRITE_AFTER_MS = 2_000L
        private val writer by lazy { Handler(HandlerThread("sandbox-learn").apply { start() }.looper) }

        /**
         * The recorder for a learning run of [core] on [game], adding to the
         * build's earlier profile when there is one.
         */
        fun start(
            context: Context,
            core: String,
            build: String,
            archive: String,
            game: String,
            roots: List<Pair<String, String>>,
        ): SandboxLearner {
            val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "sandbox-learned")
            val file = File(folder, "$core-${archive.take(12).lowercase()}.json")
            val profile = SandboxProfile(core, build, archive, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), roots)
            runCatching { if (file.isFile) profile.merge(JSONObject(file.readText())) }
                .onFailure { Log.w(TAG, "The earlier profile ${file.name} could not be read; starting afresh", it) }
            profile.startRun(File(game).name)
            Log.w(TAG, "LEARNING MODE: $core: recording into ${file.absolutePath}")
            return SandboxLearner(file, profile).also { it.scheduleWrite() }
        }
    }
}
