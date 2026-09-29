package dev.enginehost

/**
 * How a plugin build is named to a person.
 *
 * A bundle's pluginVersion is "X.Y.Z-N": the version its repository declares
 * (X.Y.Z) and N, the build counter within that version (first build -1, the
 * next -2, restarting at -1 for each new declared version). The counter is
 * bookkeeping, not a version anyone chose, so it is shown as a build number.
 * Versions published before that change are "X.Y.<run>"; Version.parsePlugin
 * reads them as X.Y.0-<run>, so they are shown the same way.
 */
object PluginVersions {
    /** "1.2.1 · build 5" for 1.2.1-5, else the version as it is. */
    fun display(version: Version): String =
        version.build?.let { "${declared(version)} · build $it" } ?: version.toString()

    /** The build number alone: "5" for 1.2.0-5, else the whole version. */
    fun build(version: Version): String = version.build?.toString() ?: version.toString()

    /** The declared version without a zero patch: "1.0" for 1.0.0-3, "1.2.1" for 1.2.1-3. */
    private fun declared(version: Version): String =
        version.parts.let { if (it.size >= 3 && it.drop(2).all { p -> p == 0 }) it.take(2) else it }.joinToString(".")
}
