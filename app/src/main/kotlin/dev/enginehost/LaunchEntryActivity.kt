package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File

/**
 * Where `dev.enginehost.LAUNCH` arrives. It shows nothing and keeps
 * nothing: it reads the request and hands it to [GameRunner.run], the same
 * call every launch inside this app makes, then it is gone. The contract
 * (the action, the "path" extra, the optional "config" and
 * "autoinstallPlugin") is documented on its manifest entry.
 *
 * It exists because the launch screen cannot be the exported door itself:
 * see [LaunchActivity.start] for what Android does with a second launch
 * intent aimed straight at a live game task.
 *
 * Programmatic launches from any app are the point of this door (owner,
 * 2026-09-27: "Enginehost's primary focus is programmatic engine launches.
 * The ENTIRE point is letting apps do that."), so this never refuses a
 * caller itself. What [caller] decides is entirely downstream, in
 * [LaunchActivity]: whether the person allowed, blocked, or has not yet
 * been asked about this exact caller (`CallerAccessStore`,
 * `CallerDefaults`). See docs/security/2026-09-27-launch-entry-consent.md.
 */
class LaunchEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_END_GAME) {
            endGame()
            finish()
            return
        }
        intent.getStringExtra(LaunchActivity.EXTRA_PATH)?.let { path ->
            // The caller's accent, when it names one, draws this session's screens (Accent.sessionAccent);
            // a launch that names none is drawn in the person's own choice.
            Accent.sessionAccent = Accent.parse(intent.getStringExtra(EXTRA_ACCENT))
            GameRunner.run(
                this,
                File(path),
                intent.getStringExtra(LaunchActivity.EXTRA_CONFIG),
                intent.getBooleanExtra(LaunchActivity.EXTRA_AUTOINSTALL, false),
                caller = caller(),
            )
        }
        finish()
    }

    /**
     * `dev.enginehost.END_GAME`: ends the running game, for droidtop's Kill when it cannot do so
     * itself. Unlike LAUNCH it is answered to droidtop alone, by signature ([TrustedCallers]):
     * ending a game someone is playing is not something any app may do. The answer comes back as the
     * activity result when the caller used startActivityForResult: RESULT_OK with `ended` true when a
     * game was running and is now gone, false when none was running; RESULT_CANCELED with `error`
     * "untrusted_caller" for anyone else, in which case nothing is touched.
     */
    private fun endGame() {
        val caller = callingPackage ?: referrer?.host
        val trusted = !intent.hasExtra(Intent.EXTRA_REFERRER) && !intent.hasExtra(Intent.EXTRA_REFERRER_NAME) &&
            caller != null && TrustedCallers.isDroidtop(packageManager, caller)
        if (!trusted) {
            setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, ERROR_UNTRUSTED_CALLER))
            return
        }
        val wasRunning = RuntimeProcess.alive(this)
        // Disarmed first, as the in-game Quit does, so ending on purpose is never offered back as a crash.
        CrashWatch.quit(this)
        RuntimeProcess.kill(this)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ENDED, wasRunning))
    }

    /** Who started this activity: [LaunchCaller.of]. */
    private fun caller(): LaunchCaller = LaunchCaller.of(this)

    companion object {
        const val ACTION_END_GAME = "dev.enginehost.END_GAME"
        const val EXTRA_ENDED = "ended"

        /** Sent to the launching app's package when its game ends (docs/contract.md, Droidtop/tracker#580). */
        const val ACTION_GAME_ENDED = "dev.enginehost.GAME_ENDED"
        const val EXTRA_GAME_PATH = "path"
        const val EXTRA_SAVE_FOLDER = "saveFolder"
        const val EXTRA_REASON = "reason"

        /** On a launch flagged savesSynced whose saveRoot was not taken: why (SaveRoot's reasons). */
        const val EXTRA_SAVE_ROOT_REFUSED = "saveRootRefused"
        const val EXTRA_ERROR = "error"

        /** On dev.enginehost.LAUNCH: the caller's accent, "#RRGGBB", for the screens of the session it starts. */
        const val EXTRA_ACCENT = "accent"
        const val ERROR_UNTRUSTED_CALLER = "untrusted_caller"
    }
}
