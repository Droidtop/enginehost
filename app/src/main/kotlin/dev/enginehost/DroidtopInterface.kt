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
            Async.run { runCatching { DroidtopLibrary.sync(context) } }
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
                    point == "engines.cores" && op == "list_cores" -> ok(EngineCores.list(context))
                    point == "saves.roots" && op == "roots" ->
                        ok(SaveRoots.roots(context, envelope.optJSONObject("args") ?: JSONObject()))
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
 * droidtop's `saves.roots@1` (Droidtop/tracker#546 section 8, checks C10 and D12; shape on #547):
 * where Enginehost keeps saves, as roots. droidtop finds the saves inside them with each game's
 * own save rule and writes nothing because of this answer alone.
 */
internal object SaveRoots {
    /**
     * `{games: [{entryId, folder, engine, context?}]}` -> `{default, families: {<engine>: path}, games: [{entryId, folder?}]}`.
     * Nothing is created to answer: a root that does not exist yet is named as it will be.
     */
    fun roots(context: Context, args: JSONObject): JSONObject {
        val games = args.optJSONArray("games") ?: throw IllegalArgumentException("games is missing")
        val store = SaveLocationStore(context)
        val asked = (0 until games.length()).mapNotNull { games.optJSONObject(it) }.mapNotNull { game ->
            val entryId = game.optString("entryId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val folder = game.optString("folder").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            entryId to hostNamedFolder(context, store, File(folder))
        }
        return answer(
            default = File(store.root(), SAVES),
            families = store.overrides().mapValues { (_, root) -> File(root, SAVES) },
            games = asked,
        )
    }

    /**
     * The reply from its parts. A family is listed only where its root differs from [default];
     * a game's folder only where the host names it, so droidtop never reads a folder of ours as
     * the place an engine chose for itself.
     */
    internal fun answer(default: File, families: Map<String, File>, games: List<Pair<String, File?>>): JSONObject {
        val familyRoots = JSONObject()
        families.filterValues { it.absolutePath != default.absolutePath }
            .toSortedMap()
            .forEach { (family, root) -> familyRoots.put(family, root.absolutePath) }
        val answers = JSONArray()
        games.forEach { (entryId, folder) ->
            answers.put(JSONObject().put("entryId", entryId).apply { folder?.let { put("folder", it.absolutePath) } })
        }
        return JSONObject().put("default", default.absolutePath).put("families", familyRoots).put("games", answers)
    }

    /**
     * The game's own folder inside its family's root, when Enginehost names it: an engine whose
     * save folder the host names (HTML, Flash/AIR, CatSystem2, web RPG Maker MV/MZ), or the
     * per-game folder a synced launch made (docs/contract.md "synced-saves"). Null where the
     * engine names its own folder or saves beside the game; droidtop's save rules find those.
     */
    private fun hostNamedFolder(context: Context, store: SaveLocationStore, gameFolder: File): File? {
        val config = runCatching { GameRunner.launchConfig(context, gameFolder) }.getOrNull() ?: return null
        if (SaveFolders.placeOf(config.engine, config.engineContext) == SaveFolders.Place.NAMED_BY_THE_HOST) {
            return store.saveFolderPathFor(config)
        }
        return SaveLocationStore.syncedFolderIn(store.rootFor(config.engine), gameFolder).takeIf { it.isDirectory }
    }

    /** The folder name every save root ends in (`SaveLocationStore.saveRootFor`; D12 checks it). */
    private const val SAVES = "saves"
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
     * signer Enginehost answers: droidtop's release key, read from the v2
     * signing block of droidtop.apk v0.2.0-dev.2257 (Droidtop/tracker#547).
     * A debug build of droidtop is signed differently and is not answered.
     */
    internal val DROIDTOP_CERTS: Set<String> = setOf(
        "38b1ef4bc47fa8cd62ffcbb43fa85d7f450e9c2d4a8b690776d2218b23f0749f",
    )

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
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        return DROIDTOP_PACKAGE in packages && isInstalled(context)
    }

    /** Whether droidtop's package is installed and signed with a pinned certificate: an app that only took its name is not. */
    fun isInstalled(context: Context): Boolean {
        val signers = signerDigests(context.packageManager, DROIDTOP_PACKAGE)
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
