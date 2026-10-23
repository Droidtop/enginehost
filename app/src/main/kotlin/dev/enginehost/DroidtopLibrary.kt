package dev.enginehost

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * droidtop's engine games, registered in Enginehost's library (Droidtop/tracker#547;
 * droidtop docs/plugin-api.md A7a, `library.read list_engine_games`). The owner,
 * 2026-10-23: "droidtop should also present games for enginehost to register, but
 * not necessarily their whole configs". Only identity and folder travel: a game's
 * config stays the person's, made in Game setup (docs/contract.md "Where a game's
 * config lives"), and nothing is written into any game's folder.
 */
object DroidtopLibrary {
    private const val TAG = "EnginehostDroidtop"
    private const val PREFS = "droidtop-library"
    private const val REGISTERED = "registered"
    private const val LAST_SYNC = "lastSync"

    /** Home's own refresh asks at most this often; a connect from droidtop always asks. */
    private const val RESUME_INTERVAL_MS = 10 * 60_000L

    /** [sync], unless one ran within [RESUME_INTERVAL_MS]. Blocks: off the main thread. */
    fun syncIfDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(LAST_SYNC, 0) < RESUME_INTERVAL_MS) return false
        return sync(context)
    }

    /**
     * Registers droidtop's engine games Enginehost does not have yet, and drops
     * the ones it registered earlier that droidtop no longer lists and whose
     * folder is gone. A game still on disk stays: dropping its row would take the
     * person's own choices for it with it. A game the person added themselves is
     * never dropped. True when Home changed. Blocks: off the main thread.
     */
    fun sync(context: Context): Boolean {
        if (!DroidtopSaves.installed(context) || !DroidtopSaves.ensureConnected(context)) return false
        val reply = DroidtopLink.call("library.read", "list_engine_games", JSONObject()) ?: return false
        val data = reply.takeIf { it.optBoolean("ok") }?.optJSONObject("data") ?: return false.also {
            Log.i(TAG, "droidtop did not list its engine games: ${reply.optJSONObject("error")?.optString("message")}")
        }
        if (!data.optBoolean("ready", true)) return false
        val games = data.optJSONArray("games") ?: return false
        val listed = (0 until games.length()).mapNotNull { games.optJSONObject(it)?.optString("path") }
            .filter { it.isNotBlank() && File(it).isDirectory }.toSet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val registered = prefs.getStringSet(REGISTERED, emptySet()).orEmpty().toMutableSet()
        val store = GameLibraryStore(context)
        val onHome = store.addedPaths()
        val added = (listed - onHome).toList()
        store.addPaths(added)
        registered += added
        val dropped = if (data.optBoolean("complete", true)) {
            registered.filter { it !in listed && !File(it).exists() }
        } else {
            emptyList()
        }
        if (dropped.isNotEmpty()) store.remove(dropped)
        registered -= dropped.toSet()
        prefs.edit().putStringSet(REGISTERED, registered).putLong(LAST_SYNC, System.currentTimeMillis()).apply()
        if (added.isNotEmpty() || dropped.isNotEmpty()) Log.i(TAG, "droidtop's games: ${added.size} registered, ${dropped.size} dropped")
        return added.isNotEmpty() || dropped.isNotEmpty()
    }
}
