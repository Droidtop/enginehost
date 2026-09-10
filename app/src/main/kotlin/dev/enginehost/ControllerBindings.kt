package dev.enginehost

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.enginehost.api.EngineControllerEvent
import dev.enginehost.api.EnginePlugin
import org.json.JSONObject
import kotlin.math.abs

sealed interface ControllerBinding {
    /**
     * What this binding is called on the controller screen. Keys and axes
     * name themselves out of the platform's own vocabulary; only [None]
     * has a word of ours, which is why a context is needed.
     */
    fun label(context: Context): String

    data class Key(val keyCode: Int) : ControllerBinding {
        override fun label(context: Context): String =
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }

    data class Axis(val axis: Int, val direction: Int = 0) : ControllerBinding {
        override fun label(context: Context): String =
            MotionEvent.axisToString(axis).removePrefix("AXIS_") + when (direction) {
                -1 -> " −"
                1 -> " +"
                else -> ""
            }
    }

    /**
     * Bound to nothing, and a binding in its own right rather than an
     * absence: any action may be None, and an engine feature driven by an
     * action (KiriKiri's pointer emulation on the stick axes) is simply
     * off while its actions are unbound. Encodes as `{"type":"none"}`.
     *
     * Nothing ships this way: every action in every set below carries a
     * real default. None is what a person chooses, not what we hand them.
     */
    object None : ControllerBinding {
        override fun label(context: Context): String = context.getString(R.string.binding_unbound)
    }
}

data class ControllerAction(val id: String, val title: String, val default: ControllerBinding)

private fun key(id: String, title: String, keyCode: Int) =
    ControllerAction(id, title, ControllerBinding.Key(keyCode))

private fun axis(id: String, title: String, axis: Int, direction: Int = 0) =
    ControllerAction(id, title, ControllerBinding.Axis(axis, direction))

/**
 * Which action set and which stored bindings a game belongs to.
 *
 * Usually the engine family, because a family is one engine. RPG Maker is
 * the exception: `rpgmaker` names three unrelated runtimes (mkxp-z's RGSS,
 * EasyRPG's RPG_RT and the MV/MZ web runtime), each with its own inputs and
 * its own names for them, so the family alone cannot say which actions to
 * offer. Those scopes carry the context: `rpgmaker/vxace`, `rpgmaker/2000`.
 */
object ControllerScope {
    /** Families where one engine id covers several engines. */
    private val contextual = setOf("rpgmaker")

    fun of(engine: String?, engineContext: String?): String? {
        val family = engine?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (family !in contextual) return family
        val context = engineContext?.lowercase()
            ?.takeIf { it.isNotBlank() && it != DEFAULT_ENGINE_CONTEXT }
            ?: return family
        return "$family/$context"
    }
}

/**
 * The actions a person may bind, per engine.
 *
 * An engine's set is the engine's own inputs under the engine's own names:
 * RGSS's `Input` symbols for RPG Maker XP/VX/VX Ace, EasyRPG's button enum
 * for 2000/2003, `Input.gamepadMapper`'s names for MV/MZ, Ren'Py's pad
 * events, Godot's `JoyButton`/`JoyAxis`, CatSystem2's `startup.xml` action
 * names. Engines with no input model of their own (KiriKiri, Buriko, CMVS,
 * Flash, HTML) take [common], where the names are ours because nobody
 * else's exist.
 *
 * A set is one list and one lookup: the title of an action is the action's
 * title and the default of an action is the action's default, so there is
 * no second table to keep in step with this one.
 */
