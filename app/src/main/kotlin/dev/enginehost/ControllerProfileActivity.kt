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
class ControllerProfileActivity : EnginehostActivity(), InputManager.InputDeviceListener {
    /** Capture: reading the pad's buttons is what this screen is for. */
    override fun primaryAction(): View? =
        findViewById(R.id.captureButton)

    private lateinit var profileDevice: TextView
    private lateinit var profileState: TextView
    private lateinit var profileHint: TextView
    private lateinit var profileList: LinearLayout
    private lateinit var captureButton: Button
    private lateinit var clearButton: Button
    private lateinit var bypassProfileButton: Button
    private lateinit var labelsButton: Button
    private lateinit var store: ControllerProfileStore

    /** The control the wizard is waiting for, or null when it is not running. */
    private var step: Int? = null
    private val captured = mutableMapOf<StandardControl, Int>()

    /**
     * What the wizard asks for, settled when it starts: [WIZARD] for a pad
     * nobody has written down, and only the controls it has for a pad
     * somebody has. Asking a person to press a button their pad does not
     * have is a question with no answer.
     */
    private var wizard: List<StandardControl> = WIZARD

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
        labelsButton = findViewById(R.id.labelsButton)
        labelsButton.setOnClickListener { chooseLabels() }
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
        val known = device?.let(KnownControllers::forDevice)
        profileDevice.text = when {
            device == null -> getString(R.string.no_controller)
            known != null -> getString(R.string.profile_device_known, known.model, device.name, device.descriptor)
            else -> getString(R.string.profile_device, device.name, device.descriptor)
        }

        val running = step != null
        val profile = device?.let(store::forDevice) ?: ControllerProfile.NONE
        val labels = store.labels(device)
        profileState.text = when {
            device == null -> getString(R.string.profile_state_no_device)
            profile.source == ControllerProfile.Source.USER -> getString(R.string.profile_state_user)
            profile.source == ControllerProfile.Source.SEED -> getString(R.string.profile_state_seed)
            known != null -> getString(R.string.profile_state_known, known.model)
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

        val waiting = step?.let { wizard.getOrNull(it) }
        profileHint.text = when {
            waiting != null -> PadGlyphs.fill(this, R.string.profile_press, controlGlyph(waiting, labels.family, profileHint.textSize))
            device == null -> getString(R.string.profile_hint_no_device)
            else -> getString(R.string.profile_hint)
        }

        labelsButton.visibility = if (device == null || running) View.GONE else View.VISIBLE
        labelsButton.text = getString(
            R.string.profile_labels,
            when (labels.source) {
                PadLabels.Source.CHOSEN -> familyName(labels.family)
                PadLabels.Source.DETECTED -> getString(R.string.profile_labels_detected, familyName(labels.family))
                PadLabels.Source.ASSUMED -> getString(R.string.profile_labels_assumed, familyName(labels.family))
            },
        )

        profileList.removeAllViews()
        rows(device, profile, labels.family).forEach { row ->
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
    private fun rows(device: InputDevice?, profile: ControllerProfile, family: PadFamily): List<CharSequence> {
        val size = profileHint.textSize
        if (device == null) return emptyList()
        if (step != null) {
            return wizard.take(step ?: 0).map { control ->
                val physical = captured[control]
                PadGlyphs.fill(
                    this,
                    R.string.binding_row,
                    controlGlyph(control, family, size),
                    physical?.let { label(control, it, family, size) } ?: getString(R.string.profile_skipped),
                )
            }
        }
        if (profile.isEmpty()) return listOf(getString(R.string.profile_no_corrections))
        return profile.keys.map { (from, to) ->
            PadGlyphs.fill(this, R.string.profile_correction, PadGlyphs.key(this, to, family, size), PadGlyphs.key(this, from, family, size))
        } + profile.axes.map { (from, to) ->
            PadGlyphs.fill(this, R.string.profile_correction, PadGlyphs.axis(this, to, family, size), PadGlyphs.axis(this, from, family, size))
        }
    }

    private fun label(control: StandardControl, physical: Int, family: PadFamily, size: Float): CharSequence =
        if (control.key != null) PadGlyphs.key(this, physical, family, size) else PadGlyphs.axis(this, physical, family, size)

    /** A standard control as the pad in hand prints it: StandardControl's A is the bottom face button. */
    private fun controlGlyph(control: StandardControl, family: PadFamily, size: Float): CharSequence = when {
        control.key != null -> PadGlyphs.key(this, control.key, family, size)
        control.axis != null -> PadGlyphs.axis(this, control.axis, family, size)
        else -> control.title
    }

    private fun familyName(family: PadFamily): String = getString(
        when (family) {
            PadFamily.XBOX -> R.string.pad_family_xbox
            PadFamily.PLAYSTATION -> R.string.pad_family_playstation
            PadFamily.NINTENDO -> R.string.pad_family_nintendo
        },
    )

    /** What this pad's buttons print: as the pad says, or the person's choice of the three families. */
    private fun chooseLabels() {
        val device = device() ?: return
        val chosen = store.chosenFamily(device)
        val sheet = Sheet(this).title(R.string.profile_labels_title)
        val detected = PadFamilies.classify(device)
        sheet.choice(
            detected?.let { getString(R.string.profile_labels_as_detected, familyName(it)) } ?: getString(R.string.profile_labels_as_detected_unknown),
            current = chosen == null,
        ) { store.chooseFamily(device, null); render() }
        PadFamily.entries.forEach { family ->
            sheet.choice(familyName(family), current = chosen == family) { store.chooseFamily(device, family); render() }
        }
        sheet.show()
    }

    private fun startWizard() {
        captured.clear()
        wizard = device()?.let(KnownControllers::forDevice)
            ?.let { known -> WIZARD.filter { it in known.controls } }
            ?: WIZARD
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
        val control = step?.let { wizard.getOrNull(it) } ?: return
        captured.filterValues { it == physical }.keys.filter { it != control }.forEach(captured::remove)
        captured[control] = physical
        advance()
    }

    private fun advance() {
        val next = (step ?: return) + 1
        if (next < wizard.size) {
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
        val control = step?.let { wizard.getOrNull(it) }
        if (control?.key != null && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 && event.isControllerInput()
        ) {
            record(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val control = step?.let { wizard.getOrNull(it) }
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
