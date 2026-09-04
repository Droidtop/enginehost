package dev.enginehost

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * What Enginehost knows about a game that will not behave, gathered so
 * someone else can act on it.
 *
 * The game's name leads the report and is not optional: without it nobody can
 * find the game, reproduce the failure, or say whether a later build fixed
 * it. Everything else is what decides whether a game runs, which is the
 * engine and its version, the plugin build that claimed it, and the host and
 * device underneath. All of it is a starting point the person can correct in
 * [ProblemReportActivity] before any of it moves; paths are reduced to the
 * names a reader needs, so a report does not carry someone's storage layout.
 */
data class ProblemReport(
    val gameName: String,
    val engineLine: String,
    val engineVersion: String,
    val config: String,
    val plugin: String,
    val host: String,
    val device: String,
    val log: String,
) {
    /** The part of the report Enginehost fills in about the machine and the plugin. */
    fun environment(): String = buildString {
        appendLine("Plugin: $plugin")
        appendLine("Enginehost: $host")
        append("Device: $device")
        if (config.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("enginehost.json:")
            append(config)
        }
    }

    companion object {
        private const val LOG_CHARACTERS = 3000

        /**
         * A report for [gameFolder], or for the app itself when there is no
         * game in question. Reading the config and the registry touches disk;
         * call this off the main thread.
         */
        fun gather(context: Context, gameFolder: File?): ProblemReport {
            val config = gameFolder?.let { folder ->
                runCatching { EngineConfigReader.resolve(folder, null) }.getOrNull()
            }
            val resolved = config?.let {
                runCatching {
                    PluginRegistry.resolve(
                        context, it.engine, it.engineContext, it.engineVersion,
                        it.runtimeRequirements, it.pluginVersionConstraint,
                    )
                }.getOrNull()
            }
            val configText = gameFolder?.let { folder ->
                File(folder, CONFIG_FILE_NAME).takeIf { it.isFile }?.let { file ->
                    runCatching { file.readText().trim() }.getOrNull()
                }
            }.orEmpty()
            return ProblemReport(
                gameName = gameFolder?.name.orEmpty(),
                engineLine = config?.let { EngineNames.line(it.engine, it.engineContext) } ?: "",
                engineVersion = config?.engineVersion?.toString() ?: "",
                config = configText,
                plugin = resolved?.let {
                    "${it.plugin.bundleId} ${PluginVersions.display(it.plugin.info.pluginVersion)}"
                } ?: context.getString(R.string.report_no_plugin),
                host = "${context.packageManager.getPackageInfo(context.packageName, 0).versionName} " +
                    "(build ${AppUpdate.installedVersionCode(context)})",
                device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()})",
                log = tail(context, gameFolder),
            )
        }

        /**
         * The end of whichever log says most about this launch: the engine's
         * own log beside the game when it wrote one, else Enginehost's.
         */
        private fun tail(context: Context, gameFolder: File?): String {
            val candidates = listOfNotNull(
                gameFolder?.let { File(it, "log.txt") },
                File(context.getExternalFilesDir(null), "log.txt"),
                File(context.filesDir, "log.txt"),
            )
            val log = candidates.firstOrNull { it.isFile } ?: return ""
            val text = runCatching { log.readText() }.getOrDefault("")
            val trimmed = if (text.length > LOG_CHARACTERS) text.takeLast(LOG_CHARACTERS) else text
            return scrub(trimmed, gameFolder)
        }

        /**
         * Absolute paths become the names a reader needs. The game's name
         * stays in the report's own field; the storage layout around it helps
         * nobody and is replaced.
         */
        fun scrub(text: String, gameFolder: File?): String {
            var result = text
            gameFolder?.let { result = result.replace(it.absolutePath, "<game>") }
            result = result.replace(Regex("/storage/[^/\\s]+"), "<storage>")
            result = result.replace(Regex("/data/(user/\\d+|data)/[^/\\s]+"), "<app>")
            return result.trim()
        }

        /**
         * The report form, prefilled from what the person has in front of
         * them. GitHub's issue forms take a value per field id in the query
         * string, so they land on the form with everything already written
         * and only have to press the button.
         */
        fun formUrl(
            game: String,
            engine: String,
            symptom: String,
            details: String,
            environment: String,
            log: String,
        ): String {
            val title = buildString {
                append(game.ifBlank { "Enginehost" })
                if (symptom.isNotBlank()) append(": ").append(symptom.lowercase())
            }
            val builder = Uri.parse("$REPORTS_REPOSITORY/issues/new").buildUpon()
                .appendQueryParameter("template", "compatibility.yml")
                .appendQueryParameter("title", title)
                .appendQueryParameter("game", game)
                .appendQueryParameter("engine", engine)
                .appendQueryParameter("symptom", symptom)
                .appendQueryParameter("details", details)
                .appendQueryParameter("environment", environment)
            if (log.isNotBlank()) builder.appendQueryParameter("log", log)
            return builder.build().toString()
        }

        const val REPORTS_REPOSITORY = "https://github.com/droidtop/enginehost-reports"
    }
}
