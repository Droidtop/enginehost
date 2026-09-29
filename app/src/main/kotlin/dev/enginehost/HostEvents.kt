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
    private const val MAX_LINES = 60
    private const val MAX_LINE_CHARACTERS = 200

    private val lock = Any()
    private val worker by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "host-events").apply { isDaemon = true } }
    }

    /** Append one event; returns at once. */
    fun record(context: Context, event: String) {
        val file = File(context.applicationContext.filesDir, FILE)
        val line = format(Date(), event)
        worker.execute {
            runCatching {
                synchronized(lock) { file.writeText(trimmed(read(file), line, MAX_LINES).joinToString("\n") + "\n") }
            }
        }
    }

    /** The recorded events, oldest first. Reads the file: not for the main thread. */
    fun recent(context: Context): List<String> =
        synchronized(lock) { read(File(context.applicationContext.filesDir, FILE)) }

    /** The ring after adding [line]: the newest [max] lines. */
    internal fun trimmed(existing: List<String>, line: String, max: Int): List<String> = (existing + line).takeLast(max)

    /** One event as a single line with its time. */
    internal fun format(at: Date, event: String): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(at) + " " +
            event.replace(Regex("\\s+"), " ").trim().take(MAX_LINE_CHARACTERS)

    private fun read(file: File): List<String> = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
}
