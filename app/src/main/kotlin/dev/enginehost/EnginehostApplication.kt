package dev.enginehost

import android.app.Application
import java.io.File

class EnginehostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Guaranteed to run before this process has had any chance to call
        // EngineBundleInstaller.install() itself; see sweepOrphanedStaging
        // for why that ordering, plus the default-process check below, is
        // what makes clearing out interrupted-install leftovers safe here.
        // Restricted to the default process: RuntimeActivity and
        // BundledActivityProxy run in a separate ":runtime" process, and
        // its own onCreate has no guarantee the default process is not
        // mid-install at that same moment.
        if (isDefaultProcess()) {
            runCatching { EngineBundleInstaller.sweepOrphanedStaging(this) }
            runCatching { removeSharedRenpyTree() }
        }
        // The sandbox goes on before anything else in either runtime
        // process, so no engine code ever runs without it. Layer 2
        // (android:isolatedProcess) adds a fresh UID and no permissions of
        // its own on top of this, it does not replace it: an isolated
        // process is still a normal Linux process that can open an IP
        // socket unless something stops it, exactly like the shared-uid
        // ":runtime" process could before this filter existed. Missing
        // this on ":runtime_isolated" was a real gap -- caught reviewing
        // dq-sandbox-05/06's own findings, not by design.
        if (isRuntimeProcess() || isIsolatedRuntimeProcess()) RuntimeSandbox.apply()
        // Games run here, and only here. Registering the tap this early is
        // what gives the host first look at the pad inside a bundled
        // plugin's own Activity, which nothing of ours is otherwise on the
        // path of; see RuntimeInputTap. Meaningless in ":runtime_isolated",
        // which never hosts an Activity (docs/engine-sandbox.md "Layer 2").
        if (isRuntimeProcess()) registerActivityLifecycleCallbacks(RuntimeInputInstaller)
        if (isRuntimeProcess()) RuntimeClassLoader.installBelowApi29(this)
    }

    /**
     * Until 2026-09-05 every Ren'Py line unpacked its engine into the root
     * of this app's files dir, as stock RAPT does; the lines now unpack
     * into renpy-engine/<line>. What the old layout left behind is dead
     * weight (about 11 MB) that no code reads any more, and only the host
     * should delete at the root of its own files dir. Exact names only.
     */
    private fun removeSharedRenpyTree() {
        for (name in LEGACY_RENPY_ROOT_NAMES) {
            File(filesDir, name).takeIf { it.exists() }?.deleteRecursively()
        }
    }

    private fun isDefaultProcess(): Boolean = processName() == packageName

    /** The process RuntimeActivity and BundledActivityProxy are declared in. */
    private fun isRuntimeProcess(): Boolean = processName() == "$packageName:runtime"

    /** IsolatedRuntimeService's own android:isolatedProcess process (docs/engine-sandbox.md "Layer 2"). */
    private fun isIsolatedRuntimeProcess(): Boolean = processName() == "$packageName:runtime_isolated"

    private fun processName(): String? = runCatching {
        File("/proc/self/cmdline").readBytes().toString(Charsets.UTF_8).substringBefore(Char(0))
    }.getOrNull()

    companion object {
        @Volatile lateinit var instance: EnginehostApplication
            private set

        private val LEGACY_RENPY_ROOT_NAMES = listOf("lib", "renpy", "main.py", "private.version")
    }
}
