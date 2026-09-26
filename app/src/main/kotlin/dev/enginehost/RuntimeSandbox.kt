package dev.enginehost

import android.util.Log

/**
 * The engine sandbox, applied to `:runtime` AND `:runtime_isolated` before
 * any engine code runs in either. Today it is the network half: after
 * [apply] no thread of the process, and no process it starts, can open an
 * IP socket. docs/engine-sandbox.md is the design, including what Android
 * does not let a process take away from itself (arbitrary file access,
 * Binder).
 *
 * Layer 2 (android:isolatedProcess, EnginehostApplication's own
 * `":runtime_isolated"` check) is additional to this, not a replacement
 * for it: a fresh UID with no permissions of its own still starts as an
 * ordinary Linux process that can open a socket unless something stops
 * it. This filter is a plain syscall deny-list (socket(AF_INET/AF_INET6),
 * io_uring_setup, cross-arch calls) with everything else -- including the
 * pread/pwrite/memfd_create/fcntl this milestone's own fd-passing and
 * shared-memory mechanisms use on both sides of the isolation boundary --
 * falling through to ALLOW, so it needed no new rule to cover the
 * isolated process's own I/O; see `runtime_sandbox.c`'s own filter
 * program for the exact instruction-by-instruction proof of that. It
 * returns EACCES for what it denies, never SIGSYS/SECCOMP_RET_KILL, so
 * this filter itself cannot be the source of a process death; a SIGSYS
 * seen on either process would have to come from the platform's own
 * filter underneath this one (installed by zygote before either of these
 * ever run), which this code does not touch and does not suppress the
 * kernel's own audit trail for.
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
