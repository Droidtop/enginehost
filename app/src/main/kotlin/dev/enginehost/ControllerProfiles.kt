package dev.enginehost

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject

/**
 * The standard controls a profile speaks in: the pad every engine and
 * every action set already assumes, one level below the per-engine action
 * map. RetroArch calls this RetroPad; here it is simply what Android
 * reports for a pad that behaves.
 *
 * The identity values are not a new table. They are the same key codes and
 * axes [ControllerActions] already uses for the identity mappings in the
 * Ren'Py and Godot sets, which is what makes "differs from what Android
 * already reports" a question with one answer.
 */
enum class StandardControl(
    val sdl: String,
    val title: String,
    val key: Int? = null,
    val axis: Int? = null,
) {
    A("a", "A", key = KeyEvent.KEYCODE_BUTTON_A),
    B("b", "B", key = KeyEvent.KEYCODE_BUTTON_B),
    X("x", "X", key = KeyEvent.KEYCODE_BUTTON_X),
    Y("y", "Y", key = KeyEvent.KEYCODE_BUTTON_Y),
    BACK("back", "Select / Back", key = KeyEvent.KEYCODE_BUTTON_SELECT),
    START("start", "Start", key = KeyEvent.KEYCODE_BUTTON_START),
    GUIDE("guide", "Guide", key = KeyEvent.KEYCODE_BUTTON_MODE),
    LEFT_SHOULDER("leftshoulder", "L1", key = KeyEvent.KEYCODE_BUTTON_L1),
    RIGHT_SHOULDER("rightshoulder", "R1", key = KeyEvent.KEYCODE_BUTTON_R1),
    LEFT_STICK("leftstick", "Left stick click", key = KeyEvent.KEYCODE_BUTTON_THUMBL),
    RIGHT_STICK("rightstick", "Right stick click", key = KeyEvent.KEYCODE_BUTTON_THUMBR),
    DPAD_UP("dpup", "D-pad up", key = KeyEvent.KEYCODE_DPAD_UP),
    DPAD_DOWN("dpdown", "D-pad down", key = KeyEvent.KEYCODE_DPAD_DOWN),
    DPAD_LEFT("dpleft", "D-pad left", key = KeyEvent.KEYCODE_DPAD_LEFT),
    DPAD_RIGHT("dpright", "D-pad right", key = KeyEvent.KEYCODE_DPAD_RIGHT),
    LEFT_X("leftx", "Left stick horizontal", axis = MotionEvent.AXIS_X),
    LEFT_Y("lefty", "Left stick vertical", axis = MotionEvent.AXIS_Y),
    RIGHT_X("rightx", "Right stick horizontal", axis = MotionEvent.AXIS_Z),
    RIGHT_Y("righty", "Right stick vertical", axis = MotionEvent.AXIS_RZ),
    LEFT_TRIGGER("lefttrigger", "Left trigger", axis = MotionEvent.AXIS_LTRIGGER),
    RIGHT_TRIGGER("righttrigger", "Right trigger", axis = MotionEvent.AXIS_RTRIGGER),
    ;

    companion object {
        fun ofSdl(name: String): StandardControl? = entries.firstOrNull { it.sdl == name }
    }
}

/**
 * One physical pad's corrections: which key code this pad sends where a
 * behaving pad would send another, and the same for axes.
 *
 * Empty is the normal case and the cheap one. Android resolves a pad's raw
 * HID codes through its own key-layout files long before an app sees them,
 * and for most pads that is already right; a profile then has nothing to
 * say and the event is passed on untouched.
 */
