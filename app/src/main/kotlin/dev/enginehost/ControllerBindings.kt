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
    /**
     * What this binding is called on the controller screen: the name on the
     * pad ([PadNames]); [None]'s word is a string, which is why a context is needed.
     */
    fun label(context: Context): String

    data class Key(val keyCode: Int) : ControllerBinding {
        override fun label(context: Context): String = PadNames.key(keyCode)
    }

    data class Axis(val axis: Int, val direction: Int = 0) : ControllerBinding {
        override fun label(context: Context): String = PadNames.axis(axis, direction)
    }

    /**
     * Bound to nothing, and a binding in its own right rather than an
     * absence: any action may be None, and an engine feature driven by an
     * action (KiriKiri's pointer emulation on the stick axes) is simply
     * off while its actions are unbound. Encodes as `{"type":"none"}`.
     *
     * Nothing ships this way: every action in every set below carries a
     * real default. None is what a person chooses, not what we hand them.
     */
    object None : ControllerBinding {
        override fun label(context: Context): String = context.getString(R.string.binding_unbound)
    }
}

data class ControllerAction(val id: String, val title: String, val default: ControllerBinding)

/**
 * Which action set and which stored bindings a game belongs to.
 *
 * Usually the engine family, because a family is one engine. RPG Maker is
 * the exception: `rpgmaker` names three unrelated runtimes (mkxp-z's RGSS,
 * EasyRPG's RPG_RT and the MV/MZ web runtime), each with its own inputs and
 * its own names for them, so the family alone cannot say which actions to
 * offer. Those scopes carry the context: `rpgmaker/vxace`, `rpgmaker/2000`.
 */
object ControllerScope {
    /** Families where one engine id covers several engines. */
    private val contextual = setOf("rpgmaker")

    fun of(engine: String?, engineContext: String?): String? {
        val family = engine?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (family !in contextual) return family
        val context = engineContext?.lowercase()
            ?.takeIf { it.isNotBlank() && it != DEFAULT_ENGINE_CONTEXT }
            ?: return family
        return "$family/$context"
    }
}

/**
 * Controller bindings, resolved per engine over a global default.
 *
 * [engine] is a [ControllerScope]; null means the global scope -- the map
 * that applies wherever an engine has not overridden it. A VN and an RPG
 * Maker game want different things from the same pad, so one flat map
 * cannot serve both.
 *
 * Resolution is engine override, then global override, then the action's
 * own default. Two levels and one lookup: an engine's defaults live in its
 * action set ([ControllerActions.forEngine]) rather than in a third table
 * beside it. Remapping an action in the global scope therefore reaches
 * every engine that has that action, which is what a person means by "all
 * engines"; ids that exist only under one engine have no global row at all
 * and resolve straight to their default.
 */
