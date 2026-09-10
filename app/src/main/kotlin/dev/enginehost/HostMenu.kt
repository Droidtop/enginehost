package dev.enginehost

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent

/**
 * The shortcut that opens Enginehost's own menu during a game.
 *
 * One setting for the whole app -- not per engine and not per profile --
 * exactly as RetroArch keeps `input_menu_toggle_gamepad_combo` global
 * rather than per core (research/retroarch-input, section 5). A person
 * learns one gesture and it means the same thing in every game.
 *
 * The default is Select + Start, RetroArch's `INPUT_COMBO_START_SELECT`.
 * Two dedicated digital buttons that every pad on this device has, and
 * neither of the two obvious alternatives: Guide/Home is eaten by Android
 * before an app sees it, and L3/R3 are not reported reliably as separate
 * digital presses by many Android pads (research section 8, item 2).
 */
class HostMenuHotkeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun combo(): Set<Int> = preferences.getStringSet(KEY_COMBO, null)
        ?.mapNotNull(String::toIntOrNull)
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_COMBO

    fun setCombo(keyCodes: Set<Int>) {
        require(keyCodes.isNotEmpty()) { "A shortcut needs at least one button" }
        preferences.edit().putStringSet(KEY_COMBO, keyCodes.map(Int::toString).toSet()).apply()
    }

    /** Back to Select + Start. */
    fun reset() {
        preferences.edit().remove(KEY_COMBO).apply()
    }

    fun label(): String = label(combo())

    companion object {
        private const val PREFERENCES = "host-menu-v1"
        private const val KEY_COMBO = "combo"

        /** Select + Start; see the class comment for why these two. */
        val DEFAULT_COMBO: Set<Int> =
            setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START)

        fun label(combo: Set<Int>): String = combo.sorted()
            .joinToString(" + ") { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
    }
}

/**
 * Watches the pad for the host menu combination.
 *
 * Deliberately over plain key codes rather than [KeyEvent]s, so the rule
 * this class encodes -- which press opens the menu and which presses the
 * game must never see -- is testable without a device.
 *
 * The leak rule is RetroArch's, adapted honestly. RetroArch checks its
 * combo against the pre-core input bitmask, so the frame the combo
 * resolves never reaches the core; the presses *before* it does resolve
 * are ordinary input and the core sees them. Here the same: the press that
 * completes the combination is consumed, and so is every press and release
 * of a member of the combination until the person lets go of all of them,
 * because a release the game never asked for is worse than no release at
 * all. What is left over is the one press that did reach the game before
 * the combination completed -- [Verdict.Open.stuck] -- and the tap sends a
 * release for it so the game is not left holding a button down.
 */
class HostMenuCombo(private val combo: Set<Int>) {
    private val held = LinkedHashSet<Int>()
    private var engaged = false

    sealed interface Verdict {
        /** Not part of the combination: hand it on untouched. */
        object Pass : Verdict

        /** Part of the combination and the host's: the game must not see it. */
        object Consume : Verdict

        /**
         * The combination just completed. [stuck] are its members whose
         * press already reached the game and now need a release.
         */
        class Open(val stuck: Set<Int>) : Verdict
    }

    fun down(keyCode: Int): Verdict {
        if (combo.isEmpty() || keyCode !in combo) return Verdict.Pass
        if (engaged) return Verdict.Consume
        val alreadyDelivered = held.toSet()
        held += keyCode
        if (!held.containsAll(combo)) return Verdict.Pass
        engaged = true
        return Verdict.Open(alreadyDelivered)
    }

    fun up(keyCode: Int): Verdict {
        if (combo.isEmpty() || keyCode !in combo) return Verdict.Pass
        held -= keyCode
        if (!engaged) return Verdict.Pass
        if (held.isEmpty()) engaged = false
        return Verdict.Consume
    }
}

/**
 * Enginehost's in-game menu: the one screen of ours a person can reach
 * without leaving the game, drawn as a dialog over the running engine in
 * the `:runtime` process.
 *
 * Its contents are RetroArch's Quick Menu scoped to what this host
 * actually owns (research/retroarch-input, section 8 item 4): resume, the
 * controller screen for this game's scope, where the saves are, what the
 * pad does here, and a clean way out. Nothing engine-specific -- shaders,
 * cheats, core options -- because those belong to the engines.
 */
object HostMenu {
    private var open: AlertDialog? = null

    fun isOpen(): Boolean = open?.isShowing == true

    fun show(activity: Activity) {
        if (isOpen() || activity.isFinishing) return
        val scope = scopeOf(activity)
        val items = arrayOf(
            activity.getString(R.string.host_menu_resume),
            activity.getString(R.string.host_menu_controller),
            activity.getString(R.string.host_menu_save_location),
            activity.getString(R.string.host_menu_legend),
            activity.getString(R.string.host_menu_quit),
        )
        open = builder(activity)
            .setTitle(R.string.host_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    ITEM_RESUME -> Unit
                    ITEM_CONTROLLER -> activity.startActivity(ControllerConfigActivity.intent(activity, scope))
                    ITEM_SAVE_LOCATION -> info(
                        activity,
                        R.string.host_menu_save_location,
                        activity.intent.getStringExtra(RuntimeActivity.EXTRA_SAVE_PATH)
                            ?: activity.getString(R.string.host_menu_save_unknown),
                    )
                    ITEM_LEGEND -> info(activity, R.string.host_menu_legend, legend(activity, scope))
                    ITEM_QUIT -> quit(activity)
                }
            }
            .setOnDismissListener { open = null }
            .show()
    }

    /**
     * Ends the runtime the way it means to end: the crash note is closed
     * first, so quitting on purpose is never offered back to the person as
     * a crash to report. [RuntimeActivity] disarms again on its way out,
     * which costs nothing and keeps either path correct on its own.
     */
    private fun quit(activity: Activity) {
        CrashWatch.disarm(activity)
        activity.finish()
    }

    /** What the pad does in this game, in this engine's own words. */
    private fun legend(activity: Activity, scope: String?): String {
        val store = ControllerBindingStore(activity, scope)
        val rows = store.actions().joinToString("\n") { action ->
            activity.getString(R.string.binding_row, action.title, store.get(action).label(activity))
        }
        return if (store.isBypassed()) {
            activity.getString(R.string.host_menu_legend_bypassed) + "\n\n" + rows
        } else {
            rows
        }
    }

    private fun info(activity: Activity, titleRes: Int, message: String) {
        open = builder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.host_menu_back) { _, _ -> show(activity) }
            .setOnDismissListener { open = null }
            .show()
    }

    /**
     * A platform dialog theme rather than the runtime's own. The runtime
     * theme is a fullscreen game surface, and the plugin shapes do not
     * agree on whether their Activity is an AppCompat one, so the one
     * theme both shapes certainly have is the platform's.
     */
    private fun builder(activity: Activity): AlertDialog.Builder =
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    /** The scope this session is playing under; the same one settings shows. */
    fun scopeOf(activity: Activity): String? = ControllerScope.of(
        activity.intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE),
        activity.intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE_CONTEXT),
    )

    private const val ITEM_RESUME = 0
    private const val ITEM_CONTROLLER = 1
    private const val ITEM_SAVE_LOCATION = 2
    private const val ITEM_LEGEND = 3
    private const val ITEM_QUIT = 4
}
