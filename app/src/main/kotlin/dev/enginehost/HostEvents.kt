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
 */
object HostEvents {
    private const val FILE = "host-events.log"

    /**
     * What the engine itself said, kept apart from the host's own events: the
     * runtime process is its only writer, so two processes never share a file.
     */
    const val ENGINE_FILE = "engine-events.log"
    private const val MAX_LINES = 60
    private const val MAX_LINE_CHARACTERS = 200

    private val lock = Any()
    private val worker by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "host-events").apply { isDaemon = true } }
    }

    /** Append one event; returns at once. */
    fun record(context: Context, event: String, name: String = FILE) {
        val file = File(context.applicationContext.filesDir, name)
        val line = format(Date(), event)
        worker.execute {
            runCatching {
                synchronized(lock) { file.writeText(trimmed(read(file), line, MAX_LINES).joinToString("\n") + "\n") }
            }
        }
    }

    /** The recorded events, oldest first. Reads the file: not for the main thread. */
    fun recent(context: Context, name: String = FILE): List<String> =
        synchronized(lock) { read(File(context.applicationContext.filesDir, name)) }

    /** Forget the ring [name]: a new game session starts a new story. */
    fun clear(context: Context, name: String) {
        val file = File(context.applicationContext.filesDir, name)
        worker.execute { runCatching { synchronized(lock) { file.delete() } } }
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

    /** The ring after adding [line]: the newest [max] lines. */
    internal fun trimmed(existing: List<String>, line: String, max: Int): List<String> = (existing + line).takeLast(max)

    /** One event as a single line with its time. */
    internal fun format(at: Date, event: String): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(at) + " " +
            event.replace(Regex("\\s+"), " ").trim().take(MAX_LINE_CHARACTERS)

    private fun read(file: File): List<String> = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
}
