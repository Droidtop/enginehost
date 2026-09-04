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
    fun label(): String

    data class Key(val keyCode: Int) : ControllerBinding {
        override fun label(): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }

    data class Axis(val axis: Int, val direction: Int = 0) : ControllerBinding {
        override fun label(): String = MotionEvent.axisToString(axis).removePrefix("AXIS_") + when (direction) {
            -1 -> " −"
            1 -> " +"
            else -> ""
        }
    }
}

data class ControllerAction(val id: String, val title: String, val default: ControllerBinding)

object ControllerActions {
    val all = listOf(
        ControllerAction("up", "Up", ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_UP)),
        ControllerAction("down", "Down", ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_DOWN)),
        ControllerAction("left", "Left", ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_LEFT)),
        ControllerAction("right", "Right", ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_RIGHT)),
        ControllerAction("confirm", "Confirm", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_A)),
        ControllerAction("cancel", "Cancel", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_B)),
        ControllerAction("menu", "Menu", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_START)),
        ControllerAction("skip", "Skip", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_X)),
        ControllerAction("auto", "Auto", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_Y)),
        ControllerAction("history", "History", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_SELECT)),
        ControllerAction("quick_save", "Quick save", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_L1)),
        ControllerAction("quick_load", "Quick load", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_R1)),
        ControllerAction("page_previous", "Previous page", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_L2)),
        ControllerAction("page_next", "Next page", ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_R2)),
        ControllerAction("left_x", "Left stick horizontal", ControllerBinding.Axis(MotionEvent.AXIS_X)),
        ControllerAction("left_y", "Left stick vertical", ControllerBinding.Axis(MotionEvent.AXIS_Y)),
        ControllerAction("right_x", "Right stick horizontal", ControllerBinding.Axis(MotionEvent.AXIS_Z)),
        ControllerAction("right_y", "Right stick vertical", ControllerBinding.Axis(MotionEvent.AXIS_RZ)),
        ControllerAction("left_trigger", "Left trigger", ControllerBinding.Axis(MotionEvent.AXIS_LTRIGGER, 1)),
        ControllerAction("right_trigger", "Right trigger", ControllerBinding.Axis(MotionEvent.AXIS_RTRIGGER, 1)),
    )

    /**
     * Per-engine defaults. An engine family listed here gets these bindings
     * before the global map is consulted, so remapping a button for every
     * engine at once never silently changes a family whose layout was
     * settled on purpose. RPG Maker's layout is the one played and approved
     * on hardware on 2026-09-03: the RGSS keys behind each action are the
     * plugin's business (see the mkxp-z wrapper), the buttons are these.
     */
    val engineDefaults: Map<String, Map<String, ControllerBinding>> = mapOf(
        "rpgmaker" to mapOf(
            "up" to ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_UP),
            "down" to ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_DOWN),
            "left" to ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_LEFT),
            "right" to ControllerBinding.Key(KeyEvent.KEYCODE_DPAD_RIGHT),
            "confirm" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_A),
            "cancel" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_B),
            "menu" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_START),
            "skip" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_X),
            "auto" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_Y),
            "history" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_SELECT),
            "quick_save" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_L1),
            "quick_load" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_R1),
            "page_previous" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_L2),
            "page_next" to ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_R2),
            "left_x" to ControllerBinding.Axis(MotionEvent.AXIS_X),
            "left_y" to ControllerBinding.Axis(MotionEvent.AXIS_Y),
        ),
    )

    /**
     * What an action is called for an engine family, when its own vocabulary
     * differs from the shared one. RPG Maker has no "auto" or "quick save";
     * those buttons are its dash and its X/Y/Z/L/R inputs, and the controller
     * screen should say so.
     */
    val engineTitles: Map<String, Map<String, String>> = mapOf(
        "rpgmaker" to mapOf(
            "confirm" to "Confirm (C)",
            "cancel" to "Cancel / menu (B)",
            "menu" to "Menu (B)",
            "skip" to "Skip messages (Ctrl)",
            "auto" to "Dash (A / Shift)",
            "history" to "X input (A key)",
            "quick_save" to "Y input (S key)",
            "quick_load" to "Z input (D key)",
            "page_previous" to "L input (Q key)",
            "page_next" to "R input (W key)",
        ),
    )

    fun title(action: ControllerAction, engine: String?): String =
        engine?.let { engineTitles[it.lowercase()]?.get(action.id) } ?: action.title
}

