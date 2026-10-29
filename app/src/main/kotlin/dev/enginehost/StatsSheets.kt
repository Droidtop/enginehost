package dev.enginehost

import android.app.Activity
import android.text.InputFilter
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject

/**
 * The sheets of the anonymous model ratings ([SharedStats]; Droidtop/tracker#491): asking once,
 * rating a model, and showing what was shared. Shown only while [SharedStats.enabled].
 */
class StatsSheets(private val activity: Activity) {
    /**
     * Asks once whether to share ratings, the first time a voice or translator is set up. The safe
     * answer leads. Either answer is kept, and Settings > Privacy changes it.
     */
    fun askOnce(then: () -> Unit = {}) {
        if (!SharedStats.shouldAsk(activity)) return then()
        Sheet(activity).title(R.string.stats_consent_title).message(R.string.stats_consent_message).apply {
            choice(R.string.stats_dont_share) {
                SharedStats.setConsent(activity, SharedStats.Consent.DECLINED)
                then()
            }
            choice(R.string.stats_share) {
                SharedStats.setConsent(activity, SharedStats.Consent.SHARED)
                then()
            }
        }.show()
    }

    /** Rating a model: which one, how good, what for, and an optional comment; then it is shared. */
    fun rate() {
        // The speech engine and its name come from other processes: read off the main thread.
        Thread {
            val text = TextSettings(activity)
            val choices = mutableListOf<Pair<String, StatsReport.Kind>>()
            if (text.translatorEndpoint.isNotBlank() && text.translatorModel.isNotBlank()) {
                choices += activity.getString(R.string.rate_translator, text.translatorModel) to StatsReport.Kind.TRANSLATION
            }
            SharedStats.voiceEngine(activity)?.let { engine ->
                val label = runCatching {
                    activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(engine, 0)).toString()
                }.getOrDefault(engine)
                choices += activity.getString(R.string.rate_voices, label) to StatsReport.Kind.VOICE
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (choices.isEmpty()) {
                    Toast.makeText(activity, R.string.rate_nothing, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Sheet(activity).title(R.string.rate_which).apply {
                    choices.forEach { (label, kind) -> choice(label) { stars(kind, label) } }
                }.show()
            }
        }.start()
    }

    private fun stars(kind: StatsReport.Kind, label: String) {
        val names = listOf(R.string.rate_1, R.string.rate_2, R.string.rate_3, R.string.rate_4, R.string.rate_5)
        Sheet(activity).title(activity.getString(R.string.rate_stars_title, label)).apply {
            names.forEachIndexed { index, name -> choice(name) { purpose(kind, index + 1) } }
        }.show()
    }

    private fun purpose(kind: StatsReport.Kind, rating: Int) {
        val names = mapOf(
            StatsReport.Purpose.VISUAL_NOVELS to R.string.purpose_visual_novels,
            StatsReport.Purpose.RPG_TEXT to R.string.purpose_rpg_text,
            StatsReport.Purpose.ACCESSIBILITY to R.string.purpose_accessibility,
            StatsReport.Purpose.LANGUAGE_LEARNING to R.string.purpose_language_learning,
            StatsReport.Purpose.OTHER to R.string.purpose_other,
        )
        Sheet(activity).title(R.string.rate_purpose_title).apply {
            names.forEach { (purpose, name) -> choice(name) { comment(kind, rating, purpose) } }
            choice(R.string.rate_skip) { comment(kind, rating, null) }
        }.show()
    }

    private fun comment(kind: StatsReport.Kind, rating: Int, purpose: StatsReport.Purpose?) {
        val field = EditText(activity).apply {
            setHint(R.string.rate_comment_hint)
            filters = arrayOf(InputFilter.LengthFilter(StatsReport.MAX_COMMENT))
        }
        Sheet(activity).title(R.string.rate_comment_title).content(field).apply {
            choice(R.string.rate_send) { send(kind, rating, purpose, field.text.toString()) }
        }.show()
    }

    private fun send(kind: StatsReport.Kind, rating: Int, purpose: StatsReport.Purpose?, comment: String) {
        val context = activity.applicationContext
        Thread {
            val report = when (kind) {
                StatsReport.Kind.TRANSLATION -> SharedStats.translatorReport(context, purpose, rating, comment)
                StatsReport.Kind.VOICE -> SharedStats.voiceReport(context, purpose, rating, comment)
            }
            report?.let { SharedStats.submit(context, it) }
            activity.runOnUiThread {
                if (!activity.isDestroyed) Toast.makeText(activity, R.string.rate_thanks, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Everything shared, newest first, each report exactly as it went or would have gone. */
    fun showLog() {
        Thread {
            val logged = SharedStats.logged(activity.applicationContext)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                Sheet(activity).title(R.string.shared_log_row).apply {
                    if (logged.isEmpty()) message(R.string.shared_log_empty)
                    logged.forEach { entry ->
                        val at = DateUtils.getRelativeTimeSpanString(entry.at)
                        val outcome = when (entry.outcome) {
                            SharedStats.Outcome.SENT -> activity.getString(R.string.shared_log_sent, at)
                            SharedStats.Outcome.DROPPED -> activity.getString(R.string.shared_log_dropped, at)
                            SharedStats.Outcome.NO_ENDPOINT -> activity.getString(R.string.shared_log_no_endpoint, at)
                        }
                        val shown = runCatching { JSONObject(entry.report).toString(2) }.getOrDefault(entry.report)
                        choice(outcome, detail = shown) {}
                    }
                }.show()
            }
        }.start()
    }
}
