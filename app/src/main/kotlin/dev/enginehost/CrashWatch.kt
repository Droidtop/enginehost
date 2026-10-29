package dev.enginehost

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Notices when a game's runtime died rather than ended.
 *
 * Games run in their own process, and when that process crashes the person is
 * simply returned to wherever they came from with nothing said. The runtime
 * leaves a note here as it starts a game: which game, which plugin, which
 * process. Later, back in the app, the note is read against Android's own
 * record of why that process ended, which is the one source that also knows
 * about native crashes, where no handler in our code ever ran. A Java crash
 * additionally writes its stack trace into the note, so the report can carry
 * the actual failure rather than only the fact of one.
 */
object CrashWatch {
    private const val TAG = "enginehost"
    private const val NOTE = "runtime-session.json"
    private const val TRACE_CHARACTERS = 4000
    private const val TOMBSTONE_LINES = 30
    private const val SIGKILL = 9
    private const val SIGTERM = 15
    private const val FAILURE = "failure"

    /** The game's screen showed something other than black ([RuntimeLoadingNotice]). */
    const val DRAWN = "drawn"

    /** The game's screen could be read at least once, so a missing [DRAWN] means something. */
    const val WATCHED = "watched"

    /** The person quit from the in-game menu ([HostMenu]). */
    const val QUIT = "quit"

    /** The reason a game that closed before showing anything is reported with. */
    const val CLOSED_UNSEEN = "Closed before it showed anything"

    /**
     * A runtime that died, with what is known about why. [byAndroid]: Android ended it from
     * outside (low memory, a system service it used stopping), and [reason] says which.
     */
    data class Crash(
        val gameFolder: File,
        val plugin: String,
        val reason: String,
        val trace: String,
        val byAndroid: Boolean = false,
    )

    /** What the person is told about [crash]: Android stopped the game, or it stopped unexpectedly. */
    fun sentence(context: Context, crash: Crash): String =
        context.getString(if (crash.byAndroid) R.string.launch_stopped_by_android else R.string.launch_crashed, crash.reason)

    /**
     * Why Android ended a game's process from outside, for an exit reason that means it did, or
     * null for any other. Such an end is shown as "Android stopped the game", with Retry, and
     * callers are told it crashed: the person did not end it, and saying nothing left them on
     * their library with no idea why the game went (owner, 2026-10-29, Droidtop/tracker#27).
     */
    internal fun stoppedByAndroid(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "it needed the memory"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a system service it was using stopped"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "it was using too much of the device"
        else -> null
    }

    /**
     * Called in the runtime process as a game starts. Records the session and
     * arranges for an uncaught Java exception to be written into it before
     * the process goes down.
     */
    /** The game folder of the last session armed, which the engine-events ring belongs to; null when none is recorded. */
    fun lastSessionPath(context: Context): String? =
        runCatching { JSONObject(file(context).readText()).optString("path") }.getOrNull()?.takeIf { it.isNotBlank() }

