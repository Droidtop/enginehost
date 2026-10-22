package dev.enginehost

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Notices a game whose main thread has stopped, in the `:runtime` process.
 *
 * Android calls a stopped main thread an ANR only when input is waiting for
 * it, so a game that froze while nobody touched it went unnoticed, and the
 * in-game menu, drawn on that same thread, could not open either. The main
 * thread beats every [BEAT_MS]; a watcher thread that sees no beat for
 * [HANG_MS] asks the person what to do ([GameTroubleActivity]), from the main
 * process, since this one cannot draw. The game keeps running; a stall that
 * goes on is asked about again every [ASK_AGAIN_MS] (owner, 2026-10-22).
 */
internal object RuntimeWatchdog {
    // Lazy: the object is also loaded by unit tests of [stalled], where Android's Looper does not exist.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var lastBeat = 0L

    /**
     * The game's Activity is resumed: in front of the person. Only then is
     * anything asked, so a game left in the background is never pulled
     * forward, and one with the question already over it is not asked twice.
     */
    @Volatile var inFront = false
    private var started = false

    /** Starts watching once the game's Activity is first resumed, past its engine's start-up on the main thread. */
    fun start(context: Context, gamePath: String?) {
        if (started) return
        started = true
        val app = context.applicationContext
        beat()
        Thread({ watch(app, gamePath) }, "enginehost-watchdog").apply { isDaemon = true }.start()
    }

    private fun beat() {
        lastBeat = SystemClock.uptimeMillis()
        main.postDelayed(::beat, BEAT_MS)
    }

    private fun watch(context: Context, gamePath: String?) {
        var askedAbout = -1L
        var askedAt = 0L
        while (true) {
            SystemClock.sleep(BEAT_MS)
            val now = SystemClock.uptimeMillis()
            val beat = lastBeat
            // A stopped main thread cannot report a pause, so inFront still
            // says where the game was when it stopped.
            if (!inFront || !stalled(beat, now, askedAbout, askedAt)) continue
            askedAbout = beat
            askedAt = now
            GameTroubleActivity.ask(context, GameTroubleActivity.HANG, gamePath)
        }
    }

    /**
     * Whether the stall since [beat] is one to ask about at [now]: past
     * [HANG_MS], and either a new stall or one last asked about
     * [ASK_AGAIN_MS] ago. Pure, for tests.
     */
    internal fun stalled(beat: Long, now: Long, askedAbout: Long, askedAt: Long): Boolean =
        now - beat >= HANG_MS && (beat != askedAbout || now - askedAt >= ASK_AGAIN_MS)

    private const val BEAT_MS = 1_000L

    /** No beat this long is a hang (owner, 2026-10-22). */
    const val HANG_MS = 15_000L
    const val ASK_AGAIN_MS = 30_000L
}
