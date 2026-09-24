package dev.enginehost

import android.util.Log

/**
 * The engine sandbox, applied to the `:runtime` process before any engine
 * code is loaded into it. Today it is the network half: after [apply] no
 * thread of the process, and no process it starts, can open an IP socket.
 * docs/engine-sandbox.md is the design, including what Android does not let
 * this process take away from itself (arbitrary file access, Binder).
 *
 * Applying it is best effort by decision: a device where the filter cannot
 * be installed (a 32-bit x86 build, a kernel that refuses it) still runs
 * games, as every device did before the sandbox existed, and says so in
 * the log under [TAG].
 */
object RuntimeSandbox {
    private const val TAG = "EnginehostSandbox"

    /** Once per process; the filter cannot be removed, and a second copy would add nothing. */
    fun apply() {
        val result = runCatching {
            System.loadLibrary("enginehost_sandbox")
            denyInternet()
        }
        when (val code = result.getOrNull()) {
            0 -> Log.i(TAG, "Network denied to every thread of the runtime process")
            1 -> Log.w(TAG, "Network denied only to the main thread and the threads it starts")
            null -> Log.w(TAG, "Network NOT denied: the sandbox library did not load", result.exceptionOrNull())
            else -> Log.w(TAG, "Network NOT denied: errno ${-code}")
        }
    }

    @JvmStatic private external fun denyInternet(): Int
}
