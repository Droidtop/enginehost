package dev.enginehost

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The accent colours one screen is drawn with, derived from one chosen colour
 * (Droidtop/tracker#644, docs/ui-v2.md "The accent at run time"): the accent
 * itself, its pressed state, the text on it, its container (the focus fill and
 * tonal buttons), that container pressed, and the text on the container.
 *
 * Whatever is chosen (Enginehost's azure, the device's dynamic colour, a picked
 * hue, droidtop's accent for a session it started) goes through one contrast
 * guard, [of]: the hue stays and only the lightness moves, until the accent reads
 * at [MIN_CONTRAST] on the ground. That is the rule that turned azure #3BB5FF into
 * #0072B8 on the light ground. Pure arithmetic on ARGB ints, so it is tested on
 * the JVM.
 */
data class AccentPalette(
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val container: Int,
    val containerPressed: Int,
    val onContainer: Int,
) {
    companion object {
        /** The body-text contrast the proposal sets (4.5:1): the accent is also link and label text. */
        const val MIN_CONTRAST = 4.5

        /** The near-black and white text on an accent fill; whichever reads better is used. */
        private const val DARK_INK = 0xFF06121C.toInt()
        private const val LIGHT_INK = 0xFFFFFFFF.toInt()

        /**
         * The palette for [chosen] on a theme whose ground is [background] and whose content
         * surface is [surface]; [dark] is the night theme. The container is the accent laid
         * over the surface (a quarter of it in dark, an eighth in light, as the shipped
         * palettes are), and its text is the accent's hue taken nearly to black or white.
         */
        fun of(chosen: Int, background: Int, surface: Int, dark: Boolean): AccentPalette {
            val accent = guard(chosen, background)
            val hsl = toHsl(accent)
            // Pressed moves away from the ground: lighter on dark, darker on light.
            val pressed = fromHsl(hsl[0], hsl[1], (hsl[2] + if (dark) 0.08 else -0.08).coerceIn(0.0, 1.0))
            val onAccent = if (contrast(DARK_INK, accent) >= contrast(LIGHT_INK, accent)) DARK_INK else LIGHT_INK
            val container = blend(surface, accent, if (dark) 0.25 else 0.12)
            val containerPressed = blend(surface, accent, if (dark) 0.35 else 0.2)
            val onContainer = fromHsl(hsl[0], min(hsl[1], 1.0), if (dark) 0.9 else 0.09)
            return AccentPalette(accent, pressed, onAccent, container, containerPressed, onContainer)
        }

        /**
         * [colour] with its lightness moved, in small steps away from the ground, until it reads
         * at [MIN_CONTRAST] on [background]; its hue and saturation are kept. A colour that
         * already reads is returned as it is.
         */
        fun guard(colour: Int, background: Int): Int {
            val opaque = colour or 0xFF000000.toInt()
            if (contrast(opaque, background) >= MIN_CONTRAST) return opaque
            val hsl = toHsl(opaque)
            val step = if (luminance(background) > 0.5) -0.01 else 0.01
            var lightness = hsl[2]
            while (lightness in 0.0..1.0) {
                val candidate = fromHsl(hsl[0], hsl[1], lightness)
                if (contrast(candidate, background) >= MIN_CONTRAST) return candidate
                lightness += step
            }
            return fromHsl(hsl[0], hsl[1], if (step < 0) 0.0 else 1.0)
        }

        /** WCAG contrast ratio of two opaque colours. */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** WCAG relative luminance. */
        fun luminance(colour: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((colour shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }

        /** [over] laid on [base] at [amount] (0 is all base, 1 all over), opaque. */
        fun blend(base: Int, over: Int, amount: Double): Int {
            fun mix(shift: Int): Int {
                val b = (base shr shift) and 0xFF
                val o = (over shr shift) and 0xFF
                return (b + (o - b) * amount).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }

        /** Hue in degrees, saturation and lightness 0 to 1. */
        fun toHsl(colour: Int): DoubleArray {
            val r = ((colour shr 16) and 0xFF) / 255.0
            val g = ((colour shr 8) and 0xFF) / 255.0
            val b = (colour and 0xFF) / 255.0
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            val lightness = (high + low) / 2
            if (high == low) return doubleArrayOf(0.0, 0.0, lightness)
            val delta = high - low
            val saturation = if (lightness > 0.5) delta / (2 - high - low) else delta / (high + low)
            val hue = when (high) {
                r -> ((g - b) / delta + (if (g < b) 6 else 0)) * 60
                g -> ((b - r) / delta + 2) * 60
                else -> ((r - g) / delta + 4) * 60
            }
            return doubleArrayOf(hue, saturation, lightness)
        }

        fun fromHsl(hue: Double, saturation: Double, lightness: Double): Int {
            val c = (1 - abs(2 * lightness - 1)) * saturation
            val x = c * (1 - abs((hue / 60) % 2 - 1))
            val m = lightness - c / 2
            val (r, g, b) = when {
                hue < 60 -> Triple(c, x, 0.0)
                hue < 120 -> Triple(x, c, 0.0)
                hue < 180 -> Triple(0.0, c, x)
                hue < 240 -> Triple(0.0, x, c)
                hue < 300 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            fun byte(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
        }
    }
}
