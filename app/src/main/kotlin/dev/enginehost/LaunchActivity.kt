package dev.enginehost

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * The primary, intended entry point -- a caller (droidtop or anything
 * else) fires `ACTION dev.enginehost.LAUNCH` with `path` (an absolute
 * folder path, required) and optionally `config` (a raw JSON string in
 * the same shape as a real enginehost.json). `config` may fill fields the
 * folder config omitted, but cannot override values already present in
 * the folder -- see [EngineConfigReader.resolve].
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        GameRunner.run(this, File(path), intent.getStringExtra("config"))
    }
}
