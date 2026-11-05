package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Game-ended save reports droidtop has not taken yet (`saves report_changed`, Droidtop/tracker#885).
 *
 * droidtop trusts its `GAME_ENDED` broadcast only for a folder droidtop itself passed as `saveRoot`;
 * saves written anywhere else reach its synced folder only through this report, over the broker.
 * So a report must not be lost when droidtop's broker is not there at the moment a game ends
 * (droidtop not running, the knock unanswered in time, a call failing) or when droidtop answers
 * that it is not ready yet. Every report is written here first and removed only once droidtop
 * accepted it or refused it for good; the rest are sent again when droidtop connects and when
 * Enginehost starts ([DroidtopSaves.flush]).
 *
 * Plain JVM code over one JSON file, so the rules are unit-tested (SavesOutboxTest). Callers run
 * it off the main thread.
 */
object SavesOutbox {
    /** One game-ended report, as `saves report_changed` sends it. */
    data class Report(val path: String, val saveFolder: String, val reason: String, val endedAtMs: Long, val attempts: Int = 0) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("saveFolder", saveFolder)
            .put("reason", reason).put("endedAtMs", endedAtMs).put("attempts", attempts)

        /** The broker call's arguments: the report without the outbox's own bookkeeping. */
        fun args(): JSONObject = JSONObject().put("path", path).put("saveFolder", saveFolder)
            .put("reason", reason).put("endedAtMs", endedAtMs)

        companion object {
            fun fromJson(json: JSONObject): Report? {
                val path = json.optString("path").takeIf { it.isNotBlank() } ?: return null
                val folder = json.optString("saveFolder").takeIf { it.isNotBlank() } ?: return null
                return Report(path, folder, json.optString("reason"), json.optLong("endedAtMs"), json.optInt("attempts"))
            }
        }
    }

    /** What became of one attempt to send a report. */
    enum class Outcome {
        /** droidtop took it: drop it. */
        DELIVERED,
        /** droidtop said no for good (no such game, a folder it never takes): drop it, it would say no again. */
        REFUSED,
        /** Not taken this time (no broker, no answer, not ready): keep it for the next connection. */
        RETRY,
    }

    /** At most this many reports wait; the oldest go first. */
    const val MAX_PENDING = 50

    /** A report this old is dropped: its saves have long since been played over. */
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** A report not taken after this many answered attempts is dropped, so nothing is retried for ever. */
    const val MAX_ATTEMPTS = 20

    /**
     * What droidtop's reply means. A missing reply or a failed call is a retry. An accepted report is
     * delivered. A refusal is final, except "not ready yet", which droidtop gives while its library loads
     * (AppInterfaceSaves.changed: "droidtop's library is not ready yet; tell it again in a moment").
     */
    fun outcome(reply: JSONObject?): Outcome {
        if (reply == null || !reply.optBoolean("ok")) return Outcome.RETRY
        val data = reply.optJSONObject("data") ?: return Outcome.RETRY
        if (data.optBoolean("accepted")) return Outcome.DELIVERED
        val message = data.optString("message")
        return if (message.contains("not ready", ignoreCase = true)) Outcome.RETRY else Outcome.REFUSED
    }

    /** Adds [report], replacing an older one for the same game and folder (the newer end is what counts). */
    @Synchronized
    fun add(file: File, report: Report, nowMs: Long) {
        val kept = read(file).filterNot { it.path == report.path && it.saveFolder == report.saveFolder }
        write(file, prune(kept + report, nowMs))
    }

    /** The reports waiting, oldest first. */
    @Synchronized
    fun pending(file: File): List<Report> = read(file)

    /**
     * Sends every waiting report with [send], oldest first, and keeps what was not taken. Stops at the
     * first one droidtop could not be asked about at all ([send] returned null: no broker), since the
     * rest would fail the same way. Returns how many were delivered.
     */
    @Synchronized
    fun deliver(file: File, nowMs: Long, log: (String) -> Unit = {}, send: (Report) -> JSONObject?): Int {
        val waiting = prune(read(file), nowMs)
        val kept = mutableListOf<Report>()
        var delivered = 0
        var stopped = false
        for (report in waiting) {
            if (stopped) { kept += report; continue }
            val reply = runCatching { send(report) }.getOrNull()
            if (reply == null) { stopped = true; kept += report; continue }
            when (outcome(reply)) {
                Outcome.DELIVERED -> { delivered++; log("droidtop took the saves in ${report.saveFolder}") }
                Outcome.REFUSED -> log("droidtop refused the saves in ${report.saveFolder}: ${reply.optJSONObject("data")?.optString("message")}")
                Outcome.RETRY -> {
                    val next = report.copy(attempts = report.attempts + 1)
                    if (next.attempts >= MAX_ATTEMPTS) log("dropped the report for ${report.saveFolder} after ${next.attempts} attempts")
                    else kept += next
                }
            }
        }
        write(file, kept)
        return delivered
    }

    private fun prune(reports: List<Report>, nowMs: Long): List<Report> =
        reports.filter { it.endedAtMs <= 0 || nowMs - it.endedAtMs <= MAX_AGE_MS }.takeLast(MAX_PENDING)

    private fun read(file: File): List<Report> {
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Report::fromJson) }
    }

    /** Written to a temporary file and renamed over, so a crash leaves the old list or the new one. */
    private fun write(file: File, reports: List<Report>) {
        file.parentFile?.mkdirs()
        if (reports.isEmpty()) { file.delete(); return }
        val array = JSONArray()
        reports.forEach { array.put(it.toJson()) }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }
}
