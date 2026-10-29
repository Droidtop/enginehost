package dev.enginehost

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Anonymous ratings of voice and translation models, opt-in (docs/stats-schema.md;
 * Droidtop/tracker#491).
 *
 * The owner, 2026-10-28: "build the system, but don't enable it yet". So it is all here, the consent,
 * the reports, the log that shows the person what went, the transport and the aggregate, and it is
 * off: [ENDPOINT] and [AGGREGATE_URL] are empty until the server exists (#364), and while
 * [ENDPOINT] is empty no part of it shows and nothing is sent. `debug.enginehost.stats 1` shows it
 * for the rig; with no endpoint, a report is then written only to the local log, marked unsent.
 *
 * Main process only. Every method that reads or writes a file or the network is for a background
 * thread.
 */
object SharedStats {
    /** Where reports go. Empty: nothing is sent, and the system stays hidden. */
    const val ENDPOINT = ""

    /** The published aggregate ([StatsAggregate]). Empty: nothing is fetched and no rating is shown. */
    const val AGGREGATE_URL = ""

    /** Delivery tries for one report, within [DELIVERY_WINDOW_MS] of the first; then it is dropped. */
    private const val MAX_ATTEMPTS = 3
    private const val DELIVERY_WINDOW_MS = 24L * 60 * 60 * 1000
    private const val AGGREGATE_AGE_MS = 24L * 60 * 60 * 1000
    private const val LOG_KEPT = 50

    enum class Consent { UNASKED, SHARED, DECLINED }

    /** Whether any of this shows: with an endpoint, or for the rig. */
    fun enabled(): Boolean = ENDPOINT.isNotEmpty() || DebugProperty.get("stats") == "1"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("shared_stats", Context.MODE_PRIVATE)

    fun consent(context: Context): Consent =
        prefs(context).getString("consent", null)?.let { runCatching { Consent.valueOf(it) }.getOrNull() } ?: Consent.UNASKED

    fun setConsent(context: Context, consent: Consent) {
        prefs(context).edit().putString("consent", consent.name).apply()
        // Turned off: whatever waited to be sent goes, unsent.
        if (consent != Consent.SHARED) Async.run { pendingFile(context).delete() }
    }

    /** Whether to ask now: the system is on and the person was never asked. */
    fun shouldAsk(context: Context): Boolean = enabled() && consent(context) == Consent.UNASKED

    /** A typical translation answer time, kept as a running average of the answers the service gave. */
    fun recordTranslationAnswer(context: Context, millis: Long) {
        val p = prefs(context)
        val old = p.getLong("translate_answer_ms", -1)
        p.edit().putLong("translate_answer_ms", if (old < 0) millis else (old * 3 + millis) / 4).apply()
    }

    private fun translationAnswerMillis(context: Context): Long? =
        prefs(context).getLong("translate_answer_ms", -1).takeIf { it >= 0 }

    /** The report for the person's translator, or null when none is set up. */
    fun translatorReport(context: Context, purpose: StatsReport.Purpose?, rating: Int, comment: String?): StatsReport? {
        val text = TextSettings(context)
        if (text.translatorEndpoint.isBlank() || text.translatorModel.isBlank()) return null
        return StatsReport.of(
            StatsReport.Kind.TRANSLATION, text.translatorModel, null, StatsReport.backendOf(text.translatorEndpoint),
            text.translateInto, purpose, rating, comment, ramTier(context), socFamily(),
            StatsReport.speedBucket(translationAnswerMillis(context)),
        )
    }

    /** The report for the device's voices: its speech engine, by package and version. */
    fun voiceReport(context: Context, purpose: StatsReport.Purpose?, rating: Int, comment: String?): StatsReport? {
        val engine = voiceEngine(context) ?: return null
        val version = runCatching { context.packageManager.getPackageInfo(engine, 0).versionName }.getOrNull()
        return StatsReport.of(
            StatsReport.Kind.VOICE, engine, version, "on-device", java.util.Locale.getDefault().language,
            purpose, rating, comment, ramTier(context), socFamily(), null,
        )
    }

    /** The speech engine Android uses by default, or the first installed one. */
    fun voiceEngine(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching {
                context.packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
                    .firstOrNull()?.serviceInfo?.packageName
            }.getOrNull()

    private fun ramTier(context: Context): String {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return StatsReport.ramTier(info.totalMem)
    }

    private fun socFamily(): String =
        StatsReport.socFamily(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null)

    /**
     * Shares [report] when the person chose to: sent now, or kept for another try when the network
     * failed. With sharing off, nothing happens and nothing is kept. Background thread.
     */
    @Synchronized
    fun submit(context: Context, report: StatsReport) {
        if (consent(context) != Consent.SHARED) return
        if (ENDPOINT.isEmpty()) {
            log(context, report.toJson(), Outcome.NO_ENDPOINT)
            return
        }
        val pending = readArray(pendingFile(context))
        pending.put(JSONObject().put("report", report.toJson()).put("first", System.currentTimeMillis()).put("attempts", 0))
        writeArray(pendingFile(context), pending)
        deliver(context)
    }

    /** Tries what is waiting, once each; drops what has had its tries or its day. Background thread. */
    @Synchronized
    fun deliver(context: Context) {
        val file = pendingFile(context)
        if (!file.isFile) return
        if (consent(context) != Consent.SHARED || ENDPOINT.isEmpty()) {
            file.delete()
            return
        }
        val now = System.currentTimeMillis()
        val pending = readArray(file)
        val kept = JSONArray()
        for (i in 0 until pending.length()) {
            val item = pending.optJSONObject(i) ?: continue
            val report = item.optString("report")
            val attempts = item.optInt("attempts") + 1
            when {
                post(report) -> log(context, report, Outcome.SENT)
                attempts >= MAX_ATTEMPTS || now - item.optLong("first") > DELIVERY_WINDOW_MS -> log(context, report, Outcome.DROPPED)
                else -> kept.put(item.put("attempts", attempts))
            }
        }
        if (kept.length() == 0) file.delete() else writeArray(file, kept)
    }

    private fun post(report: String): Boolean = runCatching {
        Http.read(
            Http.Request(
                ENDPOINT,
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = report.toByteArray(Charsets.UTF_8),
                // No app version: it would narrow who sent a report.
                userAgent = "enginehost-stats/${StatsReport.SCHEMA}",
                readTimeoutMs = 15_000,
            ),
            maxBytes = 64L * 1024,
        )
    }.isSuccess

    enum class Outcome { SENT, DROPPED, NO_ENDPOINT }

    /** One line of the log the person sees in Settings > Privacy: when, what, and whether it went. */
    data class Logged(val at: Long, val report: String, val outcome: Outcome)

    @Synchronized
    private fun log(context: Context, report: String, outcome: Outcome) {
        val file = logFile(context)
        val old = readArray(file)
        val log = JSONArray()
        // Newest first, the oldest dropped past LOG_KEPT.
        log.put(JSONObject().put("at", System.currentTimeMillis()).put("report", report).put("outcome", outcome.name))
        for (i in 0 until minOf(old.length(), LOG_KEPT - 1)) log.put(old.opt(i))
        writeArray(file, log)
    }

    /** What was shared, newest first. Background thread. */
    fun logged(context: Context): List<Logged> {
        val log = readArray(logFile(context))
        return (0 until log.length()).mapNotNull { i ->
            val e = log.optJSONObject(i) ?: return@mapNotNull null
            val outcome = runCatching { Outcome.valueOf(e.optString("outcome")) }.getOrNull() ?: return@mapNotNull null
            Logged(e.optLong("at"), e.optString("report"), outcome)
        }
    }

    /** The aggregate last fetched, or [StatsAggregate.EMPTY]. Background thread. */
    fun aggregate(context: Context): StatsAggregate {
        val file = aggregateFile(context)
        return if (file.isFile) StatsAggregate.parse(file.readText()) else StatsAggregate.EMPTY
    }

    /** Fetches the aggregate when it is a day old or missing, and there is one to fetch. Background thread. */
    fun refreshAggregate(context: Context) {
        if (AGGREGATE_URL.isEmpty()) return
        val file = aggregateFile(context)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < AGGREGATE_AGE_MS) return
        val body = runCatching {
            Http.read(Http.Request(AGGREGATE_URL, readTimeoutMs = 15_000), maxBytes = 4L * 1024 * 1024)
        }.getOrNull() ?: return
        val text = body.toString(Charsets.UTF_8)
        // Kept only when this build can read it; a bad fetch leaves the last good one.
        if (StatsAggregate.parse(text) === StatsAggregate.EMPTY) return
        val partial = File(file.parentFile, file.name + ".part")
        partial.writeText(text)
        partial.renameTo(file)
    }

    private fun logFile(context: Context) = File(context.filesDir, "shared-stats-log.json")
    private fun pendingFile(context: Context) = File(context.filesDir, "shared-stats-pending.json")
    private fun aggregateFile(context: Context) = File(context.filesDir, "shared-stats-aggregate.json")

    private fun readArray(file: File): JSONArray =
        if (file.isFile) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()) else JSONArray()

    private fun writeArray(file: File, array: JSONArray) {
        val partial = File(file.parentFile, file.name + ".part")
        partial.writeText(array.toString())
        partial.renameTo(file)
    }
}