object ControllerActions {
    /** The fallback set, for engines with no input model of their own. */
    val common: List<ControllerAction> = listOf(
        key("up", "Up", KeyEvent.KEYCODE_DPAD_UP),
        key("down", "Down", KeyEvent.KEYCODE_DPAD_DOWN),
        key("left", "Left", KeyEvent.KEYCODE_DPAD_LEFT),
        key("right", "Right", KeyEvent.KEYCODE_DPAD_RIGHT),
        key("confirm", "Confirm", KeyEvent.KEYCODE_BUTTON_A),
        key("cancel", "Cancel", KeyEvent.KEYCODE_BUTTON_B),
        key("menu", "Menu", KeyEvent.KEYCODE_BUTTON_START),
        key("skip", "Skip", KeyEvent.KEYCODE_BUTTON_X),
        key("auto", "Auto", KeyEvent.KEYCODE_BUTTON_Y),
        key("history", "History", KeyEvent.KEYCODE_BUTTON_SELECT),
        key("quick_save", "Quick save", KeyEvent.KEYCODE_BUTTON_L1),
        key("quick_load", "Quick load", KeyEvent.KEYCODE_BUTTON_R1),
        key("page_previous", "Previous page", KeyEvent.KEYCODE_BUTTON_L2),
        key("page_next", "Next page", KeyEvent.KEYCODE_BUTTON_R2),
        axis("left_x", "Left stick horizontal", MotionEvent.AXIS_X),
        axis("left_y", "Left stick vertical", MotionEvent.AXIS_Y),
        axis("right_x", "Right stick horizontal", MotionEvent.AXIS_Z),
        axis("right_y", "Right stick vertical", MotionEvent.AXIS_RZ),
        axis("left_trigger", "Left trigger", MotionEvent.AXIS_LTRIGGER, 1),
        axis("right_trigger", "Right trigger", MotionEvent.AXIS_RTRIGGER, 1),
    )

    /**
     * KiriKiri (KAG) is keyboard and mouse only, so its actions are ours --
     * [common]'s ids with the words KAG players use, and the left stick
     * named for the pointer it steers rather than for the stick it sits on,
     * so unbinding it reads as turning the pointer off.
     */
    private val kirikiri: List<ControllerAction> = common.map { action ->
        when (action.id) {
            "confirm" -> action.copy(title = "Advance / click")
            "cancel" -> action.copy(title = "Cancel (Escape)")
            "menu" -> action.copy(title = "System menu")
            "skip" -> action.copy(title = "Skip (hold)")
            "auto" -> action.copy(title = "Auto mode")
            "history" -> action.copy(title = "Backlog")
            "quick_save" -> action.copy(title = "Not used (save from the system menu)")
            "quick_load" -> action.copy(title = "Not used (load from the system menu)")
            "page_previous" -> action.copy(title = "Back one line")
            "page_next" -> action.copy(title = "Forward one line")
            "left_x" -> action.copy(title = "Pointer horizontal")
            "left_y" -> action.copy(title = "Pointer vertical")
            else -> action
        }
    }

    /**
     * Ren'Py's own pad events (`config.pad_bindings`, `00keymap.rpy`).
     * The engine's model is the pad itself, so the defaults are the
     * identity mapping: this physical control reports as that pad event.
     * That is exactly what Ren'Py sees through SDL today, which is why
     * turning bypass off changes nothing until the person remaps.
     */
    private val renpy: List<ControllerAction> = listOf(
        key("pad_dpup", "D-pad up", KeyEvent.KEYCODE_DPAD_UP),
        key("pad_dpdown", "D-pad down", KeyEvent.KEYCODE_DPAD_DOWN),
        key("pad_dpleft", "D-pad left", KeyEvent.KEYCODE_DPAD_LEFT),
        key("pad_dpright", "D-pad right", KeyEvent.KEYCODE_DPAD_RIGHT),
        key("pad_a", "A — confirm", KeyEvent.KEYCODE_BUTTON_A),
        key("pad_b", "B — cancel", KeyEvent.KEYCODE_BUTTON_B),
        key("pad_x", "X", KeyEvent.KEYCODE_BUTTON_X),
        key("pad_y", "Y", KeyEvent.KEYCODE_BUTTON_Y),
        key("pad_start", "Start — game menu", KeyEvent.KEYCODE_BUTTON_START),
        key("pad_back", "Back — rollback", KeyEvent.KEYCODE_BUTTON_SELECT),
        key("pad_guide", "Guide", KeyEvent.KEYCODE_BUTTON_MODE),
        key("pad_leftshoulder", "L1 — rollback", KeyEvent.KEYCODE_BUTTON_L1),
        key("pad_rightshoulder", "R1 — rollforward", KeyEvent.KEYCODE_BUTTON_R1),
        key("pad_leftstick", "Left stick click", KeyEvent.KEYCODE_BUTTON_THUMBL),
        key("pad_rightstick", "Right stick click", KeyEvent.KEYCODE_BUTTON_THUMBR),
        axis("pad_lefttrigger", "L2 — left trigger", MotionEvent.AXIS_LTRIGGER, 1),
        axis("pad_righttrigger", "R2 — right trigger", MotionEvent.AXIS_RTRIGGER, 1),
        axis("pad_leftx", "Left stick horizontal", MotionEvent.AXIS_X),
        axis("pad_lefty", "Left stick vertical", MotionEvent.AXIS_Y),
        axis("pad_rightx", "Right stick horizontal", MotionEvent.AXIS_Z),
        axis("pad_righty", "Right stick vertical", MotionEvent.AXIS_RZ),
    )

