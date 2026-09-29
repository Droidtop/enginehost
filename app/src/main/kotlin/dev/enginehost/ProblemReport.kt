package dev.enginehost

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.view.WindowManager
import java.io.File

/**
 * What Enginehost knows about a game that will not behave, gathered so
 * someone else can act on it.
 *
 * The game's name leads the report and is not optional: without it nobody can
 * find the game, reproduce the failure, or say whether a later build fixed
 * it. Everything else is what decides whether a game runs, which is the
 * engine and its version, the plugin build that claimed it, and the host and
 * device underneath: the graphics stack, memory and display, the last things
 * Enginehost itself did ([HostEvents]) and a trimmed system log. All of it is
 * a starting point the person can read and correct in [ProblemReportActivity]
 * before any of it moves; paths are reduced to the names a reader needs, so a
 * report does not carry someone's storage layout.
 *
 * One mechanism builds what leaves the device: [compose], run over the fields
 * as they stand when the person presses Share, Copy or the GitHub button, and
 * redacting again there. The GitHub address carries no report content at all
 * ([formUrl]); the report travels by the clipboard or the share sheet.
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
    /** The last things Enginehost did, oldest first; see [HostEvents]. */
    val events: String = "",
    /** Enginehost's own recent system log lines, trimmed to what says something. */
    val systemLog: String = "",
    /** Warnings and errors the engine itself logged this session, oldest first. */
    val engineEvents: String = "",
    /** Settings that differ from their defaults; what a person changed can explain a game that only fails for them. */
    val settings: String = "",
) {
    /** The part of the report Enginehost fills in about the machine and the plugin. */
    fun environment(): String = buildString {
        appendLine("Plugin: $plugin")
        appendLine("Enginehost: $host")
        append(device)
        if (settings.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("Settings changed from default:")
            append(settings)
        }
        if (config.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("enginehost.json:")
            append(config)
        }
    }

    /** The log field: [crash] first, then what happened just before it, the system log and the engine's own log. */
    fun logSections(crash: String?, heading: (Section) -> String): String =
        listOfNotNull(
            crash,
            engineEvents.takeIf { it.isNotBlank() }?.let { heading(Section.ENGINE) + "\n" + it },
            events.takeIf { it.isNotBlank() }?.let { heading(Section.EVENTS) + "\n" + it },
            systemLog.takeIf { it.isNotBlank() }?.let { heading(Section.SYSTEM_LOG) + "\n" + it },
            log.takeIf { it.isNotBlank() },
        ).joinToString(BLANK_LINE)

    enum class Section { ENGINE, EVENTS, SYSTEM_LOG }

    companion object {
        private const val LOG_CHARACTERS = 3000
        private const val SYSTEM_LOG_LINES = 120
        private const val SYSTEM_LOG_CHARACTERS = 6000
        private val BLANK_LINE = "\n\n"

        /**
         * A report for [gameFolder], or for the app itself when there is no
         * game in question. Reading the config and the registry touches disk
         * and the graphics probe and system log start work of their own; call
         * this off the main thread.
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
                    val p = it.plugin
                    val sandbox = if (p.isolatable) "sandboxed" else "not sandboxed"
                    val origin = p.origin.ifBlank { "unknown origin" }
                    "${p.bundleId} ${PluginVersions.display(p.info.pluginVersion)} " +
                        "($origin, signer ${p.signerIdentity.ifBlank { "unsigned" }}, $sandbox)"
                } ?: context.getString(R.string.report_no_plugin),
                host = "${context.packageManager.getPackageInfo(context.packageName, 0).versionName} " +
                    "(build ${AppUpdate.installedVersionCode(context)}, engine registry ${PlatformSnapshot.shortCommit(context) ?: "unrecorded"})",
                device = deviceFacts(context),
                log = tail(context, gameFolder),
                events = scrub(HostEvents.recent(context).joinToString("\n"), gameFolder),
                systemLog = scrub(trimSystemLog(readSystemLog()), gameFolder),
                engineEvents = scrub(HostEvents.recent(context, HostEvents.ENGINE_FILE).joinToString("\n"), gameFolder),
                settings = nonDefaultSettings(context),
            )
        }

        /**
         * The settings a person changed, one line each; empty when none are.
         * Only whether and how a setting differs, never a folder or an app's name.
         */
        private fun nonDefaultSettings(context: Context): String = runCatching {
            val updates = PluginUpdateCheck(context)
            val saves = SaveLocationStore(context)
            val callers = CallerAccessStore(context).all().values
            listOfNotNull(
                updates.frequency.takeIf { it != PluginUpdateCheck.Frequency.DAILY }?.let { "Plugin update checks: ${it.name.lowercase()}" },
                "Plugin updates install automatically".takeIf { updates.installAutomatically },
                updates.stream.takeIf { it != PluginStream.STABLE }?.let { "Plugin releases: ${it.name.lowercase()}" },
                "Plugin checks skip metered connections".takeIf { updates.unmeteredOnly },
                "Saves go to a folder the person chose".takeIf { saves.root() != saves.defaultRoot() },
                saves.overrides().size.takeIf { it > 0 }?.let { "$it engines keep saves in their own folder" },
                callers.count { it == CallerDecision.ALLOW }.takeIf { it > 0 }?.let { "$it apps always allowed to launch games" },
                callers.count { it == CallerDecision.BLOCK }.takeIf { it > 0 }?.let { "$it apps blocked from launching games" },
            ).joinToString("\n")
        }.getOrDefault("")

        /**
         * The device the game runs on, in the lines a compatibility reader
         * asks for first: hardware, memory, screen and graphics.
         */
        private fun deviceFacts(context: Context): String = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} ")
            append("(API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.joinToString("/")})")
            val chip = buildString {
                append(Build.HARDWARE)
                if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank() && Build.SOC_MODEL != Build.UNKNOWN) {
                    append(", ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL)
                }
            }
            appendLine()
            append("Chipset: $chip")
            runCatching {
                val info = ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
                appendLine()
                append("Memory: ${info.totalMem / (1024 * 1024)} MB")
            }
            runCatching {
                val metrics = context.resources.displayMetrics
                @Suppress("DEPRECATION")
                val refresh = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
                appendLine()
                append("Screen: ${metrics.widthPixels}x${metrics.heightPixels} at ${metrics.densityDpi} dpi, ${"%.0f".format(refresh)} Hz")
            }
            appendLine()
            append("Graphics: ${graphicsSummary(context)}")
        }

        /** GPU name and driver as a context reports them, plus the Vulkan level the device declares. */
        private fun graphicsSummary(context: Context): String {
            val gl = runCatching { readGlStrings() }.getOrNull()
            val vulkan = runCatching {
                context.packageManager.systemAvailableFeatures
                    .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                    ?.let { "Vulkan ${it.version ushr 22}.${(it.version ushr 12) and 0x3ff}" }
            }.getOrNull()
            return listOfNotNull(gl, vulkan ?: "no Vulkan declared").joinToString("; ")
        }

        /**
         * The renderer, vendor and version strings of a throwaway GLES 2
         * context. Never terminates the shared display, which other users in
         * this process may hold.
         */
        private fun readGlStrings(): String? {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val wanted = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
                val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
                val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
                val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
                return listOfNotNull(renderer, vendor?.let { "($it)" }, glVersion).joinToString(" ").ifBlank { null }
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        }

        /** This app's own recent system log (an app sees its own lines without any permission); empty when the device will not say. */
        private fun readSystemLog(): String = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", "600", "-v", "threadtime").redirectErrorStream(true).start()
            try {
                process.inputStream.bufferedReader().use { it.readText() }
            } finally {
                process.destroy()
            }
        }.getOrDefault("")

        private val LOG_LINE = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:""")
        private val REPORT_TAGS = listOf("enginehost", "androidruntime", "vulkan", "egl", "gles", "mali", "adreno")
        private val REPORT_EXACT_TAGS = setOf("debug", "libc", "art")

        /**
         * A threadtime system log cut down to what a reader can use: warnings
         * and worse from anyone, and everything the engine hosting and the
         * graphics stack said, the last [SYSTEM_LOG_LINES] lines of that.
         */
        internal fun trimSystemLog(raw: String): String {
            val kept = raw.lineSequence().filter { line ->
                val match = LOG_LINE.find(line) ?: return@filter false
                val level = match.groupValues[1]
                val tag = match.groupValues[2].lowercase()
                level in "WEF" || tag in REPORT_EXACT_TAGS || REPORT_TAGS.any { it in tag }
            }.toList().takeLast(SYSTEM_LOG_LINES).joinToString("\n")
            return if (kept.length > SYSTEM_LOG_CHARACTERS) kept.takeLast(SYSTEM_LOG_CHARACTERS) else kept
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
         * Absolute paths become the names a reader needs: a storage or app
         * path goes whole, not just its first component. The game's folder,
         * and any name in [hide], become "<game>". A log line can also carry
         * things that were never about the game at all: an email a plugin's
         * own crash handler logged, a LAN IP from a socket error, a home
         * directory, a bearer token, a GitHub token or a `password=` in a
         * request log. Those go too, on the same keep-the-shape-drop-the-value
         * principle. [addresses] is off for text that carries version numbers,
         * where 4.2.1.0 is not an IP.
         */
        fun scrub(text: String, gameFolder: File?, hide: List<String> = emptyList(), addresses: Boolean = true): String {
            var result = text
            gameFolder?.let { result = result.replace(it.absolutePath, "<game>") }
            hide.filter { it.isNotBlank() }.forEach { result = result.replace(it, "<game>") }
            result = result.replace(Regex("""/(?:storage|sdcard|mnt)(?:/\S*)?"""), "<storage>")
            result = result.replace(Regex("""/data/(?:user/\d+|data|media)(?:/\S*)?"""), "<app>")
            result = result.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "<email>")
            if (addresses) result = result.replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "<ip>")
            result = result.replace(Regex("/home/[^/\\s]+"), "<home>")
            result = result.replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
            result = result.replace(Regex("gh[pousr]_[A-Za-z0-9]{20,}"), "<redacted>")
            result = result.replace(
                Regex("""\b(token|password|passwd|secret|api[_-]?key|authorization)(\s*[=:]\s*)\S+""", RegexOption.IGNORE_CASE),
                "$1$2<redacted>",
            )
            return result.trim()
        }

        /**
         * The whole report as it leaves the device, from the fields as they
         * stand: redacted once more here, so what was typed or edited after
         * the fill is covered too. [hide] names what to blank out of the
         * details, environment and log (the game's folder name when the
         * person cleared the game field).
         */
        fun compose(
            game: String,
            engine: String,
            symptomHeading: String,
            symptom: String,
            details: String,
            environment: String,
            logHeading: String,
            log: String,
            hide: List<String> = emptyList(),
        ): String = buildString {
            appendLine("Game: $game")
            appendLine("Engine: $engine")
            appendLine("$symptomHeading: $symptom")
            scrub(details, null, hide).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine()
            appendLine(scrub(environment, null, hide, addresses = false))
            scrub(log, null, hide).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(logHeading)
                append(it)
            }
        }.trimEnd()

        /**
         * GitHub's issue form, opened to the report repository with a title
         * and the two short, non-identifying fields. The game, details,
         * environment and log are never put in an address (it lands in
         * history and server logs); they go by the clipboard.
         */
        fun formUrl(engine: String, symptom: String): String {
            val title = "Enginehost" + if (symptom.isNotBlank()) ": ${symptom.lowercase()}" else ""
            return Uri.parse("$REPORTS_REPOSITORY/issues/new").buildUpon()
                .appendQueryParameter("template", "compatibility.yml")
                .appendQueryParameter("title", title)
                .appendQueryParameter("engine", engine)
                .appendQueryParameter("symptom", symptom)
                .build().toString()
        }

        const val REPORTS_REPOSITORY = "https://github.com/droidtop/enginehost-reports"
    }
}
