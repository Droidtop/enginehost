package dev.enginehost

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Asks the person what to do about a game that shows nothing or has stopped
 * responding, while the game keeps running beneath (owner, 2026-10-22: the
 * person chooses; nothing is ended for them). Started by the runtime's own
 * watchers, [RuntimeLoadingNotice] for a black screen and [RuntimeWatchdog]
 * for a hang, and drawn here in the main process because a hung runtime
 * cannot draw anything. A transparent screen over the game's task: Keep
 * waiting (also B) puts the game back in front, End game ends it and the
 * launch screen says why, Report a problem opens the report.
 */
class GameTroubleActivity : Activity() {
    private var sheet: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ask()
    }

    /** Asked again about the same game (a hang that goes on): the question is drawn afresh, not stacked. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ask()
    }

    private fun ask() {
        sheet?.setOnDismissListener(null)
        sheet?.dismiss()
        val hang = intent.getStringExtra(EXTRA_KIND) == HANG
        val folder = intent.getStringExtra(EXTRA_PATH)?.let(::File)
        val name = folder?.name ?: getString(R.string.app_name)
        val reason = getString(if (hang) R.string.trouble_hang_reason else R.string.trouble_black_reason)
        HostEvents.record(this, "asked about the game: $reason")
        sheet = Sheet(this)
            .title(getString(if (hang) R.string.trouble_hang_title else R.string.trouble_black_title, name))
            .message(if (hang) R.string.trouble_hang_message else R.string.trouble_black_message)
            .choice(R.string.trouble_keep_waiting) { HostEvents.record(this, "kept waiting") }
            .choice(R.string.trouble_end_game, Sheet.Tone.DANGER) { end(reason) }
            .choice(R.string.action_report) {
                val symptom = if (hang) ProblemReportActivity.SYMPTOM_SOMETHING_ELSE else ProblemReportActivity.SYMPTOM_BLACK_SCREEN
                startActivity(ProblemReportActivity.intent(this, folder, reason, "", symptom = symptom))
            }
            .onDismiss { finish() }
            .show()
    }

    /** The launch screen beneath learns the reason from the session note once the runtime is gone. */
    private fun end(reason: String) {
        HostEvents.record(this, "ended the game: $reason")
        CrashWatch.recordFailure(this, reason)
        RuntimeProcess.kill(this)
    }

    override fun onDestroy() {
        sheet?.setOnDismissListener(null)
        sheet?.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "enginehost"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_PATH = "path"
        const val BLACK_SCREEN = "black-screen"
        const val HANG = "hang"

        /** From the `:runtime` process, on any thread. */
        fun ask(context: Context, kind: String, gamePath: String?) {
            runCatching {
                context.startActivity(
                    Intent(context, GameTroubleActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_KIND, kind)
                        .putExtra(EXTRA_PATH, gamePath),
                )
            }.onFailure { Log.w(TAG, "Could not ask about the game ($kind)", it) }
        }
    }
}
