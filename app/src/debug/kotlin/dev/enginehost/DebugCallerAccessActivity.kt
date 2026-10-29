package dev.enginehost

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * ADB/CI harness; absent from release builds. Allows launches from adb, which a person allows once
 * in Settings > App launch access (docs/core-harness.md). The
 * conformance run on an emulator (docs/conformance-test-games.md, "Layer 1b") has no person to tap it.
 * `am start -n dev.enginehost/.DebugCallerAccessActivity`.
 */
class DebugCallerAccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // adb's launches arrive as the shell's package where Android names the caller (API 34 on the
        // emulator: "launch requested by com.android.shell"), and as the unrecognized caller where not.
        val store = CallerAccessStore(this)
        store.setDecision(LaunchCaller.UNKNOWN_KEY, CallerDecision.ALLOW)
        store.setDecision(SHELL, CallerDecision.ALLOW)
        Log.i("EnginehostDebug", "Launches from adb are allowed")
        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val SHELL = "com.android.shell"
    }
}
