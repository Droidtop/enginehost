package dev.enginehost

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dev.droidtop.pluginhost.IPluginHostBroker
import dev.droidtop.pluginhost.IPluginRuntime
import dev.droidtop.pluginhost.IPluginRuntimeCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Enginehost as one of droidtop's plugins: an `app_interface` plugin, which is
 * this app itself (Droidtop/tracker#547; droidtop docs/plugin-api.md 1.3, and
 * A7a "Where saves are, both ways").
 *
 * The owner, 2026-10-23: "The two applications should detect and work with each
 * other over the plugin API so no matter what, saves go where they're needed".
 * droidtop binds this service with the action [ACTION] and talks to it through
 * droidtop's own AIDL (copied unchanged under src/main/aidl). It hands over a
 * broker, the only way Enginehost reaches droidtop ([DroidtopLink]).
 *
 * The service answers droidtop and nothing else: the calling uid must be
 * droidtop's package, signed with a certificate pinned here ([DroidtopLink.isDroidtop]).
 * Enginehost never assumes droidtop exists: with droidtop absent, this is never bound.
 */
class DroidtopInterfaceService : Service() {
    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == ACTION) Runtime(applicationContext) else null

    private class Runtime(private val context: Context) : IPluginRuntime.Stub() {
        private fun caller(): Boolean = DroidtopLink.isDroidtop(context, Binder.getCallingUid()).also {
            if (!it) Log.w(TAG, "refused a call from uid ${Binder.getCallingUid()}: not droidtop")
        }

        override fun registerCallback(callback: IPluginRuntimeCallback?) {}
        override fun unregisterCallback(callback: IPluginRuntimeCallback?) {}

        override fun loadPlugin(pluginId: String?, installDir: String?, entryClass: String?, rootApproved: Boolean, broker: IPluginHostBroker?) = false

        override fun loadContained(
            pluginId: String?,
            manifestJson: String?,
            files: Array<out ParcelFileDescriptor>?,
            names: Array<out String>?,
            broker: IPluginHostBroker?,
        ): Boolean {
            files?.forEach { runCatching { it.close() } }
            if (!caller() || pluginId == null || broker == null) return false
            DroidtopLink.connected(pluginId, broker)
            // droidtop connected: list its engine games now (A7a: "The app asks again when droidtop connects").
            Thread { runCatching { DroidtopLibrary.sync(context) } }.apply { isDaemon = true }.start()
            return true
        }

        override fun reachability(): String? = null
        override fun attachScreen(
            pluginId: String?, entrypoint: String?, library: String?, surface: Surface?,
            frames: ParcelFileDescriptor?, framesCapacity: Long, width: Int, height: Int, density: Float,
        ): String? = null
        override fun resizeScreen(pluginId: String?, width: Int, height: Int) {}
        override fun screenTouch(pluginId: String?, event: MotionEvent?) {}
        override fun screenKey(pluginId: String?, event: KeyEvent?) {}
        override fun detachScreen(pluginId: String?) {}
        override fun lastLoadError(pluginId: String?): String? = null

        override fun unloadPlugin(pluginId: String?) {
            if (caller() && pluginId != null) DroidtopLink.disconnected(pluginId)
        }

        override fun invoke(pluginId: String?, capability: String?, argsJson: String?): String =
            error("UNSUPPORTED", "Enginehost answers only through handle")

        override fun handle(pluginId: String?, envelopeJson: String?): String {
            if (!caller()) return error("PERMISSION_DENIED", "Only droidtop may call Enginehost here")
            val envelope = runCatching { JSONObject(envelopeJson ?: "") }.getOrNull()
                ?: return error("INVALID_ARGS", "The call was not readable")
            val point = envelope.optString("point")
            val op = envelope.optString("op")
            return try {
                when {
                    point == "saves.locations" && op == "list" ->
                        ok(SaveLocations.list(context, envelope.optJSONObject("args") ?: JSONObject()))
                    else -> error("UNSUPPORTED", "Enginehost does not provide $point $op")
                }
            } catch (e: IllegalArgumentException) {
                error("INVALID_ARGS", e.message ?: "The call's arguments were not usable")
            } catch (e: Exception) {
                Log.w(TAG, "handle $point $op failed", e)
                error("FAILED", "Enginehost could not answer")
            }
        }

        override fun startJob(pluginId: String?, capability: String?, argsJson: String?, jobId: String?) = false
        override fun cancelJob(pluginId: String?, jobId: String?) {}
        override fun attachRelay(pluginId: String?, name: String?, relay: IBinder?) {}
    }

    companion object {
        const val ACTION = "dev.droidtop.plugin.APP_INTERFACE"
        private const val TAG = "EnginehostDroidtop"

        internal fun ok(data: JSONObject): String = JSONObject().put("ok", true).put("data", data).toString()
        internal fun error(code: String, message: String): String =
            JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message)).toString()
    }
}

