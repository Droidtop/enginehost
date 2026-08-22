package dev.enginehost

import android.app.Activity
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * The one real code path both [LaunchActivity] (Intent-driven, the
 * primary/intended way in) and [MainActivity] (the basic pick-and-launch
 * UI, for when there's no caller passing a path) go through -- reads the
 * folder's own enginehost.json and hands off to whichever [Engine] is
 * registered for it.
 */
object GameRunner {
    private const val TAG = "enginehost"

    fun run(activity: Activity, gameFolder: File) {
        val config = try {
            EngineConfigReader.read(gameFolder)
        } catch (e: InvalidEngineConfigException) {
            fail(activity, e.message ?: "Invalid $CONFIG_FILE_NAME")
            return
        }
        val engine = EngineRegistry.get(config.engine)
        if (engine == null) {
            fail(activity, "No engine registered for \"${config.engine}\"")
            return
        }
        engine.run(activity, gameFolder, config)
    }

    private fun fail(activity: Activity, message: String) {
        Log.e(TAG, message)
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
