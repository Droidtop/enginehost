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

/**
 * The PROFILE layer's one screen: what this physical pad sends, and what
 * Enginehost should take it to mean.
 *
 * Below the per-engine action map and independent of it. The action map
 * says what the A button does in this engine; this says which button is A.
 * A profile is per controller and shared by every engine and every game,
 * which is the one thing every EmulationStation-derived front end agrees
 * on (research/controller-profiles, recommendation 4).
 */
class ControllerProfileActivity : AppCompatActivity(), InputManager.InputDeviceListener {
    private lateinit var profileDevice: TextView
    private lateinit var profileState: TextView
    private lateinit var profileHint: TextView
    private lateinit var profileList: LinearLayout
    private lateinit var captureButton: Button
    private lateinit var clearButton: Button
    private lateinit var bypassProfileButton: Button
    private lateinit var store: ControllerProfileStore

    /** The control the wizard is waiting for, or null when it is not running. */
    private var step: Int? = null
    private val captured = mutableMapOf<StandardControl, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.controller_profile)
        store = ControllerProfileStore(this)
        setContentView(R.layout.activity_controller_profile)
        wireBackButton()
        profileDevice = findViewById(R.id.profileDevice)
        profileState = findViewById(R.id.profileState)
        profileHint = findViewById(R.id.profileHint)
        profileList = findViewById(R.id.profileList)
        captureButton = findViewById(R.id.captureButton)
        captureButton.setOnClickListener { if (step == null) startWizard() else cancelWizard() }
        clearButton = findViewById(R.id.clearButton)
        clearButton.setOnClickListener { if (step == null) clearProfile() else skipControl() }
        bypassProfileButton = findViewById(R.id.bypassProfileButton)
        bypassProfileButton.setOnClickListener {
            store.setAppliesInBypass(!store.appliesInBypass())
            render()
        }
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
        render()
    }

    override fun onDestroy() {
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    /**
     * The pad this screen is about. One profile belongs to one physical
     * device, so with several connected the first is the subject; nobody
     * here plays with two at once, and guessing between them would be
     * worse than being predictable.
     */
    private fun device(): InputDevice? = InputDevice.getDeviceIds().asSequence()
        .mapNotNull(InputDevice::getDevice)
        .filter { d ->
            (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }
        .distinctBy(InputDevice::getDescriptor)
        .firstOrNull()

    private fun render() {
        val device = device()
        profileDevice.text = device?.let {
            getString(R.string.profile_device, it.name, it.descriptor)
        } ?: getString(R.string.no_controller)

        val running = step != null
        val profile = device?.let(store::forDevice) ?: ControllerProfile.NONE
        profileState.text = when {
            device == null -> getString(R.string.profile_state_no_device)
            profile.source == ControllerProfile.Source.USER -> getString(R.string.profile_state_user)
            profile.source == ControllerProfile.Source.SEED -> getString(R.string.profile_state_seed)
            else -> getString(R.string.profile_state_none)
        }

        captureButton.isEnabled = device != null
        captureButton.text = getString(if (running) R.string.profile_cancel else R.string.profile_capture)
        clearButton.text = getString(if (running) R.string.profile_skip else R.string.profile_clear)
        clearButton.visibility =
            if (running || profile.source == ControllerProfile.Source.USER) View.VISIBLE else View.GONE
        bypassProfileButton.text = getString(
            if (store.appliesInBypass()) R.string.profile_in_bypass_on else R.string.profile_in_bypass_off,
        )
        bypassProfileButton.visibility = if (running) View.GONE else View.VISIBLE

        val waiting = step?.let { WIZARD.getOrNull(it) }
        profileHint.text = when {
            waiting != null -> getString(R.string.profile_press, waiting.title)
            device == null -> getString(R.string.profile_hint_no_device)
            else -> getString(R.string.profile_hint)
        }

        profileList.removeAllViews()
        rows(device, profile).forEach { row ->
            val view = layoutInflater.inflate(R.layout.item_hint, profileList, false) as TextView
            view.text = row
            profileList.addView(view)
        }
    }

    /**
     * What the profile says, control by control: every standard control
     * that this pad does not already report correctly, and what it sends
     * instead. A pad Android already gets right has nothing here, which is
     * the answer, not an empty screen.
     */
    private fun rows(device: InputDevice?, profile: ControllerProfile): List<String> {
        if (device == null) return emptyList()
        if (step != null) {
            return WIZARD.take(step ?: 0).map { control ->
                val physical = captured[control]
                getString(
                    R.string.binding_row,
                    control.title,
                    physical?.let { label(control, it) } ?: getString(R.string.profile_skipped),
                )
            }
        }
        if (profile.isEmpty()) return listOf(getString(R.string.profile_no_corrections))
        return profile.keys.map { (from, to) ->
            getString(
                R.string.profile_correction,
                KeyEvent.keyCodeToString(to).removePrefix("KEYCODE_"),
                KeyEvent.keyCodeToString(from).removePrefix("KEYCODE_"),
            )
        } + profile.axes.map { (from, to) ->
            getString(
                R.string.profile_correction,
                MotionEvent.axisToString(to).removePrefix("AXIS_"),
                MotionEvent.axisToString(from).removePrefix("AXIS_"),
            )
        }
    }

    private fun label(control: StandardControl, physical: Int): String =
        if (control.key != null) {
            KeyEvent.keyCodeToString(physical).removePrefix("KEYCODE_")
        } else {
            MotionEvent.axisToString(physical).removePrefix("AXIS_")
        }

    private fun startWizard() {
        captured.clear()
        step = 0
        render()
    }

    private fun cancelWizard() {
        step = null
        captured.clear()
        render()
    }

    private fun skipControl() {
        advance()
    }

    private fun clearProfile() {
        device()?.let(store::clear)
        render()
    }

    /**
     * Records [physical] for the control the wizard is waiting on. A
     * control the person gives a control that another one already has takes
     * it: the last press is the one they meant, and two standard controls
     * on one physical control is not a pad anyone has.
     */
    private fun record(physical: Int) {
        val control = step?.let { WIZARD.getOrNull(it) } ?: return
        captured.filterValues { it == physical }.keys.filter { it != control }.forEach(captured::remove)
        captured[control] = physical
        advance()
    }

    private fun advance() {
        val next = (step ?: return) + 1
        if (next < WIZARD.size) {
            step = next
            render()
            return
        }
        device()?.let { store.save(it, profileOf()) }
        step = null
        captured.clear()
        render()
    }

    /**
     * The captured presses as a profile: only the controls this pad sends
     * something other than the standard for. A pad that behaved throughout
     * saves an empty profile, which is a real answer -- it stops the seed
     * database being consulted for it ever again.
     */
    private fun profileOf(): ControllerProfile {
        val keys = mutableMapOf<Int, Int>()
        val axes = mutableMapOf<Int, Int>()
        captured.forEach { (control, physical) ->
            when {
                control.key != null && control.key != physical -> keys[physical] = control.key
                control.axis != null && control.axis != physical -> axes[physical] = control.axis
            }
        }
        return ControllerProfile(keys, axes, ControllerProfile.Source.USER)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val control = step?.let { WIZARD.getOrNull(it) }
        if (control?.key != null && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 && event.isControllerInput()
        ) {
            record(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val control = step?.let { WIZARD.getOrNull(it) }
        if (control?.axis != null && event.isControllerInput()) {
            val range = InputDevice.getDevice(event.deviceId)?.motionRanges.orEmpty()
                .filter { (it.source and InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK }
                .maxByOrNull { abs(event.getAxisValue(it.axis)) }
            if (range != null && abs(event.getAxisValue(range.axis)) >= CAPTURE_THRESHOLD) {
                record(range.axis)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onInputDeviceAdded(deviceId: Int) = render()
    override fun onInputDeviceRemoved(deviceId: Int) = render()
    override fun onInputDeviceChanged(deviceId: Int) = render()

    companion object {
        private const val CAPTURE_THRESHOLD = 0.65f

        /**
         * The order the wizard asks in: the buttons first in the order they
         * sit on the pad, then the sticks and the triggers. Guide is in it
         * because a pad may have one, and skippable because Android eats it
         * on most builds.
         */
        private val WIZARD: List<StandardControl> = listOf(
            StandardControl.A, StandardControl.B, StandardControl.X, StandardControl.Y,
            StandardControl.BACK, StandardControl.START, StandardControl.GUIDE,
            StandardControl.LEFT_SHOULDER, StandardControl.RIGHT_SHOULDER,
            StandardControl.LEFT_STICK, StandardControl.RIGHT_STICK,
            StandardControl.DPAD_UP, StandardControl.DPAD_DOWN,
            StandardControl.DPAD_LEFT, StandardControl.DPAD_RIGHT,
            StandardControl.LEFT_X, StandardControl.LEFT_Y,
            StandardControl.RIGHT_X, StandardControl.RIGHT_Y,
            StandardControl.LEFT_TRIGGER, StandardControl.RIGHT_TRIGGER,
        )
    }
}
