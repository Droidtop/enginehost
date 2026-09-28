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
    /** The report is never blocked on the game field -- see [fill] -- so Send is always where the pad starts. */
    override fun primaryAction(): View? = findViewById(R.id.sendReportButton)

    private var log: String = ""
    private var symptom: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.report_title)
        setContentView(R.layout.activity_problem_report)
        wireBackButton()

        val gameFolder = intent.getStringExtra(EXTRA_PATH)?.let(::File)
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
        findViewById<Button>(R.id.sendReportButton).setOnClickListener { send() }
        findViewById<Button>(R.id.copyReportButton).setOnClickListener { copy() }

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
        val combined = listOfNotNull(crash, report.log.takeIf { it.isNotBlank() }).joinToString(BLANK_LINE)
        field(R.id.reportLog).setText(combined)
        log = combined
        findViewById<Button>(R.id.sendReportButton).isEnabled = true
        findViewById<Button>(R.id.copyReportButton).isEnabled = true
    }

    private fun field(id: Int): EditText = findViewById(id)

    private fun text(id: Int): String = field(id).text.toString().trim()

    private fun includeLog(): Boolean = findViewById<SwitchCompat>(R.id.includeLogSwitch).isChecked

    private fun send() {
        // Every field is optional: the person only has to press Send. The
        // game field is prefilled (see fill()) but editable, never required.
        val game = text(R.id.reportGame)
        val url = ProblemReport.formUrl(
            game = game,
            engine = text(R.id.reportEngine),
            symptom = symptom,
            details = text(R.id.reportDetails),
            environment = text(R.id.reportEnvironment),
            log = if (includeLog()) text(R.id.reportLog) else "",
        )
        startActivity(
            Intent(this, ProblemReportFormActivity::class.java)
                .putExtra(ProblemReportFormActivity.EXTRA_URL, url),
        )
    }

    private fun copy() {
        val text = buildString {
            appendLine("Game: ${text(R.id.reportGame)}")
            appendLine("Engine: ${text(R.id.reportEngine)}")
            appendLine("${getString(R.string.report_symptom_label)}: $symptom")
            text(R.id.reportDetails).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine()
            appendLine(text(R.id.reportEnvironment))
            text(R.id.reportLog).takeIf { includeLog() && it.isNotBlank() }?.let {
                appendLine()
                appendLine(getString(R.string.report_log_heading))
                append(it)
            }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.report_title), text))
        Toast.makeText(this, R.string.report_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val EXTRA_CRASH_REASON = "crashReason"
        private const val EXTRA_CRASH_TRACE = "crashTrace"
        private const val EXTRA_CRASH_BEFORE_START = "crashBeforeStart"
        private val BLANK_LINE = System.lineSeparator() + System.lineSeparator()

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
