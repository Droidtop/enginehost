package dev.enginehost

import android.content.Context
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/** Global controller remapping shared by every engine bundle. */
class ControllerConfigActivity : AppCompatActivity(), InputManager.InputDeviceListener {
    private lateinit var connectedControllers: TextView
    private lateinit var scopeList: LinearLayout
    private lateinit var scopeHint: TextView
    private lateinit var bindingList: LinearLayout
    private lateinit var unbindButton: Button
    private lateinit var resetButton: Button
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
        title = getString(R.string.controller_settings)
        store = ControllerBindingStore(this, scope)
        setContentView(R.layout.activity_controller_config)
        wireBackButton()
        connectedControllers = findViewById(R.id.connectedControllers)
        scopeList = findViewById(R.id.scopeList)
        scopeHint = findViewById(R.id.scopeHint)
        bindingList = findViewById(R.id.bindingList)
        unbindButton = findViewById(R.id.unbindButton)
        unbindButton.setOnClickListener {
            capturing?.let { store.set(it, ControllerBinding.None) }
            capturing = null
            render()
        }
        resetButton = findViewById(R.id.resetButton)
        resetButton.setOnClickListener { capturing = null; store.reset(); render() }
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
        render()
    }

    override fun onDestroy() {
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    private fun render() {
        val controllers = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice)
            .filter { device ->
                (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            }
            .distinctBy(InputDevice::getDescriptor)
            .toList()
        connectedControllers.text = if (controllers.isEmpty()) {
            getString(R.string.no_controller)
        } else {
            getString(R.string.connected_controllers, controllers.joinToString { it.name })
        }

        // Scope selector. Only families that are actually installed are
        // offered; configuring an engine nobody has is configuration
        // that cannot apply to anything.
        scopeList.removeAllViews()
        addScopeButton(null, getString(R.string.all_engines))
        installedEngines.forEach { engine -> addScopeButton(engine, engine) }

        // While a capture is open the hint asks for the input, and the
        // button beside it is how "nothing" is said: the same visible
        // gesture for every action rather than a hidden one. Long press
        // still drops a scoped override, which is a different thing --
        // inherit again, not unbind.
        val target = capturing
        unbindButton.visibility = if (target == null) View.GONE else View.VISIBLE
        target?.let { unbindButton.text = getString(R.string.unbind_action, ControllerActions.title(it, scope)) }
        scopeHint.text = target?.let { getString(R.string.capture_prompt, ControllerActions.title(it, scope)) }
            ?: when {
                scope == null -> getString(R.string.global_map_hint)
                scope in nativeInputEngines -> getString(R.string.native_engine_hint, scope)
                else -> getString(R.string.scoped_hint, scope)
            }

        bindingList.removeAllViews()
        ControllerActions.all.forEach { action ->
            val overridden = store.isOverridden(action)
            val button = layoutInflater.inflate(R.layout.item_action_button, bindingList, false) as Button
            // An inherited binding is marked, so it is obvious which
            // values belong to this engine and which are borrowed.
            val label = store.get(action).label(this)
            button.text = buildString {
                append(getString(R.string.binding_row, ControllerActions.title(action, scope), label))
                if (scope != null && !overridden) append("  ").append(getString(R.string.marker_inherited))
            }
            button.setOnClickListener { capturing = action; render() }
            button.setOnLongClickListener {
                if (scope != null && overridden) {
                    store.clearOverride(action)
                    capturing = null
                    render()
                }
                true
            }
            bindingList.addView(button)
        }
        resetButton.text = scope.let { engine ->
            if (engine == null) getString(R.string.reset_all) else getString(R.string.reset_scope, engine)
        }
    }

    private fun addScopeButton(engine: String?, label: String) {
        val button = layoutInflater.inflate(R.layout.item_action_button, scopeList, false) as Button
        button.text = buildString {
            append(label)
            if (engine != null && engine in nativeInputEngines) {
                append("  ").append(getString(R.string.marker_native))
            }
            if (scope == engine) append("  ").append(getString(R.string.marker_editing))
        }
        button.setOnClickListener { switchScope(engine) }
        scopeList.addView(button)
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
            val range = capturableRanges(event.deviceId).maxByOrNull { abs(event.getAxisValue(it.axis)) }
            val value = range?.let { event.getAxisValue(it.axis) } ?: 0f
            if (range != null && abs(value) >= CAPTURE_THRESHOLD) {
                store.set(target, ControllerBinding.Axis(range.axis, capturedDirection(target, value)))
                capturing = null
                render()
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * The axes a capture may land on. The joystick class covers the sticks
     * and the triggers; the hat pair is named as well because a pad that
     * reports its d-pad as AXIS_HAT_X/AXIS_HAT_Y sometimes files those
     * ranges under SOURCE_DPAD, and dropping them would leave the d-pad as
     * the one input that cannot be captured.
     */
    private fun capturableRanges(deviceId: Int): List<InputDevice.MotionRange> =
        InputDevice.getDevice(deviceId)?.motionRanges.orEmpty().filter { range ->
            (range.source and InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK ||
                range.axis == MotionEvent.AXIS_HAT_X ||
                range.axis == MotionEvent.AXIS_HAT_Y
        }

    /**
     * An analogue action -- one whose default is an axis read whole,
     * direction 0 -- stays analogue whatever axis it is moved to.
     * Everything else is digital, and an axis standing in for a button has
     * to record which way the stick or hat was pushed, or the plugin
     * cannot tell left from right on one axis.
     */
    private fun capturedDirection(action: ControllerAction, value: Float): Int {
        val analogue = (action.default as? ControllerBinding.Axis)?.direction == 0
        return if (analogue) 0 else if (value < 0) -1 else 1
    }

    override fun onInputDeviceAdded(deviceId: Int) = render()
    override fun onInputDeviceRemoved(deviceId: Int) = render()
    override fun onInputDeviceChanged(deviceId: Int) = render()

    companion object { private const val CAPTURE_THRESHOLD = 0.65f }
}