    /**
     * Godot's `JoyButton` and `JoyAxis` (`core/input/input_enums.h`). A
     * Godot game defines its own InputMap actions, so the only vocabulary
     * we can honestly offer is the pad, and remapping means "report this
     * control as that Godot button". The defaults are the identity mapping
     * Godot's own Android handler already performs
     * (`GodotInputHandler.getGodotButton`), including its two oddities:
     * Android's L2 arrives as MISC1 and R2 as PADDLE1. The paddles Godot
     * cannot receive on Android (2 to 4) and the touchpad are not offered,
     * because no control on any Android pad produces them.
     */
    private val godot: List<ControllerAction> = listOf(
        key("joy_dpad_up", "D-pad up", KeyEvent.KEYCODE_DPAD_UP),
        key("joy_dpad_down", "D-pad down", KeyEvent.KEYCODE_DPAD_DOWN),
        key("joy_dpad_left", "D-pad left", KeyEvent.KEYCODE_DPAD_LEFT),
        key("joy_dpad_right", "D-pad right", KeyEvent.KEYCODE_DPAD_RIGHT),
        key("joy_a", "A", KeyEvent.KEYCODE_BUTTON_A),
        key("joy_b", "B", KeyEvent.KEYCODE_BUTTON_B),
        key("joy_x", "X", KeyEvent.KEYCODE_BUTTON_X),
        key("joy_y", "Y", KeyEvent.KEYCODE_BUTTON_Y),
        key("joy_start", "Start", KeyEvent.KEYCODE_BUTTON_START),
        key("joy_back", "Back", KeyEvent.KEYCODE_BUTTON_SELECT),
        key("joy_guide", "Guide", KeyEvent.KEYCODE_BUTTON_MODE),
        key("joy_left_shoulder", "L1", KeyEvent.KEYCODE_BUTTON_L1),
        key("joy_right_shoulder", "R1", KeyEvent.KEYCODE_BUTTON_R1),
        key("joy_misc1", "Misc 1 (L2 on Android)", KeyEvent.KEYCODE_BUTTON_L2),
        key("joy_paddle1", "Paddle 1 (R2 on Android)", KeyEvent.KEYCODE_BUTTON_R2),
        key("joy_left_stick", "Left stick click", KeyEvent.KEYCODE_BUTTON_THUMBL),
        key("joy_right_stick", "Right stick click", KeyEvent.KEYCODE_BUTTON_THUMBR),
        axis("joy_axis_left_x", "Left stick horizontal", MotionEvent.AXIS_X),
        axis("joy_axis_left_y", "Left stick vertical", MotionEvent.AXIS_Y),
        axis("joy_axis_right_x", "Right stick horizontal", MotionEvent.AXIS_Z),
        axis("joy_axis_right_y", "Right stick vertical", MotionEvent.AXIS_RZ),
        axis("joy_trigger_left", "Left trigger", MotionEvent.AXIS_LTRIGGER, 1),
        axis("joy_trigger_right", "Right trigger", MotionEvent.AXIS_RTRIGGER, 1),
    )

