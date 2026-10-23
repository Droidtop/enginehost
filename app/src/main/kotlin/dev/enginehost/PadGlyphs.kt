package dev.enginehost

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * A control as the pad shows it, whatever the family prints on it (docs/SPEC.md, "Controls are
 * drawn as glyphs"). Android's key codes are positional (A is the bottom face button, B the right
 * one, X the left, Y the top), after the profile has corrected a pad that reports otherwise.
 */
sealed interface PadGlyph {
    enum class Position { BOTTOM, RIGHT, LEFT, TOP }
    enum class Arrow { UP, DOWN, LEFT, RIGHT, HORIZONTAL, VERTICAL, ANY }
    enum class Centre { START, SELECT, HOME }

    data class Face(val position: Position) : PadGlyph
    data class Shoulder(val left: Boolean, val trigger: Boolean) : PadGlyph
    data class StickClick(val left: Boolean) : PadGlyph
    data class Stick(val left: Boolean, val arrow: Arrow) : PadGlyph
    data class Dpad(val arrow: Arrow) : PadGlyph
    data class Middle(val which: Centre) : PadGlyph

    /** A key no pad prints: a keyboard key, drawn as its name in a key cap. */
    data class Key(val text: String) : PadGlyph

    companion object {
        fun ofKey(keyCode: Int): PadGlyph = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> Face(Position.BOTTOM)
            KeyEvent.KEYCODE_BUTTON_B -> Face(Position.RIGHT)
            KeyEvent.KEYCODE_BUTTON_X -> Face(Position.LEFT)
            KeyEvent.KEYCODE_BUTTON_Y -> Face(Position.TOP)
            KeyEvent.KEYCODE_BUTTON_L1 -> Shoulder(left = true, trigger = false)
            KeyEvent.KEYCODE_BUTTON_R1 -> Shoulder(left = false, trigger = false)
            KeyEvent.KEYCODE_BUTTON_L2 -> Shoulder(left = true, trigger = true)
            KeyEvent.KEYCODE_BUTTON_R2 -> Shoulder(left = false, trigger = true)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> StickClick(left = true)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> StickClick(left = false)
            KeyEvent.KEYCODE_DPAD_UP -> Dpad(Arrow.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> Dpad(Arrow.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> Dpad(Arrow.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dpad(Arrow.RIGHT)
            KeyEvent.KEYCODE_DPAD_CENTER -> Dpad(Arrow.ANY)
            KeyEvent.KEYCODE_BUTTON_START -> Middle(Centre.START)
            KeyEvent.KEYCODE_BUTTON_SELECT -> Middle(Centre.SELECT)
            KeyEvent.KEYCODE_BUTTON_MODE -> Middle(Centre.HOME)
            else -> Key(PadNames.key(keyCode))
        }

        /** [direction] -1 or 1 for one half of an axis, 0 for the whole. */
        fun ofAxis(axis: Int, direction: Int = 0): PadGlyph {
            fun arrow(horizontal: Boolean) = when (direction) {
                -1 -> if (horizontal) Arrow.LEFT else Arrow.UP
                1 -> if (horizontal) Arrow.RIGHT else Arrow.DOWN
                else -> if (horizontal) Arrow.HORIZONTAL else Arrow.VERTICAL
            }
            return when (axis) {
                MotionEvent.AXIS_X -> Stick(left = true, arrow(true))
                MotionEvent.AXIS_Y -> Stick(left = true, arrow(false))
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RX -> Stick(left = false, arrow(true))
                MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY -> Stick(left = false, arrow(false))
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE -> Shoulder(left = true, trigger = true)
                MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS -> Shoulder(left = false, trigger = true)
                MotionEvent.AXIS_HAT_X -> Dpad(arrow(true))
                MotionEvent.AXIS_HAT_Y -> Dpad(arrow(false))
                else -> Key(PadNames.axis(axis, direction))
            }
        }

        fun of(binding: ControllerBinding): PadGlyph? = when (binding) {
            is ControllerBinding.Key -> ofKey(binding.keyCode)
            is ControllerBinding.Axis -> ofAxis(binding.axis, binding.direction)
            ControllerBinding.None -> null
        }

        /** What [family] prints on the face button at [position]; null for PlayStation, which draws shapes. */
        fun letter(family: PadFamily, position: Position): String? = when (family) {
            PadFamily.XBOX -> when (position) {
                Position.BOTTOM -> "A"; Position.RIGHT -> "B"; Position.LEFT -> "X"; Position.TOP -> "Y"
            }
            PadFamily.NINTENDO -> when (position) {
                Position.BOTTOM -> "B"; Position.RIGHT -> "A"; Position.LEFT -> "Y"; Position.TOP -> "X"
            }
            PadFamily.PLAYSTATION -> null
        }

        /** The text on a tab-shaped control ([Shoulder], [Middle], [StickClick]) for [family]. */
        fun tab(family: PadFamily, glyph: PadGlyph): String = when (glyph) {
            is Shoulder -> when (family) {
                PadFamily.XBOX -> if (glyph.trigger) (if (glyph.left) "LT" else "RT") else (if (glyph.left) "LB" else "RB")
                PadFamily.PLAYSTATION -> if (glyph.trigger) (if (glyph.left) "L2" else "R2") else (if (glyph.left) "L1" else "R1")
                PadFamily.NINTENDO -> if (glyph.trigger) (if (glyph.left) "ZL" else "ZR") else (if (glyph.left) "L" else "R")
            }
            is StickClick -> when (family) {
                PadFamily.XBOX -> if (glyph.left) "LS" else "RS"
                PadFamily.PLAYSTATION -> if (glyph.left) "L3" else "R3"
                PadFamily.NINTENDO -> if (glyph.left) "L" else "R"
            }
            is Stick -> if (glyph.left) "L" else "R"
            is Middle -> when (glyph.which) {
                Centre.START -> when (family) { PadFamily.XBOX -> "Menu"; PadFamily.PLAYSTATION -> "Options"; PadFamily.NINTENDO -> "+" }
                Centre.SELECT -> when (family) { PadFamily.XBOX -> "View"; PadFamily.PLAYSTATION -> "Share"; PadFamily.NINTENDO -> "−" }
                Centre.HOME -> when (family) { PadFamily.XBOX -> "Xbox"; PadFamily.PLAYSTATION -> "PS"; PadFamily.NINTENDO -> "Home" }
            }
            is Key -> glyph.text
            is Face -> letter(family, glyph.position) ?: ""
            is Dpad -> ""
        }
    }
}

/**
 * Draws one [PadGlyph] for [family], [size] pixels high, in [ink]: outlines and lettering only,
 * so it reads on any background the text it sits in reads on.
 */
class PadGlyphDrawable(
    private val glyph: PadGlyph,
    private val family: PadFamily,
    private val ink: Int,
    private val size: Int,
) : Drawable() {
    private val stroke = max(1f, size / 12f)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; style = Paint.Style.STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val label = PadGlyph.tab(family, glyph)
    private val width: Int = when (glyph) {
        is PadGlyph.Face, is PadGlyph.Dpad, is PadGlyph.Stick, is PadGlyph.StickClick -> size
        else -> {
            text.textSize = size * 0.5f
            max(size, (text.measureText(label) + size * 0.7f).toInt())
        }
    }

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val b = bounds
        val box = RectF(b.left + stroke, b.top + stroke, b.right - stroke, b.bottom - stroke)
        val cx = box.centerX()
        val cy = box.centerY()
        val r = box.height() / 2
        when (glyph) {
            is PadGlyph.Face -> {
                canvas.drawCircle(cx, cy, r, line)
                val letter = PadGlyph.letter(family, glyph.position)
                if (letter != null) letter(canvas, letter, cx, cy, r * 1.15f) else shape(canvas, glyph.position, cx, cy, r * 0.5f)
            }
            is PadGlyph.Dpad -> dpad(canvas, glyph.arrow, cx, cy, r)
            is PadGlyph.Stick -> {
                canvas.drawCircle(cx, cy, r, line)
                letter(canvas, label, cx, cy, r * 0.95f)
                arrows(canvas, glyph.arrow, cx, cy, r)
            }
            is PadGlyph.StickClick -> {
                canvas.drawCircle(cx, cy, r, line)
                canvas.drawCircle(cx, cy, r * 0.72f, line)
                letter(canvas, label, cx, cy, r * 0.8f)
            }
            is PadGlyph.Shoulder -> {
                // A shoulder is a flat tab; a trigger rounds its top, as the plastic does.
                val corner = if (glyph.trigger) box.height() / 2 else box.height() / 5
                canvas.drawRoundRect(box, corner, corner, line)
                letter(canvas, label, cx, cy, box.height() * 0.5f)
            }
            is PadGlyph.Middle, is PadGlyph.Key -> {
                canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, line)
                letter(canvas, label, cx, cy, box.height() * 0.5f)
            }
        }
    }