/**
 * droidtop's `saves.locations@1`: where Enginehost keeps each game's saves,
 * as facts for droidtop's save model (droidtop writes nothing because of them alone).
 */
internal object SaveLocations {
    /** `{games: [{gameId, path, engine?, title?}]}` -> `{games: [{gameId, locations: [{path, place, engineNames?}]}]}`. */
    fun list(context: Context, args: JSONObject): JSONObject {
        val games = args.optJSONArray("games") ?: throw IllegalArgumentException("games is missing")
        val answers = JSONArray()
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("gameId").takeIf { it.isNotBlank() } ?: continue
            val path = game.optString("path").takeIf { it.isNotBlank() } ?: continue
            answers.put(JSONObject().put("gameId", gameId).put("locations", locationsOf(context, File(path))))
        }
        return JSONObject().put("games", answers)
    }

    private fun locationsOf(context: Context, folder: File): JSONArray {
        val out = JSONArray()
        val config = runCatching { GameRunner.launchConfig(context, folder) }.getOrNull() ?: return out
        val saves = SaveLocationStore(context)
        val root = saves.rootFor(config.engine)
        when (SaveFolders.placeOf(config.engine, config.engineContext)) {
            SaveFolders.Place.BESIDE_THE_GAME -> out.put(location(folder, "game_folder"))
            SaveFolders.Place.NAMED_BY_THE_HOST -> out.put(location(saves.saveFolderPathFor(config), "runner_folder"))
            SaveFolders.Place.ENGINE_NAMESPACE -> {
                val saveRoot = File(root, "saves")
                // The folder the engine itself named, when it reported one (EngineSaveFolders); else the root it names inside.
                val reported = EngineSaveFolders.recorded(context, folder, saveRoot)
                if (reported != null) out.put(location(reported, "runner_folder"))
                else out.put(location(saveRoot, "runner_root").put("engineNames", true))
            }
        }
        // A synced launch's own folder for this game (docs/contract.md "synced-saves"), when one was made.
        SaveLocationStore.syncedFolderIn(root, folder).takeIf { it.isDirectory }?.let { out.put(location(it, "runner_folder")) }
        return out
    }

    private fun location(path: File, place: String) = JSONObject().put("path", path.absolutePath).put("place", place)
}

/**
 * Enginehost's line to droidtop: the broker droidtop handed over in
 * `loadContained`, while it is loaded. Every call Enginehost makes to droidtop
 * goes through [call]; without a broker there is none to make.
 */
object DroidtopLink {
    const val DROIDTOP_PACKAGE = "dev.droidtop.app"

    /**
     * SHA-256 of droidtop's APK signing certificate (lowercase hex), the only
     * signer Enginehost answers. Empty until droidtop's digest is posted on
     * Droidtop/tracker#547, and until then Enginehost answers nobody.
     */
    internal val DROIDTOP_CERTS: Set<String> = emptySet()

    @Volatile private var broker: IPluginHostBroker? = null
    @Volatile private var pluginId: String? = null

    internal fun connected(id: String, given: IPluginHostBroker) {
        pluginId = id
        broker = given
    }

    internal fun disconnected(id: String) {
        if (pluginId == id) {
            broker = null
            pluginId = null
        }
    }

    fun isConnected(): Boolean = broker != null

    /** One broker call: `{api, version, op, args}`; the parsed `{ok, data|error}` reply, or null with no broker or no answer. */
    fun call(api: String, op: String, args: JSONObject, version: Int = 1): JSONObject? {
        val current = broker ?: return null
        val request = JSONObject().put("api", api).put("version", version).put("op", op).put("args", args)
        return runCatching { JSONObject(current.call(request.toString())) }.getOrElse {
            // A dead binder: droidtop went away. The next knock connects again.
            broker = null
            null
        }
    }

    /** Whether [uid] is droidtop: its package, signed with a pinned certificate. */
    fun isDroidtop(context: Context, uid: Int): Boolean {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: return false
        if (DROIDTOP_PACKAGE !in packages) return false
        val signers = signerDigests(pm, DROIDTOP_PACKAGE)
        return signers.isNotEmpty() && signers.all { it in DROIDTOP_CERTS }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(pm: PackageManager, packageName: String): Set<String> = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching emptySet<String>()
            // A rotated key's current signer is the last in its history; several signers must all be pinned.
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory.takeLast(1).toTypedArray()
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }.getOrDefault(emptySet())
}
