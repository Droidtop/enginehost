package dev.enginehost

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * The primary, intended entry point -- a caller (droidtop or anything
 * else) fires `ACTION dev.enginehost.LAUNCH` with a single extra, `path`
 * (an absolute folder path), and enginehost takes it from there by reading
 * that folder's own enginehost.json (see [EngineConfigReader]). No other
 * extras, no catalog, no import step.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        GameRunner.run(this, File(path))
    }
}