class ControllerBindingStore internal constructor(
    private val file: BindingStorage,
    private val engine: String? = null,
    /**
     * A game's path, for the scope that is one game's own (Droidtop/tracker#239). Its
     * bindings sit above the engine's: game, then engine, then everywhere, then the
     * action's default. The engine scope still decides which actions exist.
     */
    private val game: String? = null,
) {
    constructor(context: Context, engine: String? = null, game: String? = null) :
        this(ControllerBindingFile.of(context), engine, game)

    private fun scopedKey(action: ControllerAction): String? =
        engine?.lowercase()?.let { "engine.$it.${action.id}" }

    private fun gameKey(action: ControllerAction): String? = game?.let { "game|$it|${action.id}" }

    /** Which level a binding comes from, outermost last. */
    enum class Source { THIS_GAME, ENGINE, EVERYWHERE, DEFAULT }

    /** Where [action]'s binding in force comes from, so a screen can say so. */
    fun sourceOf(action: ControllerAction): Source = when {
        gameKey(action)?.let(file::contains) == true -> Source.THIS_GAME
        scopedKey(action)?.let(file::contains) == true -> Source.ENGINE
        file.contains(action.id) -> Source.EVERYWHERE
        else -> Source.DEFAULT
    }

    /** Whether this game has any buttons of its own. */
    fun hasGameBindings(): Boolean = game != null && actions().any { gameKey(it)?.let(file::contains) == true }

    private fun bypassKey(): String? = engine?.lowercase()?.let { "bypass.$it" }

    private fun read(key: String): ControllerBinding? =
        file.binding(key)?.let { runCatching { parse(it) }.getOrNull() }

    /** The actions this scope offers, in the engine's own vocabulary. */
    fun actions(): List<ControllerAction> = ControllerActions.forEngine(engine)

    fun get(action: ControllerAction): ControllerBinding =
        gameKey(action)?.let(::read)
            ?: scopedKey(action)?.let(::read)
            ?: read(action.id)
            ?: action.default

    /** True when the scope being edited (this game, else this engine) overrides [action] rather than inheriting it. */
    fun isOverridden(action: ControllerAction): Boolean =
        (gameKey(action) ?: scopedKey(action))?.let(file::contains) == true

    /**
     * Whether the engine handles the controller itself for this scope. On
     * by default where it is offered at all, and the whole of the contract
     * with the plugin: bypassed means the runtime intent carries no
     * controller map, so the engine's own handling is the only handling.
     */
    fun isBypassed(): Boolean = ControllerActions.offersBypass(engine) &&
        bypassKey()?.let { file.flag(it, true) } == true

    fun setBypass(bypass: Boolean) {
        bypassKey()?.let { key -> file.edit { it[key] = bypass } }
    }

    fun set(action: ControllerAction, binding: ControllerBinding) {
        val key = gameKey(action) ?: scopedKey(action) ?: action.id
        file.edit { it[key] = encode(binding) }
    }

    /**
     * Drops this engine's override so the action inherits the global
     * binding again. A no-op in the global scope, where there is nothing
     * above to inherit from.
     */
    fun clearOverride(action: ControllerAction) {
        (gameKey(action) ?: scopedKey(action))?.let { key -> file.edit { it.remove(key) } }
    }

    /**
     * The resolved map for every action this engine offers, as the runtime
     * extra a plugin on the android-activity transport reads: `{ "confirm":
     * {"type":"key","code":96}, "left_x": {"type":"axis","axis":0,
     * "direction":0}, ... }`, with the engine's own action ids as the keys.
     * Plugin-api plugins get the same map applied for them by
     * [RuntimeControllerRouter]; activity plugins own their input and must
     * apply it themselves, so the pad means what the person configured in
     * either case.
     */
    fun exportJson(): JSONObject = JSONObject().apply {
        actions().forEach { action -> put(action.id, encode(get(action))) }
    }

    /** Clears this scope only; the global map survives an engine reset. */
    fun reset() {
        if (game != null) {
            val prefix = "game|$game|"
            file.edit { values -> values.keys.filter { it.startsWith(prefix) }.forEach { values.remove(it) } }
            return
        }
        if (engine == null) {
            file.edit { it.clear() }
            return
        }
        val prefixes = listOfNotNull("engine.${engine.lowercase()}.", bypassKey())
        file.edit { values ->
            values.keys.filter { key -> prefixes.any(key::startsWith) }.forEach { values.remove(it) }
        }
    }

    private fun encode(binding: ControllerBinding) = when (binding) {
        is ControllerBinding.Key -> JSONObject().put("type", "key").put("code", binding.keyCode)
        is ControllerBinding.Axis -> JSONObject().put("type", "axis").put("axis", binding.axis)
            .put("direction", binding.direction)
        is ControllerBinding.None -> JSONObject().put("type", "none")
    }

    private fun parse(json: JSONObject): ControllerBinding = when (json.getString("type")) {
        "key" -> ControllerBinding.Key(json.getInt("code"))
        "axis" -> ControllerBinding.Axis(json.getInt("axis"), json.optInt("direction"))
        "none" -> ControllerBinding.None
        else -> error("Unknown controller binding")
    }
}

/**
 * [engine] is the [ControllerScope] of the bundle this session is running,
 * so the user's per-engine mappings actually apply while playing rather
 * than only existing in settings. A bypassed scope routes nothing: the
 * events fall through to the engine's own handling, which is the same
 * thing the absent intent extra tells an activity plugin.
 *
 * Nothing here caches a binding or the bypass flag. [ControllerBindingFile]
 * is one file with one copy per process and drops that copy the moment the
 * file changes, so a change made from the in-game menu is in force on the
 * very next event rather than at the next launch.
 */
class RuntimeControllerRouter(
    context: Context,
    engine: String? = null,
    game: String? = null,
    private val plugin: () -> EnginePlugin?,
) {
    private val bindings = ControllerBindingStore(context, engine, game)

    /** Fixed for the session: the set is the scope's, and the scope is the game's. */
    private val actions = bindings.actions()

    fun key(event: KeyEvent): Boolean {
        if (bindings.isBypassed() || !event.isControllerInput()) return false
        val value = if (event.action == KeyEvent.ACTION_UP) 0f else 1f
        return actions.asSequence()
            .filter { (bindings.get(it) as? ControllerBinding.Key)?.keyCode == event.keyCode }
            .map { dispatch(it.id, value, event.deviceId, event.eventTime) }
            .fold(false) { consumed, current -> consumed || current }
    }

    fun motion(event: MotionEvent): Boolean {
        if (bindings.isBypassed() || !event.isControllerInput()) return false
        return actions.asSequence().mapNotNull { action ->
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
    // The event's own source counts too: `adb shell input gamepad ...` sends
    // gamepad events from the virtual keyboard device, and the in-game menu's
    // Select + Start could not be checked from adb without it (rig,
    // 2026-10-22). A keyboard's own keys still are not controller input.
    (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        InputDevice.getDevice(deviceId)?.sources?.let { sources ->
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        } == true

internal fun MotionEvent.isControllerInput(): Boolean =
    (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