data class ControllerProfile(
    val keys: Map<Int, Int> = emptyMap(),
    val axes: Map<Int, Int> = emptyMap(),
    /** Where this came from, for the controller screen to say. */
    val source: Source = Source.NONE,
) {
    enum class Source { NONE, SEED, USER }

    fun isEmpty(): Boolean = keys.isEmpty() && axes.isEmpty()

    /** [event], with this pad's key code corrected; the same event when there is nothing to correct. */
    fun apply(event: KeyEvent): KeyEvent {
        val corrected = keys[event.keyCode] ?: return event
        return KeyEvent(
            event.downTime, event.eventTime, event.action, corrected, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        )
    }

    /**
     * [event], with this pad's axes corrected; the same event when there is
     * nothing to correct. A returned event that is not the one passed in is
     * a fresh one and the caller recycles it.
     *
     * An axis that is corrected *from* but never *to* is zeroed rather than
     * left as it was, or a swapped pair would report on both axes at once.
     * Only the current sample is carried: a joystick's history is a
     * smoothing detail no engine here reads, and rebuilding it would double
     * the work on every motion event for nothing.
     */
    fun apply(event: MotionEvent): MotionEvent {
        if (axes.isEmpty()) return event
        val count = event.pointerCount
        val properties = Array(count) { index ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
        }
        val vacated = axes.keys - axes.values.toSet()
        val coords = Array(count) { index ->
            val original = MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) }
            MotionEvent.PointerCoords(original).also { corrected ->
                vacated.forEach { corrected.setAxisValue(it, 0f) }
                axes.forEach { (from, to) -> corrected.setAxisValue(to, original.getAxisValue(from)) }
            }
        }
        return MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, count, properties, coords,
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags, event.source, event.flags,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("keys", JSONObject().also { keys.forEach { (from, to) -> it.put(from.toString(), to) } })
        put("axes", JSONObject().also { axes.forEach { (from, to) -> it.put(from.toString(), to) } })
    }

    companion object {
        val NONE = ControllerProfile()

        fun fromJson(json: JSONObject, source: Source): ControllerProfile = ControllerProfile(
            keys = readMap(json.optJSONObject("keys")),
            axes = readMap(json.optJSONObject("axes")),
            source = source,
        )

        private fun readMap(json: JSONObject?): Map<Int, Int> {
            if (json == null) return emptyMap()
            return json.keys().asSequence().mapNotNull { key ->
                key.toIntOrNull()?.let { it to json.getInt(key) }
            }.toMap()
        }
    }
}

/**
 * SDL's own Android numbering, which is what the seed database's `bN` and
 * `aN` targets are indices into.
 *
 * Buttons: `keycode_to_SDL` in SDL2's
 * `src/joystick/android/SDL_sysjoystick.c`, inverted. Three Android key
 * codes are aliases there (MENU for START, BACK for BACK, DPAD_CENTER for
 * A); the inverse keeps the `BUTTON_*`/`DPAD_*` code in each case, because
 * that is the one a pad actually sends.
 *
 * Axes: SDL does not number axes itself. It hands SDL the device's motion
 * ranges in the order `SDLControllerManager.java` sorts them -- the
 * `RangeComparator`, which swaps GAS with BRAKE and slides Z in between RY
 * and RZ -- keeping only ranges in the joystick source class and taking the
 * hat pair out into hats. Index N is therefore the Nth surviving range for
 * *this* device, which is why an axis target can only be resolved against
 * a pad that is actually connected.
 */
object SdlAndroidNumbering {
    private val buttons: Map<Int, Int> = buildMap {
        put(0, KeyEvent.KEYCODE_BUTTON_A)
        put(1, KeyEvent.KEYCODE_BUTTON_B)
        put(2, KeyEvent.KEYCODE_BUTTON_X)
        put(3, KeyEvent.KEYCODE_BUTTON_Y)
        put(4, KeyEvent.KEYCODE_BUTTON_SELECT)
        put(5, KeyEvent.KEYCODE_BUTTON_MODE)
        put(6, KeyEvent.KEYCODE_BUTTON_START)
        put(7, KeyEvent.KEYCODE_BUTTON_THUMBL)
        put(8, KeyEvent.KEYCODE_BUTTON_THUMBR)
        put(9, KeyEvent.KEYCODE_BUTTON_L1)
        put(10, KeyEvent.KEYCODE_BUTTON_R1)
        put(11, KeyEvent.KEYCODE_DPAD_UP)
        put(12, KeyEvent.KEYCODE_DPAD_DOWN)
        put(13, KeyEvent.KEYCODE_DPAD_LEFT)
        put(14, KeyEvent.KEYCODE_DPAD_RIGHT)
        put(15, KeyEvent.KEYCODE_BUTTON_L2)
        put(16, KeyEvent.KEYCODE_BUTTON_R2)
        put(17, KeyEvent.KEYCODE_BUTTON_C)
        put(18, KeyEvent.KEYCODE_BUTTON_Z)
        for (n in 0..15) put(20 + n, KeyEvent.KEYCODE_BUTTON_1 + n)
    }

