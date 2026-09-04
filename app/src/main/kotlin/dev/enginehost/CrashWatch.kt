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
    private const val SIGKILL = 9
    private const val SIGTERM = 15

    /** A runtime that died, with what is known about why. */
    data class Crash(val gameFolder: File, val plugin: String, val reason: String, val trace: String)

    /**
     * Called in the runtime process as a game starts. Records the session and
     * arranges for an uncaught Java exception to be written into it before
     * the process goes down.
     */
    fun arm(context: Context, gameFolder: File, plugin: String) {
        val note = JSONObject()
            .put("path", gameFolder.absolutePath)
            .put("plugin", plugin)
            .put("pid", Process.myPid())
            .put("startedAt", System.currentTimeMillis())
        runCatching { file(context).writeText(note.toString()) }
            .onFailure { Log.w(TAG, "Could not record the runtime session", it) }
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
    fun consume(context: Context): Crash? {
        val f = file(context)
        val note = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val pid = note.optInt("pid")
        val gameFolder = File(note.optString("path"))
        val plugin = note.optString("plugin")
        val trace = note.optString("trace")
        if (note.optBoolean("ended")) {
            f.delete()
            return null
        }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (manager.runningAppProcesses?.any { it.pid == pid } == true) return null
        f.delete()

        val fromHandler = trace.takeIf { it.isNotBlank() }?.let { Crash(gameFolder, plugin, "Uncaught exception", it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Before Android 11 there is no record of why a process ended, so
            // the only crash that can be recognised is one our handler saw.
            return fromHandler
        }
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()
            ?: return fromHandler
        val reason = describe(exit) ?: return null
        return Crash(gameFolder, plugin, reason, trace.ifBlank { exitTrace(exit) })
    }

    /** A sentence for an exit that was a failure, or null for one that was not. */
    private fun describe(exit: ApplicationExitInfo): String? = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH -> "Uncaught exception"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash" + signalName(exit.status)
        ApplicationExitInfo.REASON_ANR -> "Stopped responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to initialise"
        ApplicationExitInfo.REASON_SIGNALED ->
            if (exit.status == SIGKILL || exit.status == SIGTERM) null else "Killed by signal" + signalName(exit.status)
        // Low memory, the person swiping it away, our own clean exit: not a crash.
        else -> null
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

    /** The tombstone or ANR trace Android kept, when it kept one. */
    private fun exitTrace(exit: ApplicationExitInfo): String = runCatching {
        exit.traceInputStream?.bufferedReader()?.use { it.readText().take(TRACE_CHARACTERS) }
    }.getOrNull().orEmpty().ifBlank { exit.description.orEmpty() }

    private fun file(context: Context): File = File(context.filesDir, NOTE)
}