    private fun letter(canvas: Canvas, value: String, cx: Float, cy: Float, height: Float) {
        text.textSize = height
        val metrics = text.fontMetrics
        canvas.drawText(value, cx, cy - (metrics.ascent + metrics.descent) / 2, text)
    }

    /** PlayStation's four shapes. */
    private fun shape(canvas: Canvas, position: PadGlyph.Position, cx: Float, cy: Float, s: Float) {
        when (position) {
            PadGlyph.Position.BOTTOM -> {
                canvas.drawLine(cx - s, cy - s, cx + s, cy + s, line)
                canvas.drawLine(cx - s, cy + s, cx + s, cy - s, line)
            }
            PadGlyph.Position.RIGHT -> canvas.drawCircle(cx, cy, s, line)
            PadGlyph.Position.LEFT -> canvas.drawRect(cx - s * 0.9f, cy - s * 0.9f, cx + s * 0.9f, cy + s * 0.9f, line)
            PadGlyph.Position.TOP -> canvas.drawPath(
                Path().apply { moveTo(cx, cy - s); lineTo(cx + s, cy + s * 0.75f); lineTo(cx - s, cy + s * 0.75f); close() },
                line,
            )
        }
    }

    /** A cross with the arm that does the job filled. */
    private fun dpad(canvas: Canvas, arrow: PadGlyph.Arrow, cx: Float, cy: Float, r: Float) {
        val a = r * 0.36f
        val cross = Path().apply {
            moveTo(cx - a, cy - r); lineTo(cx + a, cy - r); lineTo(cx + a, cy - a); lineTo(cx + r, cy - a)
            lineTo(cx + r, cy + a); lineTo(cx + a, cy + a); lineTo(cx + a, cy + r); lineTo(cx - a, cy + r)
            lineTo(cx - a, cy + a); lineTo(cx - r, cy + a); lineTo(cx - r, cy - a); lineTo(cx - a, cy - a); close()
        }
        canvas.drawPath(cross, line)
        fun arm(up: Boolean = false, down: Boolean = false, left: Boolean = false, right: Boolean = false) {
            if (up) canvas.drawRect(cx - a, cy - r, cx + a, cy - a, fill)
            if (down) canvas.drawRect(cx - a, cy + a, cx + a, cy + r, fill)
            if (left) canvas.drawRect(cx - r, cy - a, cx - a, cy + a, fill)
            if (right) canvas.drawRect(cx + a, cy - a, cx + r, cy + a, fill)
        }
        when (arrow) {
            PadGlyph.Arrow.UP -> arm(up = true)
            PadGlyph.Arrow.DOWN -> arm(down = true)
            PadGlyph.Arrow.LEFT -> arm(left = true)
            PadGlyph.Arrow.RIGHT -> arm(right = true)
            PadGlyph.Arrow.HORIZONTAL -> arm(left = true, right = true)
            PadGlyph.Arrow.VERTICAL -> arm(up = true, down = true)
            PadGlyph.Arrow.ANY -> canvas.drawRect(cx - a, cy - a, cx + a, cy + a, fill)
        }
    }

