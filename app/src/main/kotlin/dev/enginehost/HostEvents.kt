package dev.enginehost

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The last things Enginehost itself did, kept so a problem report can say what
 * happened just before a failure without asking the person to remember
 * (Droidtop/tracker#22). A short ring of one-line events in a file in the
 * app's own storage: it survives the process dying, which is the moment it is
 * wanted. Lines name what happened (who was allowed, what the plan decided,
 * how the runtime ended), never a game's name or path; reports redact what
 * slips through anyway ([ProblemReport.scrub]). Writes go through one
 * background thread, so recording an event never touches the disk on the
 * caller's thread.
 *
 * An event is appended as one line; the file is cut back to the newest
 * [MAX_LINES] only once it holds twice that. Rewriting the whole ring for
 * every event, as this did, meant a read and a write of the file per line a
 * chatty engine logged (code quality review, Droidtop/tracker#768).
 */
object HostEvents {
    private const val FILE = "host-events.log"

    /** The logcat tag host events are also written under. */
    const val LOG_TAG = "enginehost-events"

    /**
     * What the engine itself said, kept apart from the host's own events: the
     * runtime process is its only writer, so two processes never share a file.
     */
    const val ENGINE_FILE = "engine-events.log"
    internal const val MAX_LINES = 60
    private const val MAX_LINE_CHARACTERS = 200

    private val lock = Any()
    private val worker by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "host-events").apply { isDaemon = true } }
    }

    /** The two rings the runtime process writes, kept writable while it is locked down ([RuntimeSandbox.policy]). */
    internal fun runtimeFiles(context: Context): List<File> =
        listOf(FILE, ENGINE_FILE).map { File(context.applicationContext.filesDir, it) }

    /** Append one event; returns at once. */
    fun record(context: Context, event: String, name: String = FILE) {
        val file = File(context.applicationContext.filesDir, name)
        val line = format(Date(), event)
        // The host's own ring also goes to logcat under one tag, so the core
        // test harness (tools/core-harness) and a problem report's logcat see
        // the same milestones. Host events name no game ([FILE]'s rule).
        if (name == FILE) android.util.Log.i(LOG_TAG, event)
        worker.execute {
            runCatching { synchronized(lock) { append(file, line) } }
        }
    }

    /** Lines appended to each ring since this process last cut it back, by file. */
    private val appended = HashMap<String, Int>()

    /** Appends [line] to [file], and cuts it back to the newest [MAX_LINES] once it holds twice that. Under [lock]. */
    internal fun append(file: File, line: String) {
        file.appendText(line + "\n")
        // The count starts from what the file holds, so a ring another process wrote is cut back too.
        val count = (appended[file.path] ?: read(file).size) + 1
        if (count >= 2 * MAX_LINES) {
            file.writeText(read(file).takeLast(MAX_LINES).joinToString("\n") + "\n")
            appended[file.path] = MAX_LINES
        } else {
            appended[file.path] = count
        }
    }

    /** The recorded events, oldest first. Reads the file: not for the main thread. */
    fun recent(context: Context, name: String = FILE): List<String> =
        synchronized(lock) { read(File(context.applicationContext.filesDir, name)).takeLast(MAX_LINES) }

    /** Forget the ring [name]: a new game session starts a new story. */
    fun clear(context: Context, name: String) {
        val file = File(context.applicationContext.filesDir, name)
        worker.execute { runCatching { synchronized(lock) { file.delete().also { appended.remove(file.path) } } } }
    }

    /**
     * The line an engine's own log call adds to the engine ring, or null when
     * it is not worth a slot: only warnings and worse, one line, and never the
     * same line twice running. [previous] is the last line kept.
     */
    internal fun engineLine(priority: Int, tag: String, message: String, previous: String?): String? {
        if (priority < android.util.Log.WARN) return null
        val level = when (priority) {
            android.util.Log.WARN -> "warn"
            android.util.Log.ERROR -> "error"
            else -> "fatal"
        }
        val first = message.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val text = "$level $tag: $first".take(MAX_LINE_CHARACTERS)
        return text.takeIf { it != previous }
    }

    private var lastEngineLine: String? = null

    /** Record an engine log call in the engine ring if it earns a line (see [engineLine]). */
    fun recordEngine(context: Context, priority: Int, tag: String, message: String) {
        val line = synchronized(lock) {
            engineLine(priority, tag, message, lastEngineLine)?.also { lastEngineLine = it }
        } ?: return
        record(context, line, ENGINE_FILE)
    }

    /** One event as a single line with its time. */
    internal fun format(at: Date, event: String): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(at) + " " +
            event.replace(WHITESPACE, " ").trim().take(MAX_LINE_CHARACTERS)

    private val WHITESPACE = Regex("\\s+")

    private fun read(file: File): List<String> = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
}
