package dev.enginehost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Reporting a game that will not behave.
 *
 * Enginehost fills in what it knows, and every field stays editable:
 * detection is a guess, the plugin it picked may be the wrong one, and the
 * person with the game in front of them is the one who can correct it. No
 * field is required -- the game's name leads but falls back to the engine
 * (or just "Enginehost" for a report about the host itself) when there is
 * no game to name, so a person can always just press Send. Sending opens
 * the project's form with these values already in it.
 */
class ProblemReportActivity : EnginehostActivity() {
    /** The report is never blocked on the game field -- see [fill] -- so Share is always where the pad starts. */
    override fun primaryAction(): View? = findViewById(R.id.shareReportButton)

    private var log: String = ""
    private var symptom: String = ""

    /** The game folder's own name, blanked out of the report when the person clears the game field. */
    private var folderName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.report_title)
        setContentView(R.layout.activity_problem_report)
        wireBackButton()

        val gameFolder = intent.getStringExtra(EXTRA_PATH)?.let(::File)
        folderName = gameFolder?.name.orEmpty()
        val symptoms = resources.getStringArray(R.array.report_symptoms)
        // A runtime that died after starting closed; one that died before its
        // first frame never started. The person can still change it.
        symptom = when {
            !intent.hasExtra(EXTRA_CRASH_REASON) -> symptoms.first()
            intent.getBooleanExtra(EXTRA_CRASH_BEFORE_START, false) -> symptoms.first()
            else -> symptoms[1]
        }
        findViewById<View>(R.id.symptomRow).setOnClickListener {
            Sheet(this).title(R.string.report_symptom_label).apply {
                symptoms.forEach { option ->
                    choice(option, current = option == symptom) {
                        symptom = option
                        findViewById<TextView>(R.id.symptomValue).text = symptom
                    }
                }
            }.show()
        }
        findViewById<TextView>(R.id.symptomValue).text = symptom
        findViewById<SwitchCompat>(R.id.includeLogSwitch).setOnCheckedChangeListener { _, checked ->
            // A report without the log is a report of a symptom with no
            // evidence, so say so plainly rather than letting it pass quietly.
            field(R.id.reportLog).isEnabled = checked
            findViewById<TextView>(R.id.includeLogNote).setTextColor(
                ContextCompat.getColor(this, if (checked) R.color.eh_text_secondary else R.color.eh_caution),
            )
        }
        findViewById<Button>(R.id.shareReportButton).setOnClickListener { share() }
        findViewById<Button>(R.id.copyReportButton).setOnClickListener { copy(); toast(R.string.report_copied) }
        findViewById<Button>(R.id.sendReportButton).setOnClickListener { sendToGithub() }

        Thread {
            val report = ProblemReport.gather(this, gameFolder)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                fill(report)
            }
        }.start()
    }

    private fun fill(report: ProblemReport) {
        // A blank game field used to block Send outright. For a report about
        // Enginehost itself (or any crash before the game was identified)
        // there is no game name to give, so the field falls back to the
        // engine family and version -- still something a reader can act on
        // -- and stays optional besides; see send().
        val engineLabel = "${report.engineLine} ${report.engineVersion}".trim()
        field(R.id.reportGame).setText(report.gameName.ifBlank { engineLabel.ifBlank { getString(R.string.app_name) } })
        field(R.id.reportEngine).setText("${report.engineLine} ${report.engineVersion}".trim())
        field(R.id.reportEnvironment).setText(report.environment())
        val crash = intent.getStringExtra(EXTRA_CRASH_REASON)?.let { reason ->
            buildString {
                append("Crash: ").append(reason)
                intent.getStringExtra(EXTRA_CRASH_TRACE)?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    append(ProblemReport.scrub(it, File(intent.getStringExtra(EXTRA_PATH).orEmpty())))
                }
            }
        }
        val combined = report.logSections(crash) { section ->
            getString(
                when (section) {
                    ProblemReport.Section.ENGINE -> R.string.report_engine_heading
                    ProblemReport.Section.EVENTS -> R.string.report_events_heading
                    ProblemReport.Section.SYSTEM_LOG -> R.string.report_system_log_heading
                },
            )
        }
        field(R.id.reportLog).setText(combined)
        log = combined
        findViewById<Button>(R.id.shareReportButton).isEnabled = true
        findViewById<Button>(R.id.copyReportButton).isEnabled = true
        findViewById<Button>(R.id.sendReportButton).isEnabled = true
    }

    private fun field(id: Int): EditText = findViewById(id)

    private fun text(id: Int): String = field(id).text.toString().trim()

    private fun includeLog(): Boolean = findViewById<SwitchCompat>(R.id.includeLogSwitch).isChecked

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /**
     * The report as it leaves the device: the one place its text is built, for
     * Share, Copy and the GitHub form alike. Every field is optional and the
     * person only has to press a button; the game field is prefilled (see
     * fill()) but editable, and its folder name is blanked from the rest of the
     * report when it is cleared.
     */
    private fun buildReport(): String {
        val game = text(R.id.reportGame)
        return ProblemReport.compose(
            game = game,
            engine = text(R.id.reportEngine),
            symptomHeading = getString(R.string.report_symptom_label),
            symptom = symptom,
            details = text(R.id.reportDetails),
            environment = text(R.id.reportEnvironment),
            logHeading = getString(R.string.report_log_heading),
            log = if (includeLog()) text(R.id.reportLog) else "",
            hide = if (game.isBlank()) listOf(folderName) else emptyList(),
        )
    }

    /** Account-free: hand the report to any app the person picks (email, a message, a note). */
    private fun share() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_share_subject, text(R.id.reportGame).ifBlank { getString(R.string.app_name) }))
            .putExtra(Intent.EXTRA_TEXT, buildReport())
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.report_share))) }
            .onFailure { toast(R.string.report_share_failed) }
    }

    private fun copy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.report_title), buildReport()))
    }

    /**
     * Needs a GitHub account. The form's address carries no report content, so
     * the report is put on the clipboard for the person to paste into it.
     */
    private fun sendToGithub() {
        copy()
        Toast.makeText(this, R.string.report_copied_paste, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, ProblemReportFormActivity::class.java)
                .putExtra(ProblemReportFormActivity.EXTRA_URL, ProblemReport.formUrl(text(R.id.reportEngine), symptom)),
        )
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val EXTRA_CRASH_REASON = "crashReason"
        private const val EXTRA_CRASH_TRACE = "crashTrace"
        private const val EXTRA_CRASH_BEFORE_START = "crashBeforeStart"

        /**
         * Report a game, or Enginehost itself when [gameFolder] is null --
         * a host-process crash ([HostCrashWatch]) has no game folder at all,
         * only a [crashReason] and [crashTrace], same as a game runtime one
         * ([CrashWatch]) reduces to once its own gameFolder is passed
         * separately. [beforeStart] says whether the runtime ever drew a
         * frame, which is the difference between the two symptoms a game
         * crash can be; meaningless (left false) for a host crash.
         */
        fun intent(
            context: Context,
            gameFolder: File?,
            crashReason: String? = null,
            crashTrace: String? = null,
            beforeStart: Boolean = false,
        ): Intent = Intent(context, ProblemReportActivity::class.java).apply {
            gameFolder?.let { putExtra(EXTRA_PATH, it.absolutePath) }
            crashReason?.let {
                putExtra(EXTRA_CRASH_REASON, it)
                putExtra(EXTRA_CRASH_TRACE, crashTrace.orEmpty())
                putExtra(EXTRA_CRASH_BEFORE_START, beforeStart)
            }
        }
    }
}
