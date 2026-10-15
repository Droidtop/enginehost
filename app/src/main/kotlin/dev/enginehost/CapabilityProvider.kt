package dev.enginehost

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Read-only advisory view of installed, verified engine-bundle capabilities. */
class CapabilityProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.pathSegments == listOf(PATH)) { "Unknown capability URI" }
        require(selection == null && selectionArgs == null) { "Capability filtering is not supported" }
        val requested = projection?.toList() ?: COLUMNS
        require(requested.all(COLUMNS::contains)) { "Unknown capability column" }
        return MatrixCursor(requested.toTypedArray()).apply {
            PluginRegistry.discover(requireNotNull(context)).forEach { plugin ->
                plugin.info.capabilities.forEach { capability ->
                    val values = mapOf(
                        BUNDLE_ID to plugin.bundleId,
                        ENGINE to plugin.info.engine,
                        ENGINE_CONTEXT to capability.engineContext,
                        PLUGIN_VERSION to plugin.info.pluginVersion.toString(),
                        RUNTIME_VERSION to capability.runtimeVersion.toString(),
                        SUPPORTED_VERSIONS to JSONArray(capability.supportedVersions.map(Version::toString)).toString(),
                        SUPPORTED_SERIES to JSONArray(capability.supportedSeries.map(VersionSeries::toString)).toString(),
                        SUPPORTED_RANGES to JSONArray().apply {
                            capability.supportedRanges.forEach { range ->
                                put(JSONObject().put("min", range.min.toString()).put("max", range.max.toString()))
                            }
                        }.toString(),
                        RUNTIME_REQUIREMENTS to JSONObject().apply {
                            capability.runtimeComponents.forEach { (name, version) -> put(name, version.toString()) }
                        }.toString(),
                        ORIGIN to plugin.origin,
                    )
                    addRow(requested.map(values::get).toTypedArray())
                }
            }
        }
    }

    /**
     * `state`: whether Enginehost has anything open, for droidtop's running-game session. Answered to droidtop alone
     * (Android's record of the caller, [TrustedCallers]); anyone else gets null. `tasks` is the number of Enginehost's
     * own tasks across all its processes (a launch's warning, a running game, an error screen), so it is 0 once a launch
     * was cancelled, a game ended or its error screen was left, and `runtime` is whether the game's process is alive.
     * Read-only: nothing is touched. Without this droidtop could not tell a cancelled launch from a running game when no
     * privileged helper is installed (Droidtop/tracker#289).
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_STATE) return null
        val context = context ?: return null
        val caller = callingPackage ?: return null
        if (!TrustedCallers.isDroidtop(context.packageManager, caller)) return null
        val tasks = context.getSystemService(android.app.ActivityManager::class.java)?.appTasks?.size ?: return null
        return Bundle().apply {
            putInt(STATE_TASKS, tasks)
            putBoolean(STATE_RUNTIME, RuntimeProcess.alive(context))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.dev.enginehost.capability"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = readOnly()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()

    private fun <T> readOnly(): T = throw UnsupportedOperationException("Enginehost capabilities are read-only")

    companion object {
        const val AUTHORITY = "dev.enginehost.capabilities"
        const val PATH = "installed"
        const val METHOD_STATE = "state"
        const val STATE_TASKS = "tasks"
        const val STATE_RUNTIME = "runtime"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH")
        const val BUNDLE_ID = "bundleId"
        const val ENGINE = "engine"
        const val ENGINE_CONTEXT = "engineContext"
        const val PLUGIN_VERSION = "pluginVersion"
        const val RUNTIME_VERSION = "runtimeVersion"
        const val SUPPORTED_VERSIONS = "supportedVersions"
        const val SUPPORTED_SERIES = "supportedSeries"
        const val SUPPORTED_RANGES = "supportedRanges"
        const val RUNTIME_REQUIREMENTS = "runtimeRequirements"
        const val ORIGIN = "origin"
        val COLUMNS = listOf(
            BUNDLE_ID, ENGINE, ENGINE_CONTEXT, PLUGIN_VERSION, RUNTIME_VERSION,
            SUPPORTED_VERSIONS, SUPPORTED_SERIES, SUPPORTED_RANGES, RUNTIME_REQUIREMENTS, ORIGIN,
        )
    }
}
