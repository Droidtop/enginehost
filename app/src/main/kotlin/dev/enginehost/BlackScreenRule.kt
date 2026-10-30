package dev.enginehost

/**
 * When a game that has drawn nothing is asked about ("Showed only a black screen",
 * [GameTroubleActivity]) and when [RuntimeLoadingNotice] stops watching. Pure, so the rule
 * is tested on the JVM.
 *
 * The question is about what the person sees, not about the engine's surface, which stays
 * black under the host's loading screen (Droidtop/tracker#821: Ren'Py asked at 10:07:30 with
 * the game's presplash on screen and ran 55 s later). So:
 * - while the game's own loading picture is up ([LoadingCover]) nothing is asked: the screen
 *   is not black;
 * - every progress report the engine sends ([LoadingProgress]) starts the window again: a
 *   game that is loading is never asked about;
 * - the window is [QUIET_MS], or [FIRST_LAUNCH_QUIET_MS] on a core build's first launch,
 *   which unpacks its runtime and compiles the game's scripts before anything is drawn.
 */
internal object BlackScreenRule {
    /** Nothing drawn and nothing reported this long, and the person is asked (owner, 2026-10-22). */
    const val QUIET_MS = 45_000L

    /** The same on a core build's first launch. */
    const val FIRST_LAUNCH_QUIET_MS = 150_000L

    /** Nothing drawn and nothing reported this long after asking: the notice stops watching. */
    const val GIVE_UP_MS = 180_000L

    /** How long the game has shown no sign of life: since it started, or since its last progress report. */
    fun quietFor(now: Long, startedAt: Long, reportedAt: Long): Long = now - maxOf(startedAt, reportedAt)

    /** Whether to ask about a black screen now. */
    fun shouldAsk(now: Long, startedAt: Long, reportedAt: Long, ownPictureShown: Boolean, firstLaunch: Boolean): Boolean {
        if (ownPictureShown) return false
        return quietFor(now, startedAt, reportedAt) >= if (firstLaunch) FIRST_LAUNCH_QUIET_MS else QUIET_MS
    }

    /** Whether to stop watching: no sign of life for [GIVE_UP_MS], on top of a first launch's longer start. */
    fun givesUp(now: Long, startedAt: Long, reportedAt: Long, firstLaunch: Boolean): Boolean =
        quietFor(now, startedAt, reportedAt) > GIVE_UP_MS + if (firstLaunch) FIRST_LAUNCH_QUIET_MS - QUIET_MS else 0L
}
