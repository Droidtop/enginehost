package dev.enginehost

import android.app.ActivityManager
import android.content.Context
import android.os.Process

/**
 * The `:runtime` process games run in, seen from the main process.
 *
 * One game at a time runs there, and a new game starts only in a fresh one
 * (see LaunchActivity). A runtime still alive when a game is about to start
 * belongs to a game that is over: one that is ending, or one whose Java
 * crash Android is still holding. The second can last minutes: the crash
 * handler waits in ActivityManager for a crash dialog that an emulator or a
 * handheld may never show, with the process alive and its main thread
 * stopped, and a game started into it stayed black for over a minute (rig,
 * dq-ehfix-02). Such a process is ended here rather than waited for.
 */
internal object RuntimeProcess {
    fun pids(context: Context): List<Int> {
        val name = context.packageName + ":runtime"
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.runningAppProcesses.orEmpty().filter { it.processName == name }.map { it.pid }
    }

    fun alive(context: Context): Boolean = pids(context).isNotEmpty()

    /** Ends every runtime process; same app, same user, so ours to end. */
    fun kill(context: Context) {
        pids(context).forEach(Process::killProcess)
    }
}
