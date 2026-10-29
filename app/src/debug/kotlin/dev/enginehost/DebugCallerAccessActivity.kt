package dev.enginehost

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * ADB/CI harness; absent from release builds. Allows launches from the unrecognized-caller bucket
 * (adb), which a person allows once in Settings > App launch access (docs/core-harness.md). The
 * conformance run on an emulator (docs/conformance-test-games.md, "Layer 1b") has no person to tap it.
 * `am start -n dev.enginehost/.DebugCallerAccessActivity`.
 */
class DebugCallerAccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallerAccessStore(this).setDecision(LaunchCaller.UNKNOWN_KEY, CallerDecision.ALLOW)
        Log.i("EnginehostDebug", "Launches from adb are allowed")
        setResult(RESULT_OK)
        finish()
    }
}
