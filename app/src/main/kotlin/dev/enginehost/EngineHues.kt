package dev.enginehost

import android.content.Context
import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * One hue per engine family, so a card says what it runs before it is read.
 * The hues live in colors.xml (light and night); this maps families to them
 * and paints a chip: the hue as text over the same hue at low alpha.
 */
object EngineHues {
    fun color(context: Context, engine: String): Int = ContextCompat.getColor(
        context,
        when (engine) {
            "renpy" -> R.color.eh_engine_renpy
            "rpgmaker" -> R.color.eh_engine_rpgmaker
            "kirikiri", "kirikiri2" -> R.color.eh_engine_kirikiri
            "godot" -> R.color.eh_engine_godot
            "html" -> R.color.eh_engine_html
            "flash_air" -> R.color.eh_engine_flash
            else -> R.color.eh_engine_other
        },
    )

    fun paintChip(chip: TextView, engine: String) {
        val hue = color(chip.context, engine)
        chip.setTextColor(hue)
        chip.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(hue, 0x30))
    }
}
