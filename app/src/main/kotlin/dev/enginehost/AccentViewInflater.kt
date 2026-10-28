package dev.enginehost

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatViewInflater
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * The accent painter (Droidtop/tracker#644; docs/ui-v2.md "The accent at run time"):
 * Enginehost's screens are drawn in the accent [Accent] resolves, not only in the one
 * the XML palette ships.
 *
 * Android cannot recolour a resource at run time, so the painter works where each view
 * is made. The app theme names this class as AppCompat's `viewInflaterClass`; every view
 * a layout inflates passes through here, and one whose background, text colour or tint
 * names an accent drawable or colour gets the same thing built in the chosen palette
 * ([AccentDrawables]). With Enginehost's own accent there is nothing to do and nothing
 * is touched. Code that sets an accent resource itself asks [drawable] and [colour].
 */
class AccentViewInflater : AppCompatViewInflater() {
    override fun createTextView(context: Context, attrs: AttributeSet?): AppCompatTextView =
        super.createTextView(context, attrs).also { paint(it, attrs, 0) }

    override fun createButton(context: Context, attrs: AttributeSet?): AppCompatButton =
        super.createButton(context, attrs).also { paint(it, attrs, androidx.appcompat.R.attr.buttonStyle) }

    override fun createEditText(context: Context, attrs: AttributeSet?): AppCompatEditText =
        super.createEditText(context, attrs).also { paint(it, attrs, androidx.appcompat.R.attr.editTextStyle) }

    override fun createImageView(context: Context, attrs: AttributeSet?): AppCompatImageView =
        super.createImageView(context, attrs).also { paint(it, attrs, 0) }

    override fun createImageButton(context: Context, attrs: AttributeSet?): AppCompatImageButton =
        super.createImageButton(context, attrs).also { paint(it, attrs, 0) }

    /** Every other view: layouts, rows, cards, switches and bars, made here so they can be painted. */
    override fun createView(context: Context, name: String, attrs: AttributeSet?): View? {
        if (attrs == null || Accent.palette(context) == null || name in INFLATER_TAGS) return null
        val view = instantiate(context, name, attrs) ?: return null
        paint(view, attrs, if (view is SwitchCompat) androidx.appcompat.R.attr.switchStyle else 0)
        return view
    }

    private fun paint(view: View, attrs: AttributeSet?, defStyleAttr: Int) {
        val context = view.context
        val palette = Accent.palette(context) ?: return
        val painted = AccentDrawables(context, palette)
        val a = context.obtainStyledAttributes(attrs, PAINTED_ATTRS, defStyleAttr, 0)
        try {
            painted.forDrawable(a.getResourceId(PAINTED_ATTRS.indexOf(android.R.attr.background), 0))?.let(view::setBackground)
            painted.forColour(a.getResourceId(PAINTED_ATTRS.indexOf(android.R.attr.backgroundTint), 0))?.let { view.backgroundTintList = it }
            if (view is TextView) {
                painted.forColour(a.getResourceId(PAINTED_ATTRS.indexOf(android.R.attr.textColor), 0))?.let(view::setTextColor)
                painted.forColour(a.getResourceId(PAINTED_ATTRS.indexOf(android.R.attr.drawableTint), 0))
                    ?.let { view.compoundDrawableTintList = it }
            }
            if (view is ImageView) {
                painted.forColour(a.getResourceId(PAINTED_ATTRS.indexOf(android.R.attr.tint), 0))?.let { view.imageTintList = it }
            }
        } finally {
            a.recycle()
        }
        // What the theme's colorAccent and colorControlActivated colour, which no layout names.
        when (view) {
            is ProgressBar -> ColorStateList.valueOf(palette.accent).let {
                view.indeterminateTintList = it
                view.progressTintList = it
            }
            is SwitchCompat -> {
                val off = themeColour(context, androidx.appcompat.R.attr.colorSwitchThumbNormal)
                val ground = themeColour(context, android.R.attr.colorForeground)
                view.thumbTintList = checkedList(palette.accent, off)
                view.trackTintList = checkedList(ColorUtils.setAlphaComponent(palette.accent, TRACK_ALPHA), ColorUtils.setAlphaComponent(ground, TRACK_ALPHA))
            }
        }
    }

    companion object {
        /** Sorted ascending, as obtainStyledAttributes requires. */
        private val PAINTED_ATTRS = intArrayOf(
            android.R.attr.background, android.R.attr.textColor, android.R.attr.tint,
            android.R.attr.backgroundTint, android.R.attr.drawableTint,
        ).sortedArray()

        /** Tags LayoutInflater handles itself and that name no view class. */
        private val INFLATER_TAGS = setOf("view", "fragment", "include", "merge", "requestFocus", "tag", "blink")

        /** The platform packages a bare tag name is looked up in, as LayoutInflater does. */
        private val PREFIXES = listOf("android.widget.", "android.view.", "android.webkit.")

        /** The switch track's alpha in AppCompat's own tint (0.3 of the control colour). */
        private const val TRACK_ALPHA = 77

        private val constructors = ConcurrentHashMap<String, java.lang.reflect.Constructor<out View>>()

        private fun instantiate(context: Context, name: String, attrs: AttributeSet): View? {
            val candidates = if ('.' in name) listOf(name) else PREFIXES.map { it + name }
            for (className in candidates) {
                val constructor = constructors[className] ?: runCatching {
                    Class.forName(className, false, context.classLoader).asSubclass(View::class.java)
                        .getConstructor(Context::class.java, AttributeSet::class.java)
                }.getOrNull()?.also { constructors[className] = it } ?: continue
                return constructor.newInstance(context, attrs)
            }
            return null
        }

        private fun checkedList(checked: Int, otherwise: Int) = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(checked, otherwise),
        )

        private fun themeColour(context: Context, attr: Int): Int {
            val a = context.obtainStyledAttributes(intArrayOf(attr))
            return try {
                a.getColor(0, 0)
            } finally {
                a.recycle()
            }
        }

        /** Drawable [id] as this screen's accent draws it; the resource itself with Enginehost's own accent. */
        fun drawable(context: Context, @DrawableRes id: Int): Drawable? =
            Accent.palette(context)?.let { AccentDrawables(context, it).forDrawable(id) } ?: ContextCompat.getDrawable(context, id)

        /** Colour [id] as this screen's accent draws it. */
        fun colour(context: Context, @ColorRes id: Int): Int =
            Accent.palette(context)?.let { AccentDrawables(context, it).forColour(id)?.defaultColor } ?: ContextCompat.getColor(context, id)
    }
}
