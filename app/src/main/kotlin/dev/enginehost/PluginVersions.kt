package dev.enginehost

/**
 * How a plugin build is named to a person.
 *
 * A bundle's pluginVersion is "major.minor.build": the line's own version
 * from its metadata (1.0) with the CI run number appended by the release
 * script, so that within a line every build orders strictly after the one
 * before it and an update is recognisable. The run number is bookkeeping,
 * not a version anyone chose; it is shown as a build number, never as the
 * third digit of a version.
 */
object PluginVersions {
    /** "1.0 · build 21" for 1.0.21; a two-part version is shown as it is. */
    fun display(version: Version): String {
        val parts = version.parts
        return if (parts.size >= 3) "${parts[0]}.${parts[1]} · build ${parts[2]}" else version.toString()
    }

    /** The build number alone: "21" for 1.0.21, else the whole version. */
    fun build(version: Version): String {
        val parts = version.parts
        return if (parts.size >= 3) parts[2].toString() else version.toString()
    }
}
