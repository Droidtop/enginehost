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
    }
}
