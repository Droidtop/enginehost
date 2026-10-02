package dev.enginehost

/**
 * How a plugin build is named to a person: the whole version string.
 *
 * A bundle's pluginVersion is "X.Y.Z-N": the version its repository declares
 * (X.Y.Z, bumped with every revision of the plugin's code) and N, the build
 * counter within that version (first build -1, the next -2, restarting at -1
 * for each new declared version). Both halves are shown, exactly as the
 * bundle carries them ("1.0.1-3"), so a person reporting a crash and a
 * maintainer reading the release list name the same build. Versions published
 * before that change are "X.Y.<run>"; Version.parsePlugin reads them as
 * X.Y.0-<run>, so they show in the same form.
 */
object PluginVersions {
    /** "1.2.1-5": the full version, build counter included. */
    fun display(version: Version): String = version.toString()
}
