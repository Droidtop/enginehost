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

    private fun isDefaultProcess(): Boolean {
        val name = runCatching {
            File("/proc/self/cmdline").readBytes().toString(Charsets.UTF_8).substringBefore(Char(0))
        }.getOrNull() ?: return false
        return name == packageName
    }

    companion object {
        @Volatile lateinit var instance: EnginehostApplication
            private set

        private val LEGACY_RENPY_ROOT_NAMES = listOf("lib", "renpy", "main.py", "private.version")
    }
}
