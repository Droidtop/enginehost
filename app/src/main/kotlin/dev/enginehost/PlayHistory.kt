package dev.enginehost

/** One game's play history (Droidtop/tracker#237), from the sessions the launch screen saw start and end. */
data class PlayHistory(
    val launches: Int = 0,
    val playedMs: Long = 0,
    /** When the game was last started, or 0 when it never was. */
    val lastPlayedAt: Long = 0,
    /** How the last session ended; one of [EXIT_UNKNOWN], [EXIT_CLEAN], [EXIT_CRASHED], [EXIT_FAILED]. */
    val lastExit: Int = EXIT_UNKNOWN,
) {
    companion object {
        const val EXIT_UNKNOWN = 0

        /** The game closed the way it meant to. */
        const val EXIT_CLEAN = 1

        /** The runtime crashed. */
        const val EXIT_CRASHED = 2

        /** The runtime never got going, or reported an error. */
        const val EXIT_FAILED = 3

        /** A session shorter than this is a start that failed, not time played: it adds none. */
        const val MIN_SESSION_MS = 5_000L

        /** The time a session adds to the total. */
        fun countedMs(startedAt: Long, endedAt: Long): Long =
            (endedAt - startedAt).takeIf { startedAt > 0 && it >= MIN_SESSION_MS } ?: 0L
    }
}
