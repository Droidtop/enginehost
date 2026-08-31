package dev.enginehost

import android.app.Activity
import android.content.Context
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** Global controller remapping shared by every engine bundle. */
class ControllerConfigActivity : Activity(), InputManager.InputDeviceListener {
    private lateinit var content: LinearLayout
    private lateinit var store: ControllerBindingStore
    private var capturing: ControllerAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Controller settings"
        store = ControllerBindingStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
        render()
    }

    override fun onDestroy() {
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        val controllers = InputDevice.getDeviceIds().mapNotNull(InputDevice::getDevice)
            .filter { device ->
                (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            }
            .distinctBy(InputDevice::getDescriptor)
        content.addView(TextView(this).apply {
            text = if (controllers.isEmpty()) "No controller connected. You can still edit defaults." else
                "Connected: ${controllers.joinToString { it.name }}"
        })
        content.addView(TextView(this).apply {
            text = capturing?.let { "Press a button or move an axis for ${it.title}." }
                ?: "Mappings are global; each engine translates these actions into its native input system."
            setPadding(0, 12, 0, 16)
        })
        ControllerActions.all.forEach { action ->
            content.addView(Button(this).apply {
                text = "${action.title}: ${store.get(action).label()}"
                setOnClickListener { capturing = action; render() }
            })
        }
        content.addView(Button(this).apply {
            text = "Reset all mappings"
            setOnClickListener { capturing = null; store.reset(); render() }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = capturing
        if (target != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.isControllerInput()) {
            store.set(target, ControllerBinding.Key(event.keyCode))
            capturing = null
            render()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val target = capturing
        if (target != null && event.isControllerInput()) {
            val range = InputDevice.getDevice(event.deviceId)?.motionRanges
                ?.filter { (it.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK }
                ?.maxByOrNull { abs(event.getAxisValue(it.axis)) }
            val value = range?.let { event.getAxisValue(it.axis) } ?: 0f
            if (range != null && abs(value) >= CAPTURE_THRESHOLD) {
                val defaultAxis = target.default as? ControllerBinding.Axis
                store.set(
                    target,
                    ControllerBinding.Axis(range.axis, if (defaultAxis?.direction == 0) 0 else if (value < 0) -1 else 1),
                )
                capturing = null
                render()
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onInputDeviceAdded(deviceId: Int) = render()
    override fun onInputDeviceRemoved(deviceId: Int) = render()
    override fun onInputDeviceChanged(deviceId: Int) = render()

    companion object { private const val CAPTURE_THRESHOLD = 0.65f }
}
