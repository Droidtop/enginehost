package dev.enginehost

/**
 * What the already-fetched catalog says about streams, with no network of
 * its own. A refresh caches every stream a repository publishes (see
 * [CatalogRefresh]), so a fresh install on Stable -- which nothing is
 * published to before 1.0 -- can still be told that Testing or Unstable
 * already has something, instead of reading as an empty, unexplained store.
 */
object StreamAvailability {
    /** How many distinct plugins [stream] would show, counted the way the catalog counts cards: one per bundle. */
    fun count(all: List<AvailablePlugin>, stream: PluginStream): Int =
        all.filter { it.stream.offeredTo(stream) }.distinctBy { it.bundleId }.size

    /**
     * Streams less steady than [chosen] that would show something if picked,
     * in stream order, paired with how many plugins each would show. Empty
     * when [chosen] itself already shows something (there is nothing to
     * suggest) or when no stream has anything at all.
     */
    fun moreAdventurousWithPlugins(all: List<AvailablePlugin>, chosen: PluginStream): List<Pair<PluginStream, Int>> {
        if (count(all, chosen) > 0) return emptyList()
        return PluginStream.entries
            .filter { it.ordinal > chosen.ordinal }
            .map { it to count(all, it) }
            .filter { (_, n) -> n > 0 }
    }
}
