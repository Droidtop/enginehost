package dev.enginehost

/**
 * How a plugin build is named to a person.
 *
 * A bundle's pluginVersion is "X.Y.Z-N": the version its repository declares
 * (X.Y.Z) and N, the build counter within that version (first build -1, the
 * next -2, restarting at -1 for each new declared version). The counter is
 * bookkeeping, not a version anyone chose, so it is shown as a build number.
 * Versions published before that change are "X.Y.<run>", the CI run number in
 * the third place; they are still shown as "X.Y · build <run>".
 */
object PluginVersions {
    /** "1.2.1 · build 5" for 1.2.1-5, "1.0 · build 21" for legacy 1.0.21, else the version as it is. */
    fun display(version: Version): String {
        version.build?.let { return "${declared(version)} · build $it" }
        val parts = version.parts
        return if (parts.size >= 3) "${parts[0]}.${parts[1]} · build ${parts[2]}" else version.toString()
    }

    /** The build number alone: "5" for 1.2.0-5, "21" for legacy 1.0.21, else the whole version. */
    fun build(version: Version): String {
        version.build?.let { return it.toString() }
        val parts = version.parts
        return if (parts.size >= 3) parts[2].toString() else version.toString()
    }

    /** The declared version without a zero patch: "1.0" for 1.0.0-3, "1.2.1" for 1.2.1-3. */
    private fun declared(version: Version): String =
        version.parts.let { if (it.size >= 3 && it.drop(2).all { p -> p == 0 }) it.take(2) else it }.joinToString(".")
}
