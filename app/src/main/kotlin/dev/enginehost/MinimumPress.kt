package dev.enginehost

/**
 * Holds a button's release back until the press has lasted [minimumMs], so an
 * engine that reads its input once per frame still sees a press that went
 * down and up between two of its frames.
 *
 * Droidtop/tracker#618 C10: RPG Maker MV polls its key state once per frame,
 * and a press shorter than a frame was lost. An adb tap moved the cursor once
 * in three tries. Holding a release to at least [minimumMs] (three frames at
 * 60 fps, two at 30) makes every press visible to such an engine and changes
 * nothing for a press that was already that long, which is every press a person
 * makes. A new press of the same key first delivers the held release, so press
 * and release never arrive out of order.
 *
 * [now] is the clock the events' times are on; [later] runs a delivery after
 * a delay (a Handler on the input thread in the app, direct calls in tests).
 */
internal class MinimumPress<E>(
    private val minimumMs: Long = MINIMUM_MS,
    private val now: () -> Long,
    private val later: (delayMs: Long, run: () -> Unit) -> Unit,
) {
    private val downAt = HashMap<Int, Long>()
    private val held = HashMap<Int, () -> Unit>()

    /** A press of [key]: delivers a release still held for it first, then lets this press through. */
    fun down(key: Int) {
        held.remove(key)?.invoke()
        downAt[key] = now()
    }

    /**
     * A release of [key], to be handed on with [deliver]. True when it was
     * delivered now; false when it is held and [deliver] runs later.
     */
    fun up(key: Int, event: E, deliver: (E) -> Unit): Boolean {
        val pressedAt = downAt.remove(key) ?: return deliverNow(event, deliver)
        val wait = minimumMs - (now() - pressedAt)
        if (wait <= 0) return deliverNow(event, deliver)
        val run = { deliver(event) }
        held[key] = run
        later(wait) { if (held[key] === run) held.remove(key)?.invoke() }
        return false
    }

    private fun deliverNow(event: E, deliver: (E) -> Unit): Boolean {
        deliver(event)
        return true
    }

    companion object {
        const val MINIMUM_MS = 60L
    }
}