/**
 * Controller bindings, resolved per engine over a global default.
 *
 * [engine] null means the global scope -- the map that applies wherever
 * an engine has not overridden it. A VN and an RPG Maker game want
 * different things from the same pad (skip and auto-advance mean nothing
 * in RPG Maker; its dash and menu buttons mean nothing in a VN), so one
 * flat map cannot serve both.
 *
 * Resolution is engine override, then the engine family's own default
 * ([ControllerActions.engineDefaults]), then global, then the action's
 * default. That way remapping Confirm once applies everywhere except to a
 * family whose layout was settled deliberately, and only genuinely
 * engine-specific actions need per-engine attention.
 */
class ControllerBindingStore(context: Context, private val engine: String? = null) {
    private val preferences = context.getSharedPreferences("controller-bindings-v1", Context.MODE_PRIVATE)

    private fun scopedKey(action: ControllerAction): String? =
        engine?.lowercase()?.let { "engine.$it.${action.id}" }

    private fun read(key: String): ControllerBinding? = preferences.getString(key, null)
        ?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    fun get(action: ControllerAction): ControllerBinding =
        scopedKey(action)?.let(::read)
            ?: engine?.let { ControllerActions.engineDefaults[it.lowercase()]?.get(action.id) }
            ?: read(action.id)
            ?: action.default

    /** True when this engine overrides [action] rather than inheriting it. */
    fun isOverridden(action: ControllerAction): Boolean =
        scopedKey(action)?.let { preferences.contains(it) } == true

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
     * The resolved map for every action, as the runtime extra a plugin on
     * the android-activity transport reads: `{ "confirm": {"type":"key",
     * "code":96}, "left_x": {"type":"axis","axis":0,"direction":0}, ... }`.
     * Plugin-api plugins get the same map applied for them by
     * [RuntimeControllerRouter]; activity plugins own their input and must
     * apply it themselves, so the pad means what the person configured in
     * either case.
     */
    fun exportJson(): JSONObject = JSONObject().apply {
        ControllerActions.all.forEach { action -> put(action.id, encode(get(action))) }
    }

    /** Clears this scope only; the global map survives an engine reset. */
    fun reset() {
        if (engine == null) {
            preferences.edit().clear().apply()
            return
        }
        val prefix = "engine.${engine.lowercase()}."
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    private fun encode(binding: ControllerBinding) = when (binding) {
        is ControllerBinding.Key -> JSONObject().put("type", "key").put("code", binding.keyCode)
        is ControllerBinding.Axis -> JSONObject().put("type", "axis").put("axis", binding.axis)
            .put("direction", binding.direction)
    }

    private fun parse(json: JSONObject): ControllerBinding = when (json.getString("type")) {
        "key" -> ControllerBinding.Key(json.getInt("code"))
        "axis" -> ControllerBinding.Axis(json.getInt("axis"), json.optInt("direction"))
        else -> error("Unknown controller binding")
    }
}

/**
 * [engine] is the family of the bundle this session is running, so the
 * user's per-engine mappings actually apply while playing rather than
 * only existing in settings.
 */
class RuntimeControllerRouter(
    context: Context,
    engine: String? = null,
    private val plugin: () -> EnginePlugin?,
) {
    private val bindings = ControllerBindingStore(context, engine)

    fun key(event: KeyEvent): Boolean {
        if (!event.isControllerInput()) return false
        val value = if (event.action == KeyEvent.ACTION_UP) 0f else 1f
        return ControllerActions.all.asSequence()
            .filter { (bindings.get(it) as? ControllerBinding.Key)?.keyCode == event.keyCode }
            .map { dispatch(it.id, value, event.deviceId, event.eventTime) }
            .fold(false) { consumed, current -> consumed || current }
    }

    fun motion(event: MotionEvent): Boolean {
        if (!event.isControllerInput()) return false
        return ControllerActions.all.asSequence().mapNotNull { action ->
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
