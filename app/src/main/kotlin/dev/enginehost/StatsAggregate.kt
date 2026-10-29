package dev.enginehost

import org.json.JSONObject

/**
 * The published summary of everyone's ratings (docs/stats-schema.md, "The aggregate";
 * Droidtop/tracker#491): per model, how many rated it, the average, and the purpose it is rated for
 * most. Pure, so it is unit-tested. A model with fewer than [MIN_RATINGS] ratings is left out, so a
 * handful of reports never stands for a verdict.
 */
class StatsAggregate private constructor(private val entries: Map<Key, Entry>) {
    data class Entry(val count: Int, val average: Double, val popularFor: StatsReport.Purpose?)

    private data class Key(val kind: String, val model: String, val backend: String)

    /** What is known of [model] on [backend], or null when too few rated it or it is not listed. */
    fun lookup(kind: StatsReport.Kind, model: String, backend: String): Entry? =
        entries[Key(kind.wire, model.trim().lowercase(), backend)]

    companion object {
        const val MIN_RATINGS = 10

        val EMPTY = StatsAggregate(emptyMap())

        /** The aggregate document, or [EMPTY] when it is not one this build reads. */
        fun parse(json: String): StatsAggregate = runCatching {
            val root = JSONObject(json)
            if (root.optInt("schema", -1) != StatsReport.SCHEMA) return EMPTY
            val models = root.getJSONArray("models")
            val entries = HashMap<Key, Entry>()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val count = m.optInt("count", 0)
                val average = m.optDouble("average", Double.NaN)
                if (count < MIN_RATINGS || average.isNaN() || average < 1.0 || average > 5.0) continue
                val kind = m.optString("kind").takeIf { k -> StatsReport.Kind.entries.any { it.wire == k } } ?: continue
                val model = m.optString("model").trim().lowercase().takeIf { it.isNotEmpty() } ?: continue
                val backend = m.optString("backend").takeIf { it.isNotEmpty() } ?: continue
                val purpose = m.optString("popularFor").let { p -> StatsReport.Purpose.entries.firstOrNull { it.wire == p } }
                entries[Key(kind, model, backend)] = Entry(count, average, purpose)
            }
            StatsAggregate(entries)
        }.getOrDefault(EMPTY)
    }
}
