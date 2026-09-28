package dev.enginehost

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Notices when Enginehost's own process died, not a launched game's.
 *
 * [CrashWatch] only ever knows about a game's runtime process -- it is armed
 * by [CrashWatch.arm], which nothing calls except as a game starts. A crash
 * inside the host itself (Home, Settings, any screen this app draws) had no
 * note anywhere: the person was dropped on the launcher with nothing said,
 * and there was no way into [ProblemReportActivity] for it at all, so the
 * one report an owner most wants -- "Enginehost itself broke" -- could
 * never actually be filed. This is the same one-note-file, read-once shape
 * as [CrashWatch], scoped to the default process instead of a game's.
 */
object HostCrashWatch {
    private const val TAG = "enginehost"
    private const val NOTE = "host-crash.json"
    private const val TRACE_CHARACTERS = 4000

    data class Crash(val reason: String, val trace: String)

    /**
     * Installed once, in [EnginehostApplication.onCreate] for the default
     * process only: a game runtime crash is [CrashWatch]'s job, and the
     * isolated/plugin processes are sandboxed on purpose, not somewhere
     * this app's own crash report belongs.
     */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val note = JSONObject()
                    .put("reason", error::class.java.simpleName + (error.message?.let { ": $it" } ?: ""))
                    .put("trace", Log.getStackTraceString(error).take(TRACE_CHARACTERS))
                file(context).writeText(note.toString())
            }.onFailure { Log.w(TAG, "could not record the host crash note", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last host crash, once -- reading it clears the note, same as [CrashWatch.consume]. */
    fun consume(context: Context): Crash? {
        val f = file(context)
        val note = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        f.delete()
        val reason = note.optString("reason").ifBlank { return null }
        return Crash(reason, note.optString("trace"))
    }

    private fun file(context: Context): File = File(context.filesDir, NOTE)
}
