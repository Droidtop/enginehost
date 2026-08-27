package dev.enginehost

/**
 * Plain dotted-integer version ("2.32", "1.4.0") -- missing trailing
 * components compare as 0, same convention as semver's own padding rule.
 */
data class Version(val parts: List<Int>) : Comparable<Version> {
    private fun canonicalParts(): List<Int> = parts.dropLastWhile { it == 0 }.ifEmpty { listOf(0) }

    override fun equals(other: Any?): Boolean =
        other is Version && canonicalParts() == other.canonicalParts()

    override fun hashCode(): Int = canonicalParts().hashCode()

    override fun compareTo(other: Version): Int {
        val len = maxOf(parts.size, other.parts.size)
        for (i in 0 until len) {
            val cmp = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    /**
     * How far apart two versions are, most-significant component weighted
     * heaviest -- used to pick the "nearest" installed engineVersion when
     * no exact match exists. Not a real distance metric, just enough to
     * rank candidates sensibly (a major-version gap should always outrank
     * a patch-version gap, which plain numeric subtraction alone wouldn't
     * guarantee once component values vary widely).
     */
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
            require(normalized.matches(Regex("[0-9]+(?:\\.[0-9]+)*"))) {
                "Version must contain only dot-separated non-negative integers: $raw"
            }
            return Version(normalized.split(".").map(String::toInt))
        }
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
                .map { token ->
                    val dashIndex = token.indexOf('-', startIndex = 1) // skip a leading '-' if any, not expected but harmless
                    if (dashIndex > 0) {
                        val low = Version.parse(token.substring(0, dashIndex))
                        val high = Version.parse(token.substring(dashIndex + 1))
                        Entry.Range(low, high)
                    } else {
                        Entry.Exact(Version.parse(token))
                    }
                }
            return VersionConstraint(entries)
        }
    }
}