    fun arm(context: Context, gameFolder: File, plugin: String) {
        val note = JSONObject()
            .put("path", gameFolder.absolutePath)
            .put("plugin", plugin)
            .put("pid", Process.myPid())
            .put("startedAt", System.currentTimeMillis())
        runCatching { file(context).writeText(note.toString()) }
            .onFailure { Log.w(TAG, "Could not record the runtime session", it) }
        HostEvents.clear(context, HostEvents.ENGINE_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val current = JSONObject(file(context).readText())
                current.put("trace", Log.getStackTraceString(error).take(TRACE_CHARACTERS))
                file(context).writeText(current.toString())
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Marks the session note with [key] ([DRAWN], [WATCHED], [QUIT]), in the
     * runtime process. Read and rewritten under one lock, as the uncaught
     * exception handler also rewrites the note.
     */
    @Synchronized
    fun mark(context: Context, key: String) {
        runCatching {
            val f = file(context)
            if (f.isFile) f.writeText(JSONObject(f.readText()).put(key, true).toString())
        }
    }

    /**
     * The person ended a game that showed nothing or stopped responding
     * ([GameTroubleActivity]): called in the main process just before the
     * runtime is killed, so the launch screen says why rather than "closed".
     */
    fun recordFailure(context: Context, reason: String) {
        runCatching {
            val f = file(context)
            if (f.isFile) f.writeText(JSONObject(f.readText()).put(FAILURE, reason).toString())
        }
    }

    /**
     * The person ended the game on purpose (the in-game menu's Quit,
     * END_GAME): never a crash, and never "closed before it showed anything".
     */
    fun quit(context: Context) {
        mark(context, QUIT)
        disarm(context)
    }

    /** Forgets the last session, for an end the launch screen has already explained. */
    fun discard(context: Context) {
        file(context).delete()
    }

    /** Called in the runtime process when a game ends the way it meant to. */
    fun disarm(context: Context) {
        runCatching {
            val f = file(context)
            if (f.isFile) {
                val current = JSONObject(f.readText()).put("ended", true)
                f.writeText(current.toString())
            }
        }
    }

    /**
     * Whether the last runtime session ended in a crash. Reading the note
     * clears it once the answer is known, so a crash is offered for report
     * once and a clean exit is never mentioned. Returns null while the
     * runtime is still alive.
     */
    /**
     * True while the runtime process the note names is still alive, or has
     * ended without Android having filed its exit record yet. A native crash
     * keeps the process alive for about a second while crash_dump writes the
     * tombstone, and the activity result arrives before that; classifying at
     * that moment reads as "no crash". Callers poll this before [consume].
     */
    fun pending(context: Context): Boolean {
        val note = runCatching { JSONObject(file(context).readText()) }.getOrNull() ?: return false
        if (note.optBoolean("ended")) return false
        // Our handler has written the trace: the crash is known, whatever
        // Android does with the process afterwards.
        if (note.optString("trace").isNotBlank()) return false
        val pid = note.optInt("pid")
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (manager.runningAppProcesses?.any { it.pid == pid } == true) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).isEmpty()
    }

    fun consume(context: Context): Crash? {
        val f = file(context)
        val note = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val pid = note.optInt("pid")
        val gameFolder = File(note.optString("path"))
        val plugin = note.optString("plugin")
        val trace = note.optString("trace")
        note.optString(FAILURE).takeIf { it.isNotBlank() }?.let { failure ->
            f.delete()
            return Crash(gameFolder, plugin, failure, trace)
        }
        // A game whose screen was watched and never drew, and that ended
        // without the person quitting it from the in-game menu, closed
        // before it showed anything: a failure, not a game that was played.
        val closedUnseen = note.optBoolean(WATCHED) && !note.optBoolean(DRAWN) && !note.optBoolean(QUIT)
        val unseen = Crash(gameFolder, plugin, CLOSED_UNSEEN, trace)
        if (note.optBoolean("ended")) {
            f.delete()
            return if (closedUnseen) unseen else null
        }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (manager.runningAppProcesses?.any { it.pid == pid } == true) {
            // Alive after a Java crash is Android holding it for a crash
            // dialog (see RuntimeProcess); the crash is on record, so the
            // process is ended and the crash reported now.
            if (trace.isBlank()) return null
            Process.killProcess(pid)
            f.delete()
            return Crash(gameFolder, plugin, "Uncaught exception", trace)
        }
        f.delete()

        val fromHandler = trace.takeIf { it.isNotBlank() }?.let { Crash(gameFolder, plugin, "Uncaught exception", it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Before Android 11 there is no record of why a process ended, so
            // the only crash that can be recognised is one our handler saw.
            return fromHandler ?: unseen.takeIf { closedUnseen }
        }
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()
            ?: return fromHandler ?: unseen.takeIf { closedUnseen }
        val reason = describe(exit) ?: return unseen.takeIf { closedUnseen }
        // A locked-down process leaves no tombstone: Android's crash report needs a new
        // process. Its trace ring is the only record, of the call a signal 31 died on or
        // the place a fatal signal hit. Null when the process was not locked down.
        val signaled = exit.reason == ApplicationExitInfo.REASON_SIGNALED ||
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        val sandbox = if (signaled) {
            RuntimeSandbox.traceOf(context, pid)?.let { "Sandbox trace: $it" }
        } else {
            null
        }
        sandbox?.let { Log.w(TAG, it) }
        val details = listOfNotNull(trace.ifBlank { exitTrace(exit) }.ifBlank { null }, sandbox).joinToString("\n\n")
        return Crash(gameFolder, plugin, reason, details, byAndroid = stoppedByAndroid(exit.reason) != null)
    }

    /** A sentence for an exit that was a failure, or null for one that was not. */
    private fun describe(exit: ApplicationExitInfo): String? = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH -> "Uncaught exception"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash" + signalName(exit.status)
        ApplicationExitInfo.REASON_ANR -> "Stopped responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to initialise"
        ApplicationExitInfo.REASON_SIGNALED ->
            if (exit.status == SIGKILL || exit.status == SIGTERM) null else "Killed by signal" + signalName(exit.status)
        // The person swiping it away, our own clean exit: not a failure. Android ending it is.
        else -> stoppedByAndroid(exit.reason)
    }

    private fun signalName(status: Int): String = when (status) {
        0 -> ""
        4 -> " (SIGILL)"
        6 -> " (SIGABRT)"
        7 -> " (SIGBUS)"
        8 -> " (SIGFPE)"
        11 -> " (SIGSEGV)"
        else -> " (signal $status)"
    }

    /**
     * What Android kept about the exit. A native crash's tombstone is
     * summarised ([summarizeNative]); an ANR trace is text and is kept as is.
     */
    private fun exitTrace(exit: ApplicationExitInfo): String {
        val bytes = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
        if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            return summarizeNative(exit.description, bytes ?: ByteArray(0))
        }
        return bytes?.toString(Charsets.UTF_8)?.take(TRACE_CHARACTERS).orEmpty().ifBlank { exit.description.orEmpty() }
    }

    private val PRINTABLE_RUN = Regex("[\\x20-\\x7e]{5,}")
    private val TOMBSTONE_MARKER = Regex("""\.so\b|Abort message|Fatal signal|\bSIG[A-Z]+|>>> |\bcode -?\d+|fault addr""")

    /**
     * A native crash in the few lines a reader needs: what Android says the
     * signal was, then the lines of the tombstone that name a library, the
     * signal, the abort message or the crashed process. Android 12 and later
     * keep the tombstone as a binary protocol buffer, whose text is still
     * there as printable runs, so the same filter reads both forms. Internal
     * for tests, which feed it a text and a binary sample.
     */
    internal fun summarizeNative(description: String?, trace: ByteArray): String {
        val runs = PRINTABLE_RUN.findAll(String(trace, Charsets.ISO_8859_1)).map { it.value.trim() }
        val kept = runs.filter { TOMBSTONE_MARKER.containsMatchIn(it) }.distinct().take(TOMBSTONE_LINES).toList()
        return (listOfNotNull(description?.takeIf { it.isNotBlank() }) + kept).joinToString("\n").take(TRACE_CHARACTERS)
    }

    private fun file(context: Context): File = File(context.filesDir, NOTE)

    /** The session note, which the runtime keeps writing while locked down ([RuntimeSandbox.policy]). */
    internal fun noteFile(context: Context): File = file(context)
}
