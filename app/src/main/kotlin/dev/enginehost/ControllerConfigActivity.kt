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

    /** null = the global map every engine inherits from. */
    private var scope: String? = null
    private val installedEngines: List<String> by lazy {
        runCatching {
            PluginRegistry.discover(this).map { it.info.engine }.distinct().sorted()
        }.getOrDefault(emptyList())
    }

    /**
     * Engines whose bundles all handle controllers themselves. Remapping
     * here does not reach them, and saying so is the difference between
     * a documented boundary and an apparent bug.
     */
    private val nativeInputEngines: Set<String> by lazy {
        runCatching {
            PluginRegistry.discover(this)
                .groupBy { it.info.engine }
                .filterValues { plugins ->
                    plugins.all { plugin ->
                        plugin.info.capabilities.all { it.controllerInput == ControllerInput.NATIVE }
                    }
                }
                .keys
        }.getOrDefault(emptySet())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Controller settings"
        store = ControllerBindingStore(this, scope)
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
        val controllers = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice)
            .filter { device ->
                (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            }
            .distinctBy(InputDevice::getDescriptor)
            .toList()
        content.addView(TextView(this).apply {
            text = if (controllers.isEmpty()) "No controller connected. You can still edit defaults." else
                "Connected: ${controllers.joinToString { it.name }}"
        })
        // Scope selector. Only families that are actually installed are
        // offered -- configuring an engine nobody has is configuration
        // that cannot apply to anything.
        content.addView(TextView(this).apply {
            text = "Applies to"
            setPadding(0, 12, 0, 4)
        })
        content.addView(Button(this).apply {
            text = "All engines" + if (scope == null) "  (editing)" else ""
            setOnClickListener { switchScope(null) }
        })
        installedEngines.forEach { engine ->
            content.addView(Button(this).apply {
                text = buildString {
                    append(engine)
                    if (engine in nativeInputEngines) append("  (handles its own controller)")
                    if (scope == engine) append("  (editing)")
                }
                setOnClickListener { switchScope(engine) }
            })
        }

        content.addView(TextView(this).apply {
            text = capturing?.let { "Press a button or move an axis for ${it.title}." }
                ?: if (scope == null) {
                    "The map every engine starts from. Each engine translates these " +
                        "actions into its own native input system."
                } else if (scope in nativeInputEngines) {
                    "$scope handles controllers itself, so these mappings don't reach " +
                        "it. Configure the pad inside the game or in that engine's own " +
                        "settings instead."
                } else {
                    "Overrides for $scope only. Anything left inherited follows the " +
                        "All engines map."
                }
            setPadding(0, 16, 0, 16)
        })

        ControllerActions.all.forEach { action ->
            val overridden = store.isOverridden(action)
            content.addView(Button(this).apply {
                // An inherited binding is marked, so it is obvious which
                // values belong to this engine and which are borrowed.
                text = buildString {
                    append(action.title).append(": ").append(store.get(action).label())
                    if (scope != null && !overridden) append("  (inherited)")
                }
                setOnClickListener { capturing = action; render() }
                setOnLongClickListener {
                    if (scope != null && overridden) {
                        store.clearOverride(action)
                        capturing = null
                        render()
                    }
                    true
                }
            })
        }
        content.addView(Button(this).apply {
            text = if (scope == null) "Reset all mappings" else "Reset $scope overrides"
            setOnClickListener { capturing = null; store.reset(); render() }
        })
    }

    private fun switchScope(engine: String?) {
        scope = engine
        store = ControllerBindingStore(this, engine)
        capturing = null
        render()
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
