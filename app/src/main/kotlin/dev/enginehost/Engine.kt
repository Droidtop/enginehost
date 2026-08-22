package dev.enginehost

import android.app.Activity
import java.io.File

/** One engine backend -- given a game folder and its own config, actually runs the game. */
interface Engine {
    fun run(activity: Activity, gameFolder: File, config: EngineConfig)
}

/**
 * Maps an [EngineConfig.engine] id to the real [Engine] that handles it.
 * Real engines get added here as they're wired up (see each vendor/
 * submodule's own README for status) -- an id with no registered [Engine]
 * is a real, expected case (config referencing an engine this build
 * doesn't include), not a bug, so [LaunchActivity] treats it as a normal
 * failure to report, not a crash.
 */
object EngineRegistry {
    private val engines = mutableMapOf<String, Engine>()

    fun register(id: String, engine: Engine) {
        engines[id] = engine
    }

    fun get(id: String): Engine? = engines[id]
}
