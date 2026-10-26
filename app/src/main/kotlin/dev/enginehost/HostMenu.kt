package dev.enginehost

import android.app.Activity
import android.app.Dialog
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
 * The default is hold Select (400ms), matching droidtop's in-game menu trigger.
 * This replaces the previous Select+Start combo (RetroArch's `INPUT_COMBO_START_SELECT`).
 * Hold Select is the dedicated in-game menu button across both apps.
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

    /** Back to hold Select (400ms). */
    fun reset() {
        preferences.edit().remove(KEY_COMBO).apply()
    }

    fun label(): String = label(combo())

    companion object {
        internal const val PREFERENCES = "host-menu-v1"
        private const val KEY_COMBO = "combo"

        /** Hold Select (400ms); matches droidtop's in-game menu trigger. */
        val DEFAULT_COMBO: Set<Int> =
            setOf(KeyEvent.KEYCODE_BUTTON_SELECT)

        fun label(combo: Set<Int>): String = PadNames.chord(combo)
    }
}

/**
 * Watches the pad for the host menu trigger: hold Select (400ms).
 *
 * Deliberately over plain key codes rather than [KeyEvent]s, so the rule
 * this class encodes -- which press opens the menu and which presses the
 * game must never see -- is testable without a device.
 *
 * The leak rule is RetroArch's, adapted honestly. RetroArch checks its
 * combo against the pre-core input bitmask, so the frame the combo
 * resolves never reaches the core; the presses *before* it does resolve
 * are ordinary input and the core sees them. Here the same: the press that
 * completes the hold is consumed, and so is every press and release
 * of the trigger until the person lets go of it,
 * because a release the game never asked for is worse than no release at
 * all. What is left over is the one press that did reach the game before
 * the hold completed -- [Verdict.Open.stuck] -- and the tap sends a
 * release for it so the game is not left holding a button down.
 */
class HostMenuCombo(private val triggerKey: Int = KeyEvent.KEYCODE_BUTTON_SELECT, private val holdMs: Long = 400) {
    private var downTime: Long = 0
    private var engaged = false
    private var fired = false

    sealed interface Verdict {
        /** Not the trigger: hand it on untouched. */
        object Pass : Verdict

        /** The trigger and the host's: the game must not see it. */
        object Consume : Verdict

        /**
         * The hold just completed. [stuck] is the trigger if its press
         * already reached the game and now needs a release.
         */
        class Open(val stuck: Set<Int>) : Verdict
    }

    fun down(keyCode: Int, now: Long): Verdict {
        if (keyCode != triggerKey) return Verdict.Pass
        if (engaged) return Verdict.Consume
        downTime = now
        engaged = true
        fired = false
        return Verdict.Pass
    }

    fun up(keyCode: Int, now: Long): Verdict {
        if (keyCode != triggerKey) return Verdict.Pass
        if (!engaged) return Verdict.Pass
        engaged = false
        if (!fired) {
            // Tap - pass through to the game
            return Verdict.Open(setOf(triggerKey))
        }
        // Hold completed - consume the release
        return Verdict.Consume
    }

    fun tick(now: Long): Verdict? {
        if (engaged && !fired && now - downTime >= holdMs) {
            fired = true
            return Verdict.Open(emptySet())
        }
        return null
    }
}

/**
 * What Back does during a game: opens [HostMenu] on its release. Pure, so
 * the rule is testable without a device. Hold Select (400ms) ([HostMenuCombo])
 * stays the pad's shortcut; Back is the one that works on a handheld whose
 * engine reads every pad button for itself.
 */
object HostBack {
    fun owns(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BACK

    fun opensMenu(keyCode: Int, action: Int, cancelled: Boolean): Boolean =
        owns(keyCode) && action == KeyEvent.ACTION_UP && !cancelled
}

/**
 * Enginehost's in-game menu: the one screen of ours a person can reach
 * without leaving the game, drawn as a [Sheet] over the running engine in
 * the `:runtime` process.
 *
 * Its contents match droidtop's Quick Menu > This game (proposal section 3.4):
 * Resume, Saves, Controls, Display, Performance, Screenshot, Quit game,
 * plus an Engine group the engine adapter fills with the engine's own actions.
 * No engine-specific screen -- shaders, cheats, core options belong to the engines.
 */
object HostMenu {
    private var open: Dialog? = null
    private var combo: HostMenuCombo? = null

    fun isOpen(): Boolean = open?.isShowing == true

