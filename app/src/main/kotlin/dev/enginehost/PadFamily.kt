package dev.enginehost

import android.view.InputDevice

/**
 * What is printed on a pad's buttons (docs/SPEC.md, "Controls are drawn as glyphs"): Xbox, PlayStation or Nintendo, with
 * the word Enginehost stores ([PadFamily.id]). It is lib-input's ([dev.droidtop.lib.input.PadFamily], Droidtop/tracker#867
 * D4: one shared controller model), the same enum this app kept a copy of.
 */
typealias PadFamily = dev.droidtop.lib.input.PadFamily

/** A pad's family and where it came from, strongest first. */
data class PadLabels(val family: PadFamily, val source: Source) {
    enum class Source { CHOSEN, DETECTED, ASSUMED }
}

/**
 * Which [PadFamily] a pad belongs to, from its USB or Bluetooth vendor and product id with its name as the fallback: SDL's
 * ids and rules, as lib-input's [dev.droidtop.lib.input.PadFamilies] applies them (Droidtop/tracker#867), plus the labels a
 * pad's profile page shows. An id SDL does not know answers null, which is "unknown", never a guess.
 */
object PadFamilies {
    fun classify(vendorId: Int, productId: Int, name: String?): PadFamily? =
        dev.droidtop.lib.input.PadFamilies.classify(vendorId, productId, name)

    fun classify(device: InputDevice): PadFamily? = classify(device.vendorId, device.productId, device.name)

    /** The labels for [device]: the person's choice on its profile page, else what its id says, else Xbox. */
    fun labelsFor(chosen: PadFamily?, detected: PadFamily?): PadLabels = when {
        chosen != null -> PadLabels(chosen, PadLabels.Source.CHOSEN)
        detected != null -> PadLabels(detected, PadLabels.Source.DETECTED)
        else -> PadLabels(PadFamily.XBOX, PadLabels.Source.ASSUMED)
    }
}
