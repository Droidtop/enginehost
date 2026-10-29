package dev.enginehost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.droidtop.pluginhost.IAppInterfaceDoor
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Saves go where droidtop keeps them, whichever app started the game
 * (Droidtop/tracker#547; droidtop docs/plugin-api.md A7a; docs/contract.md
 * "droidtop's plugin interface"). The owner, 2026-10-23: "It shouldn't matter,
 * honestly. The two applications should detect and work with each other over
 * the plugin API so no matter what, saves go where they're needed".
 *
 * Before a launch droidtop did not make, Enginehost asks droidtop's `saves where`
 * and launches with the answer exactly as if the caller had passed it
 * (`saveRoot`, `savesSynced`: the one mechanism a caller's save folder already has).
 * After such a game ends it tells droidtop with `saves report_changed`.
 * Without droidtop installed, or with no answer, nothing waits and nothing changes.
 */
object DroidtopSaves {
    private const val TAG = "EnginehostDroidtop"
    private const val DOOR_ACTION = "dev.droidtop.plugin.APP_INTERFACE_DOOR"

    /** How long a broker may take to arrive after a knock. */
    private const val CONNECT_MS = 5_000L

    /** droidtop may sync a game's saves down first, and may ask the person about a conflict. */
    private const val WHERE_MS = 120_000L

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(DroidtopLink.DROIDTOP_PACKAGE, 0) }.isSuccess

    /**
     * [inlineJson] with droidtop's save folder for [gameFolder] added, when droidtop
     * keeps one; else [inlineJson] unchanged. Only for a launch droidtop did not
     * make ([callerPackage]) that names no `saveRoot` of its own. Blocks: off the main thread.
     */
    fun withDroidtopSaveRoot(context: Context, gameFolder: File, inlineJson: String?, callerPackage: String?): String? {
        if (callerPackage == DroidtopLink.DROIDTOP_PACKAGE) return inlineJson
        val inline = inlineJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (inlineJson != null && inline == null) return inlineJson
        if (inline?.has(SaveRoot.KEY) == true) return inlineJson
        if (!installed(context) || !ensureConnected(context)) return inlineJson
        val reply = withDeadline(WHERE_MS) {
            DroidtopLink.call("saves", "where", JSONObject().put("path", gameFolder.absolutePath))
        } ?: return inlineJson.also { Log.i(TAG, "droidtop gave no save folder in time; the game saves in Enginehost's own") }
        val data = reply.takeIf { it.optBoolean("ok") }?.optJSONObject("data") ?: return inlineJson
        if (!data.optBoolean("known")) return inlineJson
        val synced = data.optBoolean("synced", false)
        val root = data.optString("saveRoot").takeIf { !data.isNull("saveRoot") && it.isNotBlank() }
        if (root == null && !synced) return inlineJson
        val merged = inline ?: JSONObject()
        root?.let { merged.put(SaveRoot.KEY, it) }
        if (synced) merged.put(SaveRoot.SYNCED_KEY, true)
        Log.i(TAG, "droidtop keeps this game's saves" + (root?.let { " in $it" } ?: "") + if (synced) " (synced)" else "")
        return merged.toString()
    }

    /** Tells droidtop that the game at [gameFolder] wrote saves in [saveFolder]. Runs off the main thread ([Async]). */
    fun reportChanged(context: Context, gameFolder: File, saveFolder: String, reason: String, endedAtMs: Long) {
        val app = context.applicationContext
        Async.run {
            if (!installed(app) || !ensureConnected(app)) return@run
            val reply = DroidtopLink.call(
                "saves", "report_changed",
                JSONObject().put("path", gameFolder.absolutePath).put("saveFolder", saveFolder)
                    .put("reason", reason).put("endedAtMs", endedAtMs),
            )
            Log.i(TAG, "told droidtop about saves in $saveFolder: ${reply?.optJSONObject("data")?.optString("message") ?: "no answer"}")
        }
    }

    /**
     * Announces Enginehost to droidtop once as the app starts: apps with an
     * application-interface layer register themselves (owner, Droidtop/tracker#547).
     * One knock, off the main thread, only when droidtop is installed and has not
     * connected; the answer is not needed (true: droidtop connected; false: it is
     * not added yet, and droidtop tells the person).
     */
    fun announce(context: Context) {
        val app = context.applicationContext
        Async.run {
            runCatching { if (installed(app) && !DroidtopLink.isConnected()) knock(app) }
        }
    }

    /**
     * A broker, knocking on droidtop's door when there is none (droidtop binds
     * this app's service and hands one over). False when none came in time.
     */
    fun ensureConnected(context: Context): Boolean {
        if (DroidtopLink.isConnected()) return true
        if (!knock(context)) return false
        // droidtop binds back and hands a broker over; its arrival wakes this (Droidtop/tracker#768: a 100 ms poll before).
        return DroidtopLink.awaitConnected(CONNECT_MS)
    }

    /** droidtop's door (`IAppInterfaceDoor.knock`): it carries no data; droidtop decides whether to bind back. */
    private fun knock(context: Context): Boolean {
        val connected = CountDownLatch(1)
        var door: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                door = service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(DOOR_ACTION).setPackage(DroidtopLink.DROIDTOP_PACKAGE)
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) return false
        return try {
            connected.await(CONNECT_MS, TimeUnit.MILLISECONDS)
            door?.let { runCatching { IAppInterfaceDoor.Stub.asInterface(it).knock() }.getOrDefault(false) } ?: false
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    /** [block] on its own thread, waited for at most [ms]; null when it took longer or failed. */
    private fun <T> withDeadline(ms: Long, block: () -> T?): T? {
        var result: T? = null
        val worker = Thread { result = runCatching(block).getOrNull() }
        worker.start()
        worker.join(ms)
        return if (worker.isAlive) null else result
    }
}
