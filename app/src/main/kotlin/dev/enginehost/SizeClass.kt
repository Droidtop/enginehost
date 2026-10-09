package dev.enginehost

import android.content.Context

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
    const val MEDIUM_MIN_DP = 600
    const val EXPANDED_MIN_DP = 840

    /** Under this height a bar along the bottom costs the content too much, so the destinations move to a rail. */
    const val SHORT_HEIGHT_DP = 480

    /** Whether the destinations sit in a rail at the left edge rather than a bar along the bottom. */
    fun usesRail(widthDp: Int, heightDp: Int): Boolean = widthDp >= MEDIUM_MIN_DP || heightDp < SHORT_HEIGHT_DP

    fun usesRail(context: Context): Boolean {
        val config = context.resources.configuration
        return usesRail(config.screenWidthDp, config.screenHeightDp)
    }

    /**
     * How many columns of at least [minColumnPx] fit in [widthPx], and always one. A grid sizes
     * itself by the room it has, not by a column count written for one orientation.
     */
    fun columns(widthPx: Int, minColumnPx: Int, gapPx: Int): Int =
        if (minColumnPx <= 0) 1 else ((widthPx + gapPx) / (minColumnPx + gapPx)).coerceAtLeast(1)
}
