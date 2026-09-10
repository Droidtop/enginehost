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
    private lateinit var bypassButton: Button
    private lateinit var unbindButton: Button
    private lateinit var resetButton: Button
    private lateinit var store: ControllerBindingStore
    private var capturing: ControllerAction? = null

    /** null = the global map every engine inherits from. */
    private var scope: String? = null

    /**
     * The scopes worth offering: one per compatibility line an installed
     * bundle serves, because RPG Maker's three runtimes are three engines
     * with three different action sets. Configuring an engine nobody has
     * is configuration that cannot apply to anything.
     */
    private val installedScopes: List<Pair<String, String>> by lazy {
        runCatching {
            PluginRegistry.discover(this).flatMap { manifest ->
                manifest.info.capabilities.mapNotNull { capability ->
                    val engine = manifest.info.engineOf(capability)
                    ControllerScope.of(engine, capability.engineContext)
                        ?.let { it to EngineNames.line(engine, capability.engineContext) }
                }
            }.distinct().sortedBy { it.second }
        }.getOrDefault(emptyList())
    }

    /**
     * Scopes whose bundles all handle controllers themselves and offer no
     * bypass toggle of their own. Remapping here does not reach them, and
     * saying so is the difference between a documented boundary and an
     * apparent bug.
     */
    private val nativeInputScopes: Set<String> by lazy {
        runCatching {
            PluginRegistry.discover(this).flatMap { manifest ->
                manifest.info.capabilities.mapNotNull { capability ->
                    val engine = manifest.info.engineOf(capability)
                    ControllerScope.of(engine, capability.engineContext)
                        ?.let { it to (capability.controllerInput == ControllerInput.NATIVE) }
                }
            }
                .groupBy({ it.first }, { it.second })
                .filterValues { native -> native.all { it } }
                .keys
                .filterNot(ControllerActions::offersBypass)
                .toSet()
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
        bypassButton = findViewById(R.id.bypassButton)
        bypassButton.setOnClickListener {
            store.setBypass(!store.isBypassed())
            capturing = null
            render()
        }
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

        scopeList.removeAllViews()
        addScopeButton(null, getString(R.string.all_engines))
        installedScopes.forEach { (id, label) -> addScopeButton(id, label) }

        // A bypassed engine reads the pad itself: the host sends it no map
        // at all, so the rows below are shown as what they would be rather
        // than as what is in force, and are not editable until the toggle
        // is off. That is the whole contract in one control.
        val offersBypass = ControllerActions.offersBypass(scope)
        val bypassed = offersBypass && store.isBypassed()
        bypassButton.visibility = if (offersBypass) View.VISIBLE else View.GONE
        bypassButton.text =
            getString(if (bypassed) R.string.bypass_on else R.string.bypass_off)

        // While a capture is open the hint asks for the input, and the
        // button beside it is how "nothing" is said: the same visible
        // gesture for every action rather than a hidden one. Long press
        // still drops a scoped override, which is a different thing --
        // inherit again, not unbind.
        val target = capturing
        unbindButton.visibility = if (target == null) View.GONE else View.VISIBLE
        target?.let { unbindButton.text = getString(R.string.unbind_action, it.title) }
        scopeHint.text = target?.let { getString(R.string.capture_prompt, it.title) }
            ?: when {
                scope == null -> getString(R.string.global_map_hint)
                bypassed -> getString(R.string.bypass_hint, scope)
                scope in nativeInputScopes -> getString(R.string.native_engine_hint, scope)
                else -> getString(R.string.scoped_hint, scope)
            }

        bindingList.removeAllViews()
        store.actions().forEach { action ->
            val overridden = store.isOverridden(action)
            val button = layoutInflater.inflate(R.layout.item_action_button, bindingList, false) as Button
            // An inherited binding is marked, so it is obvious which
            // values belong to this engine and which are borrowed.
            val label = store.get(action).label(this)
            button.text = buildString {
                append(getString(R.string.binding_row, action.title, label))
                if (scope != null && !overridden) append("  ").append(getString(R.string.marker_inherited))
            }
            button.isEnabled = !bypassed
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
            if (engine != null && engine in nativeInputScopes) {
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
