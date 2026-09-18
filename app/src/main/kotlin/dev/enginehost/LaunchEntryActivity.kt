package dev.enginehost

import android.app.Activity
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
            )
        }
        finish()
    }
}