    /**
     * RGSS's `Input` symbols (mkxp-z, `src/input/input.h`). Every button
     * that does something today keeps its place: A is C (confirm), B is B
     * (cancel), Y is A (dash), Start is the menu.
     *
     * RGSS has no separate menu input -- B *is* the menu -- and one action
     * takes one binding, so Start needs an action of its own to reach the
     * same engine input. That is RGSS's own idea rather than a workaround:
     * its default table binds two keys to `Input::B` (Escape and KP 0).
     *
     * Shift, Alt and F5 to F9 have no free pad control left, so they keep
     * the keyboard keys RGSS itself gives them
     * (`src/input/keybindings.cpp`); a person who wants one on the pad
     * binds it, and nothing ships bound to nothing.
     */
    private val rgss: List<ControllerAction> = listOf(
        key("rgss_up", "Up", KeyEvent.KEYCODE_DPAD_UP),
        key("rgss_down", "Down", KeyEvent.KEYCODE_DPAD_DOWN),
        key("rgss_left", "Left", KeyEvent.KEYCODE_DPAD_LEFT),
        key("rgss_right", "Right", KeyEvent.KEYCODE_DPAD_RIGHT),
        key("rgss_c", "Confirm (RGSS C)", KeyEvent.KEYCODE_BUTTON_A),
        key("rgss_b", "Cancel (RGSS B)", KeyEvent.KEYCODE_BUTTON_B),
        key("rgss_b_second", "Menu (RGSS B, second binding)", KeyEvent.KEYCODE_BUTTON_START),
        key("rgss_a", "Dash (RGSS A)", KeyEvent.KEYCODE_BUTTON_Y),
        key("rgss_ctrl", "Skip (RGSS Ctrl)", KeyEvent.KEYCODE_BUTTON_X),
        key("rgss_x", "RGSS X", KeyEvent.KEYCODE_BUTTON_SELECT),
        key("rgss_y", "RGSS Y", KeyEvent.KEYCODE_BUTTON_L1),
        key("rgss_z", "RGSS Z", KeyEvent.KEYCODE_BUTTON_R1),
        key("rgss_l", "RGSS L (page up)", KeyEvent.KEYCODE_BUTTON_L2),
        key("rgss_r", "RGSS R (page down)", KeyEvent.KEYCODE_BUTTON_R2),
        key("rgss_shift", "Shift key", KeyEvent.KEYCODE_SHIFT_LEFT),
        key("rgss_alt", "Alt key", KeyEvent.KEYCODE_ALT_LEFT),
        key("rgss_f5", "F5", KeyEvent.KEYCODE_F5),
        key("rgss_f6", "F6", KeyEvent.KEYCODE_F6),
        key("rgss_f7", "F7", KeyEvent.KEYCODE_F7),
        key("rgss_f8", "F8", KeyEvent.KEYCODE_F8),
        key("rgss_f9", "F9", KeyEvent.KEYCODE_F9),
        axis("left_x", "Left stick horizontal", MotionEvent.AXIS_X),
        axis("left_y", "Left stick vertical", MotionEvent.AXIS_Y),
        axis("right_x", "Right stick horizontal", MotionEvent.AXIS_Z),
        axis("right_y", "Right stick vertical", MotionEvent.AXIS_RZ),
    )

