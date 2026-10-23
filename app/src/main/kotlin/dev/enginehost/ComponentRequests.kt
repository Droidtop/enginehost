package dev.enginehost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * A component (a subplugin) a running game asks for (docs/engine-bundle-format.md, "A game asks,
 * and receives"; owner, 2026-10-23: "games asks, and it receives"). The engine calls
 * [dev.enginehost.api.EngineHost.requestComponent] when the game turns out to need one, and waits.
 *
 * Which version is the game's to say, read from its own files when it asks ([wantedVersion]); which
 * build is the newest installed and approved one of that version for this engine's series. When
 * there is none, [ComponentRequestActivity] opens over the game: install it, accept its licence, or
 * not now; its answer comes back through a file both processes see, and the waiting call returns.
 */
object ComponentRequests {
    private const val TAG = "EnginehostComponents"
    private const val POLL_MS = 250L

    /** The installed, approved component of [name] for [engine] at [runtimeVersion]: [version] when known, else the newest. */
    internal fun pick(
        components: List<InstalledPlugin>,
        approved: (InstalledPlugin) -> Boolean,
        engine: String,
        name: String,
        version: Version?,
        runtimeVersion: Version,
    ): InstalledPlugin? = components
        .filter { installed ->
            val component = installed.component ?: return@filter false
            component.engine == engine && component.name == name &&
                (version == null || component.version == version) &&
                component.supportedSeries.any(runtimeVersion::belongsTo) && approved(installed)
        }
        .maxWithOrNull(compareBy<InstalledPlugin>({ it.component!!.version }, { it.info.pluginVersion }, { it.bundleId }))

    /** The version of [name] the game's own files call for; null when they say nothing. */
    fun wantedVersion(gameFolder: File, name: String): Version? = runCatching {
        when (name) {
            SpineSkeletonScan.COMPONENT -> SpineSkeletonScan.requirementFor(FileGameTree.scan(gameFolder), null)[name]?.let(Version::parse)
            else -> null
        }
    }.getOrNull()

    /** The verified root of the component the game asked for, once it is installed and approved; null for none. */
    fun request(activity: Activity, gameFolder: File, engine: String, runtimeVersion: Version, name: String): File? {
        val version = wantedVersion(gameFolder, name)
        Log.i(TAG, "The game asks for $name${version?.let { " $it" }.orEmpty()} ($engine $runtimeVersion)")
        available(activity, engine, name, version, runtimeVersion)?.let { return it }
        val id = UUID.randomUUID().toString()
        activity.runOnUiThread {
            activity.startActivity(
                Intent(activity, ComponentRequestActivity::class.java)
                    .putExtra(ComponentRequestActivity.EXTRA_REQUEST, id)
                    .putExtra(ComponentRequestActivity.EXTRA_ENGINE, engine)
                    .putExtra(ComponentRequestActivity.EXTRA_NAME, name)
                    .putExtra(ComponentRequestActivity.EXTRA_VERSION, version?.toString())
                    .putExtra(ComponentRequestActivity.EXTRA_RUNTIME_VERSION, runtimeVersion.toString()),
            )
        }
        val answer = outcome(activity, id)
        while (!answer.exists()) {
            if (activity.isFinishing || activity.isDestroyed) return null
            SystemClock.sleep(POLL_MS)
        }
        val approved = answer.readText().trim() == APPROVED
        answer.delete()
        Log.i(TAG, "$name: ${if (approved) "received" else "not now"}")
        return if (approved) available(activity, engine, name, version, runtimeVersion) else null
    }

    private fun available(context: Context, engine: String, name: String, version: Version?, runtimeVersion: Version): File? {
        val trust = PluginTrustStore(context)
        val chosen = pick(PluginRegistry.components(context), trust::isApproved, engine, name, version, runtimeVersion) ?: return null
        // Verified like every bundle a launch loads: signature, pinned signer, payload stamps.
        return runCatching { InstalledBundleVerifier.verify(context, chosen); chosen.directory.canonicalFile }
            .onFailure { Log.w(TAG, "$name did not verify: $it") }
            .getOrNull()
    }

    /** Where [ComponentRequestActivity] writes its answer to request [id]. */
    internal fun outcome(context: Context, id: String): File =
        File(File(context.filesDir, "component-requests").apply { mkdirs() }, id)

    internal const val APPROVED = "approved"
    internal const val DECLINED = "declined"
}
