package dev.enginehost

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one way Enginehost does short work off the main thread (Droidtop/tracker#768): file,
 * database, network and SAF reads a screen needs, and the writes it starts.
 *
 * About a hundred hand-rolled `Thread { ... runOnUiThread { ... } }` blocks did this before, each
 * with its own daemon flag and its own check (or none) that the screen was still there when the
 * work finished. Here the work runs on one shared pool, and the part that touches views runs on
 * the main thread only while the screen that asked is alive: not destroyed and not finishing. A
 * screen that is gone drops the result rather than drawing into a dead window.
 *
 * A context that is not an activity (the application, a service) has no screen to outlive, so its
 * results always run. A failure in [work] with no [failed] handler is not caught, as it was not on
 * the threads this replaces.
 *
 * Long-lived workers that block on one thing for a whole run (a pipe reader, the watchdog, the
 * isolated runtime's frame loop) keep their own named threads; this is for work that finishes.
 */
object Async {
    private val pool: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "enginehost-async").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Runs [work] off the main thread, with nothing to show afterwards. */
    fun run(work: () -> Unit) {
        pool.execute(work)
    }

    /**
     * Runs [work] off the main thread, then [show] with its result on the main thread while
     * [owner]'s screen is alive. A failure goes to [failed] the same way when there is one.
     */
    fun <T> load(owner: Context, work: () -> T, failed: ((Throwable) -> Unit)? = null, show: (T) -> Unit) {
        pool.execute {
            if (failed == null) {
                val result = work()
                onMain(owner) { show(result) }
            } else {
                runCatching(work)
                    .onSuccess { result -> onMain(owner) { show(result) } }
                    .onFailure { error -> onMain(owner) { failed(error) } }
            }
        }
    }

    /** Runs [block] on the main thread while [owner]'s screen is alive, from any thread. */
    fun onMain(owner: Context, block: () -> Unit) {
        main.post { if (alive(owner)) block() }
    }

    /** Whether the screen behind [context] can still be drawn into. */
    fun alive(context: Context): Boolean {
        val activity = activityOf(context) ?: return true
        return !activity.isDestroyed && !activity.isFinishing
    }

    private fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
