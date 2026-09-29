package dev.enginehost

/**
 * Dotted-integer version ("2.32", "1.4.0") with an optional build counter
 * ("1.0.0-3") -- missing trailing components compare as 0, same convention
 * as semver's own padding rule.
 *
 * Order is by the dotted [parts] first, then by [build]. The build is the
 * per-version counter a plugin release carries after a dash: the first
 * published build of a declared version is `-1`, the next `-2`, and a new
 * declared version starts again at `-1` (Droidtop/tracker#126). A version
 * without a build compares as build 0, so 1.0.0-N is the 1.0.0 release, not
 * a pre-release below it, and it sorts above a bare 1.0.0.
 *
 * Legacy plugin versions are `X.Y.<run>`, where the CI run number sat in the
 * third place. They stay comparable by the same rule: X.Y.57 orders by its
 * dotted parts (so it is above 1.0.0-99) and below 1.0.57-1. The consequence
 * is that the first declared version of the new form must exceed the last
 * legacy run number in its third place (or bump the minor) to be offered as
 * an update over a legacy install.
 */
data class Version(val parts: List<Int>, val build: Int? = null) : Comparable<Version> {
    private fun canonicalParts(): List<Int> = parts.dropLastWhile { it == 0 }.ifEmpty { listOf(0) }

    override fun equals(other: Any?): Boolean =
        other is Version && canonicalParts() == other.canonicalParts() && (build ?: 0) == (other.build ?: 0)

    override fun hashCode(): Int = 31 * canonicalParts().hashCode() + (build ?: 0)

    override fun compareTo(other: Version): Int {
        val len = maxOf(parts.size, other.parts.size)
        for (i in 0 until len) {
            val cmp = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return (build ?: 0).compareTo(other.build ?: 0)
    }

    override fun toString(): String = parts.joinToString(".") + (build?.let { "-$it" } ?: "")

    /** True when this version begins with every component in [series]. */
    fun belongsTo(series: VersionSeries): Boolean =
        parts.size >= series.parts.size && parts.take(series.parts.size) == series.parts

    /** A stable weighted span used only to rank explicitly declared ranges. */
    fun distanceTo(other: Version): Long {
        val len = maxOf(parts.size, other.parts.size)
        var distance = 0L
        for (i in 0 until len) {
            val diff = kotlin.math.abs(parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 })
            val weight = 1_000_000L / Math.pow(1000.0, i.toDouble()).toLong().coerceAtLeast(1)
            distance += diff * weight
        }
        return distance
    }

    companion object {
        fun parse(raw: String): Version {
            val normalized = raw.trim()
            require(normalized.matches(Regex("[0-9]+(?:\\.[0-9]+)*(?:-[0-9]+)?"))) {
                "Version must be dot-separated non-negative integers with an optional -build: $raw"
            }
            val dash = normalized.indexOf('-')
            val dotted = if (dash < 0) normalized else normalized.substring(0, dash)
            return Version(dotted.split(".").map(String::toInt), if (dash < 0) null else normalized.substring(dash + 1).toInt())
        }
    }
}

/** A dotted version-line prefix such as Ren'Py `8.2`, not an exact version. */
data class VersionSeries(val parts: List<Int>) {
    init {
        require(parts.isNotEmpty()) { "Version series must contain at least one component" }
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        fun parse(raw: String): VersionSeries = Version.parse(raw).let { VersionSeries(it.parts) }
    }
}

/**
 * A game's own real requirement on which plugin *builds* it trusts,
 * independent of engine version -- comma-separated exact versions and/or
 * `lo-hi` ranges, e.g. "1.0.0,1.2.0-1.4.0". Exists specifically to guard
 * against known-bad plugin revisions (a newer plugin build can regress a
 * game even though it implements the exact same engine version).
 */
class VersionConstraint private constructor(private val entries: List<Entry>) {
    private sealed class Entry {
        data class Exact(val version: Version) : Entry()
        data class Range(val low: Version, val high: Version) : Entry()
    }

    fun matches(version: Version): Boolean = entries.any { entry ->
        when (entry) {
            is Entry.Exact -> entry.version == version
            is Entry.Range -> version >= entry.low && version <= entry.high
        }
    }

    companion object {
        fun parse(raw: String): VersionConstraint {
            val entries = raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { token -> parseEntry(token) }
            require(entries.isNotEmpty()) { "Version allowlist must not be empty" }
            return VersionConstraint(entries)
        }

        /**
         * A whole token that is one version ("1.0.0", "1.0.0-3") is exact. Otherwise
         * a dash separates the two ends of a range, and the split is the first one
         * where both sides are versions ("1.2.0-1.4.0", "1.0.0-3-1.0.0-9").
         */
        private fun parseEntry(token: String): Entry {
            runCatching { Version.parse(token) }.getOrNull()?.let { return Entry.Exact(it) }
            var dash = token.indexOf('-', startIndex = 1)
            while (dash > 0) {
                val low = runCatching { Version.parse(token.substring(0, dash)) }.getOrNull()
                val high = runCatching { Version.parse(token.substring(dash + 1)) }.getOrNull()
                if (low != null && high != null) {
                    require(low <= high) { "Version range must be ordered: $token" }
                    return Entry.Range(low, high)
                }
                dash = token.indexOf('-', startIndex = dash + 1)
            }
            throw IllegalArgumentException("Not a version or a version range: $token")
        }
    }
}