    /**
     * EasyRPG's `Input::InputButton` enum, with the engine's own display
     * names (`src/input_buttons.h`). The defaults are EasyRPG's own
     * joystick table (`src/input_buttons_desktop.cpp`) wherever it names a
     * control -- Select really is RESET there, the shoulders really are
     * abort-event and show-FPS, the stick clicks really are N0 and N5 --
     * so switching bypass off leaves the pad doing what the engine already
     * does. Two cells the engine cannot decide for us:
     *
     * - X is a second CANCEL in EasyRPG's table, and one action takes one
     *   binding, so it keeps this host's X (fast forward, our "skip").
     * - The right stick is eight numpad directions there; four of them are
     *   expressible as signed axes (N4/N6/N8/N2) and the diagonals keep
     *   the numpad keys EasyRPG gives them on a keyboard.
     *
     * L2/R2 stay this host's page pair, which EasyRPG has only on the
     * keyboard; the trigger axes take the engine's own trigger actions.
     */
    private val easyrpg: List<ControllerAction> = listOf(
        key("easyrpg_up", "Up Direction", KeyEvent.KEYCODE_DPAD_UP),
        key("easyrpg_down", "Down Direction", KeyEvent.KEYCODE_DPAD_DOWN),
        key("easyrpg_left", "Left Direction", KeyEvent.KEYCODE_DPAD_LEFT),
        key("easyrpg_right", "Right Direction", KeyEvent.KEYCODE_DPAD_RIGHT),
        key("easyrpg_decision", "Decision", KeyEvent.KEYCODE_BUTTON_A),
        key("easyrpg_cancel", "Cancel", KeyEvent.KEYCODE_BUTTON_B),
        key("easyrpg_shift", "Shift", KeyEvent.KEYCODE_BUTTON_Y),
        key("easyrpg_fast_forward_a", "Fast forward", KeyEvent.KEYCODE_BUTTON_X),
        key("easyrpg_settings_menu", "Settings menu", KeyEvent.KEYCODE_BUTTON_START),
        key("easyrpg_reset", "Reset to title", KeyEvent.KEYCODE_BUTTON_SELECT),
        key("easyrpg_debug_abort_event", "Abort event (debug)", KeyEvent.KEYCODE_BUTTON_L1),
        key("easyrpg_toggle_fps", "Toggle FPS display", KeyEvent.KEYCODE_BUTTON_R1),
        key("easyrpg_page_up", "Page up", KeyEvent.KEYCODE_BUTTON_L2),
        key("easyrpg_page_down", "Page down", KeyEvent.KEYCODE_BUTTON_R2),
        key("easyrpg_n0", "Number 0", KeyEvent.KEYCODE_BUTTON_THUMBL),
        key("easyrpg_n5", "Number 5", KeyEvent.KEYCODE_BUTTON_THUMBR),
        axis("easyrpg_debug_through", "Walk through walls (debug)", MotionEvent.AXIS_LTRIGGER, 1),
        axis("easyrpg_fast_forward_b", "Fast forward (plus)", MotionEvent.AXIS_RTRIGGER, 1),
        axis("easyrpg_n4", "Number 4", MotionEvent.AXIS_Z, -1),
        axis("easyrpg_n6", "Number 6", MotionEvent.AXIS_Z, 1),
        axis("easyrpg_n8", "Number 8", MotionEvent.AXIS_RZ, -1),
        axis("easyrpg_n2", "Number 2", MotionEvent.AXIS_RZ, 1),
        key("easyrpg_n1", "Number 1", KeyEvent.KEYCODE_NUMPAD_1),
        key("easyrpg_n3", "Number 3", KeyEvent.KEYCODE_NUMPAD_3),
        key("easyrpg_n7", "Number 7", KeyEvent.KEYCODE_NUMPAD_7),
        key("easyrpg_n9", "Number 9", KeyEvent.KEYCODE_NUMPAD_9),
        key("easyrpg_plus", "Plus", KeyEvent.KEYCODE_NUMPAD_ADD),
        key("easyrpg_minus", "Minus", KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
        key("easyrpg_multiply", "Multiply", KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
        key("easyrpg_divide", "Divide", KeyEvent.KEYCODE_NUMPAD_DIVIDE),
        key("easyrpg_period", "Period", KeyEvent.KEYCODE_NUMPAD_DOT),
        key("easyrpg_debug_menu", "Debug menu", KeyEvent.KEYCODE_F9),
        key("easyrpg_debug_save", "Debug save", KeyEvent.KEYCODE_F11),
        key("easyrpg_take_screenshot", "Take screenshot", KeyEvent.KEYCODE_F7),
        key("easyrpg_show_log", "Show log", KeyEvent.KEYCODE_F3),
        key("easyrpg_toggle_fullscreen", "Toggle fullscreen", KeyEvent.KEYCODE_F4),
        key("easyrpg_toggle_zoom", "Toggle zoom", KeyEvent.KEYCODE_F5),
        axis("left_x", "Left stick horizontal", MotionEvent.AXIS_X),
        axis("left_y", "Left stick vertical", MotionEvent.AXIS_Y),
    )

    /**
     * RPG Maker MV/MZ's own names, from `Input.gamepadMapper` and
     * `Input.keyMapper`. This is the family where the engine's model is
     * richer than what the wrapper sends it: MV/MZ separates cancel, menu
     * and escape, which one Escape keypress cannot express.
     *
     * Every button keeps its place. Select, L1 and R1 have no host action
     * in MV/MZ (no history, no quick save or load), so they take the three
     * inputs MV/MZ has and the pad had nowhere to put: escape, tab and
     * debug. Debug is inert outside a playtest build, which is why a
     * shoulder button is a safe home for it.
     */
    private val mvmz: List<ControllerAction> = listOf(
        key("mvmz_up", "Up", KeyEvent.KEYCODE_DPAD_UP),
        key("mvmz_down", "Down", KeyEvent.KEYCODE_DPAD_DOWN),
        key("mvmz_left", "Left", KeyEvent.KEYCODE_DPAD_LEFT),
        key("mvmz_right", "Right", KeyEvent.KEYCODE_DPAD_RIGHT),
        key("mvmz_ok", "OK", KeyEvent.KEYCODE_BUTTON_A),
        key("mvmz_cancel", "Cancel", KeyEvent.KEYCODE_BUTTON_B),
        key("mvmz_menu", "Menu", KeyEvent.KEYCODE_BUTTON_START),
        key("mvmz_control", "Control (skip)", KeyEvent.KEYCODE_BUTTON_X),
        key("mvmz_shift", "Shift (dash)", KeyEvent.KEYCODE_BUTTON_Y),
        key("mvmz_escape", "Escape", KeyEvent.KEYCODE_BUTTON_SELECT),
        key("mvmz_tab", "Tab", KeyEvent.KEYCODE_BUTTON_L1),
        key("mvmz_debug", "Debug (playtest only)", KeyEvent.KEYCODE_BUTTON_R1),
        key("mvmz_pageup", "Page up", KeyEvent.KEYCODE_BUTTON_L2),
        key("mvmz_pagedown", "Page down", KeyEvent.KEYCODE_BUTTON_R2),
        axis("left_x", "Left stick horizontal", MotionEvent.AXIS_X),
        axis("left_y", "Left stick vertical", MotionEvent.AXIS_Y),
        axis("right_x", "Right stick horizontal", MotionEvent.AXIS_Z),
        axis("right_y", "Right stick vertical", MotionEvent.AXIS_RZ),
    )

    /**
     * CatSystem2's own action names, from the `<KEY>` section of a game's
     * `startup.xml` (key00 to key28). This family fits the host's layout
     * better than any other: quick save and quick load are real engine
     * actions here (F1/F2), the message log is real (F8/Tab), and skip and
     * auto even carry the engine's own joypad slots. Every existing button
     * therefore keeps a meaningful action rather than only its position.
     *
     * The actions with no room on the face buttons take controls the host
     * layout leaves free -- the stick clicks, the trigger axes and the
     * right stick -- rather than the keyboard, because the pad has them:
     * the right stick scrolls the log and trims auto speed, the triggers
     * hold force-skip and replay the last voice line. Only the two
     * option-history slots keep their keyboard keys (F6/F7), and the
     * window slots (screen mode, minimize, quit) are not offered at all:
     * those are the host's business, not a game's.
     */
    private val catsystem2: List<ControllerAction> = listOf(
        key("cs2_cursor_up", "Cursor Up", KeyEvent.KEYCODE_DPAD_UP),
        key("cs2_cursor_down", "Cursor Down", KeyEvent.KEYCODE_DPAD_DOWN),
        key("cs2_cursor_left", "Cursor Left", KeyEvent.KEYCODE_DPAD_LEFT),
        key("cs2_cursor_right", "Cursor Right", KeyEvent.KEYCODE_DPAD_RIGHT),
        key("cs2_confirm", "Confirm", KeyEvent.KEYCODE_BUTTON_A),
        key("cs2_cancel", "Cancel", KeyEvent.KEYCODE_BUTTON_B),
        key("cs2_options_screen", "Options Screen", KeyEvent.KEYCODE_BUTTON_START),
        key("cs2_skip_mode", "Skip Mode", KeyEvent.KEYCODE_BUTTON_X),
        key("cs2_auto_mode", "Auto Mode", KeyEvent.KEYCODE_BUTTON_Y),
        key("cs2_message_log", "Message Log", KeyEvent.KEYCODE_BUTTON_SELECT),
        key("cs2_quick_save", "Quick Save", KeyEvent.KEYCODE_BUTTON_L1),
        key("cs2_quick_load", "Quick Load", KeyEvent.KEYCODE_BUTTON_R1),
        key("cs2_page_up", "Page Up", KeyEvent.KEYCODE_BUTTON_L2),
        key("cs2_page_down", "Page Down", KeyEvent.KEYCODE_BUTTON_R2),
        key("cs2_advance_text", "Advance Text", KeyEvent.KEYCODE_BUTTON_THUMBL),
        key("cs2_hide_message_window", "Hide Message Window", KeyEvent.KEYCODE_BUTTON_THUMBR),
        key("cs2_skip_to_next_option", "Skip to Next Option", KeyEvent.KEYCODE_F7),
        key("cs2_return_to_last_option", "Return to Last Option", KeyEvent.KEYCODE_F6),
        axis("cs2_force_skip", "Force Skip", MotionEvent.AXIS_RTRIGGER, 1),
        axis("cs2_replay_voice", "Replay Voice", MotionEvent.AXIS_LTRIGGER, 1),
        axis("cs2_scroll_up", "Scroll Up", MotionEvent.AXIS_RZ, -1),
        axis("cs2_scroll_down", "Scroll Down", MotionEvent.AXIS_RZ, 1),
        axis("cs2_auto_speed_down", "Auto Speed Down", MotionEvent.AXIS_Z, -1),
        axis("cs2_auto_speed_up", "Auto Speed Up", MotionEvent.AXIS_Z, 1),
        axis("left_x", "Pointer horizontal", MotionEvent.AXIS_X),
        axis("left_y", "Pointer vertical", MotionEvent.AXIS_Y),
    )

    /**
     * The engines with a set of their own. Everything absent here takes
     * [common]: KiriKiri's neighbours Buriko, Flash and HTML have no input
     * model to borrow names from, and CMVS's 24 `key.cfg` functions have
     * no names in the engine yet -- naming them is the CMVS owner's
     * reverse-engineering work, and inventing names for them here would be
     * fabrication.
     */
    private val sets: Map<String, List<ControllerAction>> = mapOf(
        "renpy" to renpy,
        "godot" to godot,
        "kirikiri" to kirikiri,
        "kirikiri2" to kirikiri,
        "catsystem2" to catsystem2,
        "rpgmaker/xp" to rgss,
        "rpgmaker/vx" to rgss,
        "rpgmaker/vxace" to rgss,
        "rpgmaker/2000" to easyrpg,
        "rpgmaker/2003" to easyrpg,
        "rpgmaker/mv" to mvmz,
        "rpgmaker/mz" to mvmz,
    )

    /**
     * Engines whose own controller support is good enough to use instead
     * of ours: Ren'Py and Godot take the pad through SDL and Godot's own
     * handler, and EasyRPG ships the fullest joystick default table of any
     * engine here. Their bypass is on out of the box.
     */
    private val bypassable: Set<String> = setOf("renpy", "godot", "rpgmaker/2000", "rpgmaker/2003")

    /** The action set for a [ControllerScope]; [common] for anything else. */
    fun forEngine(engine: String?): List<ControllerAction> =
        engine?.lowercase()?.let { sets[it] } ?: common

    /** Whether the controller screen offers this scope a bypass toggle. */
    fun offersBypass(engine: String?): Boolean = engine?.lowercase() in bypassable
}

/**
 * Controller bindings, resolved per engine over a global default.
 *
 * [engine] is a [ControllerScope]; null means the global scope -- the map
 * that applies wherever an engine has not overridden it. A VN and an RPG
 * Maker game want different things from the same pad, so one flat map
 * cannot serve both.
 *
 * Resolution is engine override, then global override, then the action's
 * own default. Two levels and one lookup: an engine's defaults live in its
 * action set ([ControllerActions.forEngine]) rather than in a third table
 * beside it. Remapping an action in the global scope therefore reaches
 * every engine that has that action, which is what a person means by "all
 * engines"; ids that exist only under one engine have no global row at all
 * and resolve straight to their default.
 */
class ControllerBindingStore(context: Context, private val engine: String? = null) {
    private val preferences = context.getSharedPreferences("controller-bindings-v1", Context.MODE_PRIVATE)

    private fun scopedKey(action: ControllerAction): String? =
        engine?.lowercase()?.let { "engine.$it.${action.id}" }

    private fun bypassKey(): String? = engine?.lowercase()?.let { "bypass.$it" }

    private fun read(key: String): ControllerBinding? = preferences.getString(key, null)
        ?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    /** The actions this scope offers, in the engine's own vocabulary. */
    fun actions(): List<ControllerAction> = ControllerActions.forEngine(engine)

    fun get(action: ControllerAction): ControllerBinding =
        scopedKey(action)?.let(::read)
            ?: read(action.id)
            ?: action.default

    /** True when this engine overrides [action] rather than inheriting it. */
    fun isOverridden(action: ControllerAction): Boolean =
        scopedKey(action)?.let { preferences.contains(it) } == true

    /**
     * Whether the engine handles the controller itself for this scope. On
     * by default where it is offered at all, and the whole of the contract
     * with the plugin: bypassed means the runtime intent carries no
     * controller map, so the engine's own handling is the only handling.
     */
    fun isBypassed(): Boolean = ControllerActions.offersBypass(engine) &&
        bypassKey()?.let { preferences.getBoolean(it, true) } == true

    fun setBypass(bypass: Boolean) {
        bypassKey()?.let { preferences.edit().putBoolean(it, bypass).apply() }
    }

    fun set(action: ControllerAction, binding: ControllerBinding) {
        val key = scopedKey(action) ?: action.id
        preferences.edit().putString(key, encode(binding).toString()).apply()
    }

    /**
     * Drops this engine's override so the action inherits the global
     * binding again. A no-op in the global scope, where there is nothing
     * above to inherit from.
     */
    fun clearOverride(action: ControllerAction) {
        scopedKey(action)?.let { preferences.edit().remove(it).apply() }
    }

    /**
     * The resolved map for every action this engine offers, as the runtime
     * extra a plugin on the android-activity transport reads: `{ "confirm":
     * {"type":"key","code":96}, "left_x": {"type":"axis","axis":0,
     * "direction":0}, ... }`, with the engine's own action ids as the keys.
     * Plugin-api plugins get the same map applied for them by
     * [RuntimeControllerRouter]; activity plugins own their input and must
     * apply it themselves, so the pad means what the person configured in
     * either case.
     */
    fun exportJson(): JSONObject = JSONObject().apply {
        actions().forEach { action -> put(action.id, encode(get(action))) }
    }

    /** Clears this scope only; the global map survives an engine reset. */
    fun reset() {
        if (engine == null) {
            preferences.edit().clear().apply()
            return
        }
        val prefixes = listOfNotNull("engine.${engine.lowercase()}.", bypassKey())
        preferences.edit().apply {
            preferences.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach { remove(it) }
        }.apply()
    }

    private fun encode(binding: ControllerBinding) = when (binding) {
        is ControllerBinding.Key -> JSONObject().put("type", "key").put("code", binding.keyCode)
        is ControllerBinding.Axis -> JSONObject().put("type", "axis").put("axis", binding.axis)
            .put("direction", binding.direction)
        is ControllerBinding.None -> JSONObject().put("type", "none")
    }

    private fun parse(json: JSONObject): ControllerBinding = when (json.getString("type")) {
        "key" -> ControllerBinding.Key(json.getInt("code"))
        "axis" -> ControllerBinding.Axis(json.getInt("axis"), json.optInt("direction"))
        "none" -> ControllerBinding.None
        else -> error("Unknown controller binding")
    }
}

/**
 * [engine] is the [ControllerScope] of the bundle this session is running,
 * so the user's per-engine mappings actually apply while playing rather
 * than only existing in settings. A bypassed scope routes nothing: the
 * events fall through to the engine's own handling, which is the same
 * thing the absent intent extra tells an activity plugin.
 */
class RuntimeControllerRouter(
    context: Context,
    engine: String? = null,
    private val plugin: () -> EnginePlugin?,
) {
    private val bindings = ControllerBindingStore(context, engine)
    private var actions = bindings.actions()
    private var bypassed = bindings.isBypassed()

    /**
     * Re-reads the map. The runtime calls this when it comes back to the
     * front, so a change made in the controller screen and then returned
     * from is in force without the game being restarted.
     *
     * Only what this class caches is re-read; a binding itself is read out
     * of the store on every event already.
     */
    fun refresh() {
        actions = bindings.actions()
        bypassed = bindings.isBypassed()
    }

    fun key(event: KeyEvent): Boolean {
        if (bypassed || !event.isControllerInput()) return false
        val value = if (event.action == KeyEvent.ACTION_UP) 0f else 1f
        return actions.asSequence()
            .filter { (bindings.get(it) as? ControllerBinding.Key)?.keyCode == event.keyCode }
            .map { dispatch(it.id, value, event.deviceId, event.eventTime) }
            .fold(false) { consumed, current -> consumed || current }
    }

    fun motion(event: MotionEvent): Boolean {
        if (bypassed || !event.isControllerInput()) return false
        return actions.asSequence().mapNotNull { action ->
            val binding = bindings.get(action) as? ControllerBinding.Axis ?: return@mapNotNull null
            val raw = event.getAxisValue(binding.axis)
            val value = when (binding.direction) {
                -1 -> (-raw).coerceAtLeast(0f)
                1 -> raw.coerceAtLeast(0f)
                else -> if (abs(raw) < DEAD_ZONE) 0f else raw
            }
            dispatch(action.id, value, event.deviceId, event.eventTime)
        }.fold(false) { consumed, current -> consumed || current }
    }

    private fun dispatch(action: String, value: Float, deviceId: Int, time: Long): Boolean {
        val descriptor = InputDevice.getDevice(deviceId)?.descriptor.orEmpty()
        return runCatching {
            plugin()?.onControllerEvent(EngineControllerEvent(action, value, deviceId, descriptor, time)) == true
        }.getOrDefault(false)
    }

    companion object { private const val DEAD_ZONE = 0.18f }
}

internal fun KeyEvent.isControllerInput(): Boolean =
    InputDevice.getDevice(deviceId)?.sources?.let { sources ->
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    } == true

internal fun MotionEvent.isControllerInput(): Boolean =
    (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
