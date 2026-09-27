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
 * `LAUNCH` stays open to any app by design (owner, 2026-09-24), so this
 * does not reject a caller. What it decides is whether [GameRunner.run]
 * carries a label for [LaunchActivity]'s external-caller consent prompt:
 * see [unverifiedCallerLabel].
 */
class LaunchEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(LaunchActivity.EXTRA_PATH)?.let { path ->
            GameRunner.run(
                this,
                File(path),
                intent.getStringExtra(LaunchActivity.EXTRA_CONFIG),
                intent.getBooleanExtra(LaunchActivity.EXTRA_AUTOINSTALL, false),
                callerLabel = unverifiedCallerLabel(),
            )
        }
        finish()
    }

    /**
     * Null when Android's own record of who started this activity names
     * droidtop's package under droidtop's own signing certificate
     * ([TrustedCallers]); otherwise a label to show on the consent
     * [LaunchActivity] asks for before the game actually runs.
     *
     * Only a referrer derived with neither `EXTRA_REFERRER` nor
     * `EXTRA_REFERRER_NAME` present on the intent THIS activity received is
     * trusted: both are ordinary extras any caller can set on the intent it
     * sends, and `Activity.getReferrer()` prefers them over Android's own
     * record of the calling package -- so a caller supplying either is
     * treated as unverifiable even when it names droidtop, exactly the
     * caller that check exists to catch. When neither extra is present,
     * `referrer` can only have come from the system, which fills it from
     * the actual calling activity's package and cannot be spoofed by the
     * caller's own intent data.
     */
    private fun unverifiedCallerLabel(): String? {
        if (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)) {
            return getString(R.string.external_launch_unknown_caller)
        }
        val callerPackage = referrer?.host ?: return getString(R.string.external_launch_unknown_caller)
        if (TrustedCallers.isDroidtop(packageManager, callerPackage)) return null
        return callerPackage
    }
}
