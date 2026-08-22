package dev.enginehost

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Real placeholder, not wired up yet -- per-engine controller mapping is
 * genuinely separate work from getting an engine to run at all. Kept as
 * its own activity from the start so [MainActivity] has a real, stable
 * place to link to rather than bolting this in later.
 */
class ControllerConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Controller config not implemented yet." })
    }
}
