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
}

class ControllerBindingStore(context: Context) {
    private val preferences = context.getSharedPreferences("controller-bindings-v1", Context.MODE_PRIVATE)

    fun get(action: ControllerAction): ControllerBinding = preferences.getString(action.id, null)
        ?.let { runCatching { parse(JSONObject(it)) }.getOrNull() } ?: action.default

    fun set(action: ControllerAction, binding: ControllerBinding) {
        preferences.edit().putString(action.id, encode(binding).toString()).apply()
    }

    fun reset() = preferences.edit().clear().apply()

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

class RuntimeControllerRouter(context: Context, private val plugin: () -> EnginePlugin?) {
    private val bindings = ControllerBindingStore(context)

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