    fun show(activity: Activity) {
        if (isOpen() || activity.isFinishing) return
        val scope = scopeOf(activity)
        open = Sheet(activity)
            .title(R.string.host_menu_title)
            .choice(R.string.host_menu_resume) {}
            .choice(R.string.host_menu_saves) {
                info(activity, R.string.host_menu_saves, saveLocation(activity))
            }
            .choice(R.string.host_menu_controls) {
                activity.startActivity(ControllerConfigActivity.intent(activity, scope, gameOf(activity)))
            }
            .choice(R.string.host_menu_display) {
                info(activity, R.string.host_menu_display, "Display settings")
            }
            .choice(R.string.host_menu_performance) {
                info(activity, R.string.host_menu_performance, "Performance settings")
            }
            .choice(R.string.host_menu_screenshot) {
                takeScreenshot(activity)
            }
            .choice(R.string.host_menu_quit) { quit(activity) }
            .onDismiss(::closed)
            .show()
    }

    /**
     * Ends the runtime the way it means to end: the crash note is closed
     * first, so quitting on purpose is never offered back to the person as
     * a crash to report. [RuntimeActivity] disarms again on its way out,
     * which costs nothing and keeps either path correct on its own.
     */
    private fun quit(activity: Activity) {
        CrashWatch.quit(activity)
        activity.finish()
    }

    private fun takeScreenshot(activity: Activity) {
        // TODO: Implement screenshot capture
        info(activity, R.string.host_menu_screenshot, "Screenshot saved")
    }

    /**
     * Where this game's saves really are. The save folder the runtime was
     * handed is only what the engine's SYSTEM locations mean; an engine
     * that saves beside the game never writes there, so for it the answer
     * is the game's own folder (README, saves; UI assessment 2026-09-24).
     */
    private fun saveLocation(activity: Activity): String {
        val intent = activity.intent
        val engine = intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE)
        val game = intent.getStringExtra(RuntimeActivity.EXTRA_PATH)
        val saves = intent.getStringExtra(RuntimeActivity.EXTRA_SAVE_PATH)
        val place = engine?.let { SaveFolders.placeOf(it, intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE_CONTEXT)) }
        return when {
            place == SaveFolders.Place.BESIDE_THE_GAME && game != null ->
                activity.getString(R.string.host_menu_save_beside_game, game)
            saves == null -> activity.getString(R.string.host_menu_save_unknown)
            place == SaveFolders.Place.ENGINE_NAMESPACE ->
                activity.getString(R.string.host_menu_save_engine_namespace, saves)
            else -> activity.getString(R.string.host_menu_save_named, saves)
        }
    }

    /** What the pad does in this game, in this engine's own words. */
    private fun legend(activity: Activity, scope: String?): CharSequence {
        val store = ControllerBindingStore(activity, scope)
        val family = PadGlyphs.family(activity)
        val size = activity.resources.getDimension(R.dimen.eh_glyph_text)
        val rows = android.text.SpannableStringBuilder()
        store.actions().forEachIndexed { i, action ->
            if (i > 0) rows.append("\n")
            rows.append(PadGlyphs.fill(activity, R.string.binding_row, action.title, PadGlyphs.binding(activity, store.get(action), family, size)))
        }
        return if (store.isBypassed()) {
            android.text.SpannableStringBuilder(activity.getString(R.string.host_menu_legend_bypassed)).append("\n\n").append(rows)
        } else {
            rows
        }
    }

    /** A page of the menu: its sentence, and the way back to the menu. B closes the menu. */
    private fun info(activity: Activity, titleRes: Int, message: CharSequence) {
        open = Sheet(activity)
            .title(titleRes)
            .message(message)
            .choice(R.string.host_menu_back) { show(activity) }
            .onDismiss(::closed)
            .show()
    }

    /**
     * A sheet's dismissal is reported after the fact, so when a choice
     * opens the next page the old page's report arrives once the new one
     * is already up; only forget the menu when nothing of it is showing.
     */
    private fun closed() {
        if (open?.isShowing != true) open = null
    }

    /** The game this session is playing, as the controller screen names it ("This game"). */
    fun gameOf(activity: Activity): String? = activity.intent.getStringExtra(RuntimeActivity.EXTRA_GAME)

    /** The scope this session is playing under; the same one settings shows. */
    fun scopeOf(activity: Activity): String? = ControllerScope.of(
        activity.intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE),
        activity.intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE_CONTEXT),
    )
}
