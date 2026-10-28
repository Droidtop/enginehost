package dev.enginehost

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which accent Enginehost is drawn in (Settings > Appearance, Droidtop/tracker#644).
 *
 * The person picks one of three: Enginehost's own azure (the default, and what a
 * device without droidtop shows), "Match my device" (Android's dynamic colour,
 * API 31 and up), or a hue of their own. A launch from droidtop can name droidtop's
 * resolved accent for the session it starts ([sessionAccent]); that wins over the
 * setting until the process ends, so the two apps look like one while droidtop
 * drives. Every choice goes through [AccentPalette.of]'s contrast guard.
 */
object Accent {
    enum class Choice { ENGINEHOST, DEVICE, PICKED }

    private const val PREFS = "appearance"
    private const val KEY_CHOICE = "accent_choice"
    private const val KEY_HUE = "accent_hue"

    /** A launch caller's accent ("#RRGGBB" on dev.enginehost.LAUNCH), for this process's life. */
    @Volatile
    var sessionAccent: Int? = null
        set(value) {
            field = value
            generation++
        }

    /** Bumped whenever the accent may have changed, so an open screen knows to redraw. */
    @Volatile
    var generation = 0
        private set

    fun choice(context: Context): Choice =
        prefs(context).getString(KEY_CHOICE, null)?.let { runCatching { Choice.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == Choice.DEVICE && !deviceColourAvailable() }
            ?: Choice.ENGINEHOST

    /** The picked hue in degrees; azure's by default, so a first pick starts where the app is. */
    fun pickedHue(context: Context): Float = prefs(context).getFloat(KEY_HUE, AZURE_HUE)

    fun set(context: Context, choice: Choice, hue: Float? = null) {
        prefs(context).edit().apply {
            putString(KEY_CHOICE, choice.name)
            hue?.let { putFloat(KEY_HUE, it.coerceIn(0f, 359f)) }
        }.apply()
        generation++
    }

    fun deviceColourAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * The palette to draw [context] in, or null when it is Enginehost's own: the XML palette
     * (values, values-night) is that one, so nothing needs painting.
     */
    fun palette(context: Context): AccentPalette? {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val chosen = sessionAccent ?: when (choice(context)) {
            Choice.ENGINEHOST -> return null
            Choice.DEVICE -> deviceAccent(context, dark) ?: return null
            Choice.PICKED -> pickedPreview(context, pickedHue(context))
        }
        val background = ContextCompat.getColor(context, R.color.eh_background)
        val surface = ContextCompat.getColor(context, R.color.eh_surface)
        val palette = AccentPalette.of(chosen, background, surface, dark)
        // A choice that comes out as the shipped accent is the shipped palette.
        return palette.takeUnless { it.accent == (ContextCompat.getColor(context, R.color.eh_accent) or 0xFF000000.toInt()) }
    }

    /** Android's dynamic accent: its 200 tone on dark, 600 on light, the tones Material uses for primary. */
    private fun deviceAccent(context: Context, dark: Boolean): Int? {
        if (!deviceColourAvailable()) return null
        return runCatching {
            context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        }.getOrNull()
    }

    /** The accent a picked [hue] comes out as on [context]'s ground, for the picker's preview. */
    fun pickedPreview(context: Context, hue: Float): Int {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val chosen = AccentPalette.fromHsl(hue.toDouble(), PICKED_SATURATION, if (dark) 0.62 else 0.38)
        return AccentPalette.guard(chosen, ContextCompat.getColor(context, R.color.eh_background))
    }

    /** "#RRGGBB" (or "RRGGBB") to an opaque colour; null for anything else. */
    fun parse(text: String?): Int? {
        val hex = text?.trim()?.removePrefix("#") ?: return null
        if (hex.length != 6 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return hex.toLong(16).toInt() or 0xFF000000.toInt()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Azure #3BB5FF's hue. */
    const val AZURE_HUE = 202.6f

    /** A picked hue is drawn at the saturation of Enginehost's azure, so every hue looks as deliberate. */
    private const val PICKED_SATURATION = 1.0
}
