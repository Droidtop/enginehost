package dev.enginehost

/**
 * The one place a layout decision reads the window's size (Droidtop/tracker#234).
 * Layouts key off the window's own width and height in dp, which Android
 * reports per window, so a tablet, a foldable, a split-screen half and a
 * freeform window each get the answer for the space they really have, where
 * `layout` and `layout-land` only know which way the device is turned.
 * Width classes are the Material ones: compact under 600dp, medium to 839dp,
 * expanded from 840dp.
 */
object SizeClass {
    const val EXPANDED_MIN_DP = 840

    /** Under this height a window is short: Home shows one shelf instead of two. */
    const val SHORT_HEIGHT_DP = 480

    /**
     * How many columns of at least [minColumnPx] fit in [widthPx], and always one. A grid sizes
     * itself by the room it has, not by a column count written for one orientation.
     */
    fun columns(widthPx: Int, minColumnPx: Int, gapPx: Int): Int =
        if (minColumnPx <= 0) 1 else ((widthPx + gapPx) / (minColumnPx + gapPx)).coerceAtLeast(1)
}
