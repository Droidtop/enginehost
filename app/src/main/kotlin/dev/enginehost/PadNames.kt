package dev.enginehost

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * What a control is called where a person reads it: the letter or name printed on the pad
 * ("A", "L2", "Start", "Left stick ←"), not the platform's constant ("BUTTON_A", "AXIS_LTRIGGER +").
 * The one place controls are named, for the controller screen, the in-game Controls sheet,
 * the host menu shortcut and the profile page (Droidtop/tracker#620 E8). A keyboard key, or
 * anything a pad does not print, keeps the platform's name, made readable.
 */
object PadNames {
    fun key(keyCode: Int): String = KEYS[keyCode] ?: readable(KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_"))

    /** [direction] is -1 or 1 for one half of an axis, 0 for the whole axis. */
    fun axis(axis: Int, direction: Int = 0): String {
        val stick = STICKS[axis]
        if (stick != null) {
            val (name, horizontal) = stick
            val arrow = when (direction) {
                -1 -> if (horizontal) " ←" else " ↑"
                1 -> if (horizontal) " →" else " ↓"
                else -> if (horizontal) " ↔" else " ↕"
            }
            return name + arrow
        }
        // A trigger reads as the button it is; its "negative half" is never pressed.
        TRIGGERS[axis]?.let { return it }
        HAT[axis]?.let { (negative, positive, whole) ->
            return when (direction) {
                -1 -> negative
                1 -> positive
                else -> whole
            }
        }
        return readable(MotionEvent.axisToString(axis).removePrefix("AXIS_")) + when (direction) {
            -1 -> " −"
            1 -> " +"
            else -> ""
        }
    }

    /** A chord, as the pad shows it: "Select + Start". */
    fun chord(keyCodes: Collection<Int>): String = keyCodes.sorted().joinToString(" + ") { key(it) }

    /** "DPAD_UP" → "Dpad up", "ENTER" → "Enter": a platform name nobody has to decode. */
    internal fun readable(constant: String): String =
        constant.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private val KEYS: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to "A",
        KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_C to "C",
        KeyEvent.KEYCODE_BUTTON_X to "X",
        KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_Z to "Z",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1",
        KeyEvent.KEYCODE_BUTTON_R1 to "R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "L2",
        KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_BUTTON_START to "Start",
        KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
        KeyEvent.KEYCODE_BUTTON_MODE to "Home",
        KeyEvent.KEYCODE_DPAD_UP to "D-pad ↑",
        KeyEvent.KEYCODE_DPAD_DOWN to "D-pad ↓",
        KeyEvent.KEYCODE_DPAD_LEFT to "D-pad ←",
        KeyEvent.KEYCODE_DPAD_RIGHT to "D-pad →",
        KeyEvent.KEYCODE_DPAD_CENTER to "D-pad centre",
        KeyEvent.KEYCODE_BACK to "Back",
    )

    /** Each stick axis: the stick's name, and whether it is the horizontal one. */
    private val STICKS: Map<Int, Pair<String, Boolean>> = mapOf(
        MotionEvent.AXIS_X to ("Left stick" to true),
        MotionEvent.AXIS_Y to ("Left stick" to false),
        MotionEvent.AXIS_Z to ("Right stick" to true),
        MotionEvent.AXIS_RZ to ("Right stick" to false),
        MotionEvent.AXIS_RX to ("Right stick" to true),
        MotionEvent.AXIS_RY to ("Right stick" to false),
    )

    private val TRIGGERS: Map<Int, String> = mapOf(
        MotionEvent.AXIS_LTRIGGER to "L2",
        MotionEvent.AXIS_RTRIGGER to "R2",
        MotionEvent.AXIS_BRAKE to "L2",
        MotionEvent.AXIS_GAS to "R2",
    )

    private val HAT: Map<Int, Triple<String, String, String>> = mapOf(
        MotionEvent.AXIS_HAT_X to Triple("D-pad ←", "D-pad →", "D-pad ↔"),
        MotionEvent.AXIS_HAT_Y to Triple("D-pad ↑", "D-pad ↓", "D-pad ↕"),
    )
}
