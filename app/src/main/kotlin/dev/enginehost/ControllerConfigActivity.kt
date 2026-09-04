package dev.enginehost

import android.content.Context
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
        connectedControllers = findViewById(R.id.connectedControllers)
        scopeList = findViewById(R.id.scopeList)
        scopeHint = findViewById(R.id.scopeHint)
        bindingList = findViewById(R.id.bindingList)
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

        scopeHint.text = capturing?.let { getString(R.string.capture_prompt, it.title) }
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
            button.text = buildString {
                append(getString(R.string.binding_row, ControllerActions.title(action, scope), store.get(action).label()))
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