    /** The Android key code SDL's button index [index] comes from, or null. */
    fun keyCodeOf(index: Int): Int? = buttons[index]

    /** The Android axis SDL's axis index [index] is, for this device. */
    fun axesOf(device: InputDevice): List<Int> = device.motionRanges
        .filter { (it.source and InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK }
        .filterNot { it.axis == MotionEvent.AXIS_HAT_X || it.axis == MotionEvent.AXIS_HAT_Y }
        .sortedBy { sortKey(it.axis) }
        .map { it.axis }

    /** `SDLControllerManager.RangeComparator`, exactly. */
    private fun sortKey(axis: Int): Int {
        val swapped = when (axis) {
            MotionEvent.AXIS_GAS -> MotionEvent.AXIS_BRAKE
            MotionEvent.AXIS_BRAKE -> MotionEvent.AXIS_GAS
            else -> axis
        }
        return when {
            swapped == MotionEvent.AXIS_Z -> MotionEvent.AXIS_RZ - 1
            swapped > MotionEvent.AXIS_Z && swapped < MotionEvent.AXIS_RZ -> swapped - 1
            else -> swapped
        }
    }
}

/** One line of `gamecontrollerdb-android.txt`. */
data class SdlMapping(val guid: String, val name: String, val targets: Map<String, String>) {
    companion object {
        fun parse(line: String): SdlMapping? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            val fields = trimmed.split(",").filter { it.isNotBlank() }
            if (fields.size < 3) return null
            val targets = fields.drop(2).mapNotNull { field ->
                val separator = field.indexOf(':')
                if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
            }.toMap()
            return SdlMapping(fields[0], fields[1], targets)
        }
    }
}

/**
 * Where a pad's profile comes from, and what it is.
 *
 * Two sources, in one order. A profile the person captured on this device
 * wins, keyed by [InputDevice.getDescriptor] with the pad's name recorded
 * beside it; failing that, the seed database, matched by name. Nothing
 * else: an unmatched pad gets no profile, which is the same as saying
 * Android's own handling was right, which for most pads it is.
 *
 * Read `third_party/sdl_gamecontrollerdb/README.md` for what of an entry is
 * used and why the rest is not.
 */
class ControllerProfileStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The profile in force for [device]. */
    fun forDevice(device: InputDevice): ControllerProfile =
        user(device) ?: ControllerProfileSeed.forDevice(context, device) ?: ControllerProfile.NONE

    /** The profile the person captured for [device], if they captured one. */
    fun user(device: InputDevice): ControllerProfile? =
        preferences.getString(key(device), null)
            ?.let { runCatching { ControllerProfile.fromJson(JSONObject(it), ControllerProfile.Source.USER) }.getOrNull() }

    fun save(device: InputDevice, profile: ControllerProfile) {
        preferences.edit()
            .putString(key(device), profile.toJson().toString())
            .putString(nameKey(device), device.name)
            .apply()
    }

    fun clear(device: InputDevice) {
        preferences.edit().remove(key(device)).remove(nameKey(device)).apply()
    }

    /**
     * Whether a profile is applied while an engine is bypassing the action
     * map. Off by default, and the default is the point: bypass means the
     * engine reads the raw pad, and a profile is not raw. A person whose
     * pad genuinely reports the wrong buttons wants it on, and says so
     * once, globally.
     */
    fun appliesInBypass(): Boolean = preferences.getBoolean(KEY_IN_BYPASS, false)

    fun setAppliesInBypass(applies: Boolean) {
        preferences.edit().putBoolean(KEY_IN_BYPASS, applies).apply()
    }

    private fun key(device: InputDevice) = "profile.${device.descriptor}"
    private fun nameKey(device: InputDevice) = "name.${device.descriptor}"

    companion object {
        private const val PREFERENCES = "controller-profiles-v1"
        private const val KEY_IN_BYPASS = "apply-in-bypass"
    }
}

