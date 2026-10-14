package dev.enginehost

import android.app.Activity

/**
 * The one warning for engine plugins that cannot run sandboxed. Owner,
 * 2026-10-10 (tracker#482): "Running plugins unsandboxed should be
 * possible, but big red warnings and a recommendation that users not
 * install unsandboxed plugins". Such a plugin's code runs with Enginehost's
 * own all-files and network access while it plays (docs/engine-sandbox.md,
 * "What Android allows inside one app"), so installing or updating one asks
 * first, on a red [Sheet.warning] sheet whose first choice, and so the pad's
 * first focus, is the safe one. A sheet that cannot be shown counts as no.
 * The launch screen's per-run question (LaunchActivity.offerSandboxConsent)
 * wears the same sheet.
 */
object UnsandboxedWarning {
    /**
     * Before installing or updating [plugins] from a catalog: runs [proceed]
     * at once when every one of them runs sandboxed, else only once the person
     * chooses to install anyway; [cancel] otherwise.
     */
    fun beforeInstall(activity: Activity, plugins: List<AvailablePlugin>, proceed: () -> Unit, cancel: () -> Unit) {
        // A runtime component runs inside whichever engine loads it, so the
        // engine's own install is where this question belongs, not the component's.
        val unsandboxed = plugins.filterNot { it.manifest.isolatable || it.manifest.component != null }
            .map { lineName(it.info) }.distinct()
        if (unsandboxed.isEmpty()) return proceed()
        val shown = runCatching {
            Sheet(activity)
                .warning()
                .title(R.string.unsandboxed_install_title)
                .message(activity.getString(R.string.unsandboxed_install_message, unsandboxed.joinToString(", ")))
                .choice(R.string.cancel) { cancel() }
                .choice(R.string.unsandboxed_install_anyway, Sheet.Tone.DANGER) { proceed() }
                .onCancel { cancel() }
                .show()
        }.isSuccess
        if (!shown) cancel()
    }

    /**
     * After installing a bundle picked from storage, which is only readable
     * once it is installed: the same warning, offering to remove it again
     * first. Keeping it, or closing the sheet, runs [keep].
     */
    fun afterFileInstall(activity: Activity, installed: InstalledPlugin, keep: () -> Unit) {
        if (installed.isolatable || installed.component != null) return keep()
        val shown = runCatching {
            Sheet(activity)
                .warning()
                .title(R.string.unsandboxed_install_title)
                .message(activity.getString(R.string.unsandboxed_install_message, lineName(installed.info)))
                .choice(R.string.unsandboxed_remove) { Thread { PluginRegistry.uninstall(activity, installed.bundleId) }.start() }
                .choice(R.string.unsandboxed_keep, Sheet.Tone.DANGER) { keep() }
                .onCancel { keep() }
                .show()
        }.isSuccess
        if (!shown) keep()
    }

    /** The name Plugins gives a line: the versions it runs ("Ren'Py 7.5.x"), else the engine family. */
    private fun lineName(info: PluginInfo): String =
        EngineNames.compatibility(info.engine, info.capabilities)
            .ifEmpty { listOf(EngineNames.family(info.engine)) }
            .joinToString(" · ")
}