    /** Small arrowheads on a stick's rim for the direction that does the job. */
    private fun arrows(canvas: Canvas, arrow: PadGlyph.Arrow, cx: Float, cy: Float, r: Float) {
        val h = r * 0.32f
        fun head(dx: Float, dy: Float) {
            val tipX = cx + dx * r
            val tipY = cy + dy * r
            val baseX = cx + dx * (r - h)
            val baseY = cy + dy * (r - h)
            canvas.drawPath(
                Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(baseX - dy * h, baseY + dx * h)
                    lineTo(baseX + dy * h, baseY - dx * h)
                    close()
                },
                fill,
            )
        }
        when (arrow) {
            PadGlyph.Arrow.UP -> head(0f, -1f)
            PadGlyph.Arrow.DOWN -> head(0f, 1f)
            PadGlyph.Arrow.LEFT -> head(-1f, 0f)
            PadGlyph.Arrow.RIGHT -> head(1f, 0f)
            PadGlyph.Arrow.HORIZONTAL -> { head(-1f, 0f); head(1f, 0f) }
            PadGlyph.Arrow.VERTICAL -> { head(0f, -1f); head(0f, 1f) }
            PadGlyph.Arrow.ANY -> Unit
        }
    }

    override fun setAlpha(alpha: Int) {
        line.alpha = alpha; fill.alpha = alpha; text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        line.colorFilter = colorFilter; fill.colorFilter = colorFilter; text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Glyphs set into text: each control is its [PadNames] name (what a screen reader reads and a copy
 * carries) drawn as its glyph. One place for every control surface (docs/SPEC.md, "Controls are
 * drawn as glyphs").
 */
object PadGlyphs {
    /** The family to draw for now: the first connected pad's, by its profile; Xbox with none connected. */
    fun family(context: Context): PadFamily {
        val pad = android.view.InputDevice.getDeviceIds().asSequence().mapNotNull(android.view.InputDevice::getDevice)
            .firstOrNull { (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD }
        return runCatching { ControllerProfileStore(context).labels(pad).family }.getOrDefault(PadFamily.XBOX)
    }

    fun of(context: Context, glyph: PadGlyph, name: String, family: PadFamily, textSize: Float): CharSequence {
        val ink = ContextCompat.getColor(context, R.color.eh_text_primary)
        val drawable = PadGlyphDrawable(glyph, family, ink, (textSize * 1.35f).toInt())
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        val align = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BASELINE
        return SpannableString(name).apply { setSpan(ImageSpan(drawable, align), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** [binding] as its glyph; [ControllerBinding.None] stays its words. */
    fun binding(context: Context, binding: ControllerBinding, family: PadFamily, textSize: Float): CharSequence {
        val glyph = PadGlyph.of(binding) ?: return binding.label(context)
        return of(context, glyph, binding.label(context), family, textSize)
    }

    fun key(context: Context, keyCode: Int, family: PadFamily, textSize: Float): CharSequence =
        of(context, PadGlyph.ofKey(keyCode), PadNames.key(keyCode), family, textSize)

    fun axis(context: Context, axis: Int, family: PadFamily, textSize: Float): CharSequence =
        of(context, PadGlyph.ofAxis(axis), PadNames.axis(axis), family, textSize)

    /**
     * [res] formatted with [parts], which may carry glyphs: a string resource's own format
     * cannot hold spans, so each part goes in as a placeholder and is put in place after.
     */
    fun fill(context: Context, @androidx.annotation.StringRes res: Int, vararg parts: CharSequence): CharSequence {
        val tokens = parts.indices.map { "\uE000$it\uE001" }
        val out = SpannableStringBuilder(context.getString(res, *tokens.toTypedArray()))
        tokens.forEachIndexed { i, token ->
            val at = out.toString().indexOf(token)
            if (at >= 0) out.replace(at, at + token.length, parts[i])
        }
        return out
    }

    /** A chord as glyphs: "Select + Start". */
    fun chord(context: Context, keyCodes: Collection<Int>, family: PadFamily, textSize: Float): CharSequence {
        val out = SpannableStringBuilder()
        keyCodes.sorted().forEachIndexed { i, code ->
            if (i > 0) out.append(" + ")
            out.append(key(context, code, family, textSize))
        }
        return out
    }
}