/** The seed half of [ControllerProfileStore]: SDL_GameControllerDB, translated. */
object ControllerProfileSeed {
    private const val ASSET = "gamecontrollerdb-android.txt"

    @Volatile private var entries: List<SdlMapping>? = null

    private fun entries(context: Context): List<SdlMapping> = entries ?: synchronized(this) {
        entries ?: runCatching {
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.mapNotNull(SdlMapping::parse).toList()
            }
        }.getOrDefault(emptyList()).also { entries = it }
    }

    /**
     * The seed profile for [device], or null when the database has nothing
     * to say about it, says two contradictory things about it, or says only
     * what Android already reports.
     *
     * Ambiguity is resolved the one way it honestly can be: an entry whose
     * GUID carries this pad's vendor and product wins over one that does
     * not. Failing that, several entries under one name disagreeing is a
     * database that does not know which pad this is, and applying either
     * would be a coin toss the person has to undo.
     */
    fun forDevice(context: Context, device: InputDevice): ControllerProfile? {
        val named = entries(context).filter { it.name.equals(device.name.trim(), ignoreCase = true) }
        if (named.isEmpty()) return null
        val entry = named.singleOrNull()
            ?: named.singleOrNull { identifies(it.guid, device) }
            ?: return null
        return translate(entry, device)
    }

    /**
     * Whether [guid] is the vendor-and-product form and names this pad.
     * SDL lays a 16-byte GUID out as little-endian 16-bit fields: bus, then
     * a CRC of the name, then vendor, a gap, product (`SDL_CreateJoystickGUID`
     * in `src/joystick/SDL_joystick.c`). Only that form can identify
     * hardware; the older Android form holds the device name instead and is
     * no better a key than the name field already is.
     */
    private fun identifies(guid: String, device: InputDevice): Boolean {
        if (device.vendorId == 0 || device.productId == 0) return false
        return field(guid, 2) == device.vendorId && field(guid, 4) == device.productId
    }

    /** The [index]th little-endian 16-bit field of a 32 hex character GUID. */
    private fun field(guid: String, index: Int): Int? {
        if (guid.length != 32) return null
        val at = index * 4
        val low = guid.substring(at, at + 2).toIntOrNull(16) ?: return null
        val high = guid.substring(at + 2, at + 4).toIntOrNull(16) ?: return null
        return low or (high shl 8)
    }

    /**
     * An entry, as the corrections it implies for this pad. Only targets
     * that can contradict Android are read, and only when they do; see the
     * third_party README for the list and the reasoning.
     */
    fun translate(entry: SdlMapping, device: InputDevice): ControllerProfile? {
        val axes = SdlAndroidNumbering.axesOf(device)
        val keyCorrections = mutableMapOf<Int, Int>()
        val axisCorrections = mutableMapOf<Int, Int>()
        for ((name, target) in entry.targets) {
            val standard = StandardControl.ofSdl(name) ?: continue
            when {
                standard.key != null && target.startsWith("b") -> {
                    val index = target.drop(1).toIntOrNull() ?: continue
                    val physical = SdlAndroidNumbering.keyCodeOf(index) ?: continue
                    if (physical != standard.key) keyCorrections[physical] = standard.key
                }
                standard.axis != null && (target.startsWith("a") || target.startsWith("+a") || target.startsWith("-a")) -> {
                    val index = target.removePrefix("+").removePrefix("-").drop(1).removeSuffix("~").toIntOrNull() ?: continue
                    val physical = axes.getOrNull(index) ?: continue
                    if (physical != standard.axis) axisCorrections[physical] = standard.axis
                }
            }
        }
        // Two physical controls claiming one standard button is a mapping
        // this pad cannot have; the entry is for some other pad wearing the
        // same name, and half-applying it is worse than not applying it.
        if (keyCorrections.values.toSet().size != keyCorrections.size) return null
        if (axisCorrections.values.toSet().size != axisCorrections.size) return null
        val profile = ControllerProfile(keyCorrections, axisCorrections, ControllerProfile.Source.SEED)
        return profile.takeUnless { it.isEmpty() }
    }
}
