package dev.enginehost

import android.view.InputDevice

/** What is printed on a pad's buttons (docs/SPEC.md, "Controls are drawn as glyphs"). */
enum class PadFamily(val id: String) {
    /** Bottom A, right B, left X, top Y; LB, RB, LT, RT; Menu and View. Also what an unknown pad is drawn as. */
    XBOX("xbox"),

    /** Bottom cross, right circle, left square, top triangle; L1, R1, L2, R2; Options and Share. */
    PLAYSTATION("playstation"),

    /** Bottom B, right A, left Y, top X; L, R, ZL, ZR; plus and minus. */
    NINTENDO("nintendo"),
    ;

    companion object {
        fun fromId(id: String?): PadFamily? = entries.firstOrNull { it.id == id }
    }
}

/** A pad's family and where it came from, strongest first. */
data class PadLabels(val family: PadFamily, val source: Source) {
    enum class Source { CHOSEN, DETECTED, ASSUMED }
}

/**
 * Which [PadFamily] a pad belongs to, from its USB or Bluetooth vendor and product id, with
 * its name as the fallback. The ids are SDL's ([SdlControllerIds], generated from SDL's
 * controller list); the rules below are those `SDL_GetJoystickGameControllerTypeFromVIDPID`
 * applies before and around that list (src/joystick/SDL_joystick.c, zlib), as droidtop
 * applies them (third_party/sdl_controller_list/README.md). An id SDL does not know answers
 * null, which is "unknown", never a guess.
 */
object PadFamilies {
    fun classify(vendorId: Int, productId: Int, name: String?): PadFamily? {
        // Some Switch clones are only identifiable by their name.
        if (vendorId == 0 && productId == 0) {
            return if (name in NAME_ONLY_NINTENDO) PadFamily.NINTENDO else null
        }
        return when {
            vendorId == 0x0001 && productId == 0x0001 -> null
            // The Nintendo Online NES pad shares the right Joy-Con's id and is not a Joy-Con.
            vendorId == 0x057e && productId == 0x2007 && name?.contains("NES Controller") == true -> null
            // Amazon Luna (USB and Bluetooth), Google Stadia and the NVIDIA Shield print the Xbox letters.
            (vendorId == 0x1949 && productId == 0x0419) || (vendorId == 0x0171 && productId == 0x0419) -> PadFamily.XBOX
            vendorId == 0x18d1 && productId == 0x9400 -> PadFamily.XBOX
            vendorId == 0x0955 && (productId == 0x7210 || productId == 0x7214) -> PadFamily.XBOX
            else -> SdlControllerIds.familyOf(vendorId, productId)
        }
    }

    fun classify(device: InputDevice): PadFamily? = classify(device.vendorId, device.productId, device.name)

    /** The labels for [device]: the person's choice on its profile page, else what its id says, else Xbox. */
    fun labelsFor(chosen: PadFamily?, detected: PadFamily?): PadLabels = when {
        chosen != null -> PadLabels(chosen, PadLabels.Source.CHOSEN)
        detected != null -> PadLabels(detected, PadLabels.Source.DETECTED)
        else -> PadLabels(PadFamily.XBOX, PadLabels.Source.ASSUMED)
    }

    private val NAME_ONLY_NINTENDO = setOf("Lic Pro Controller", "Nintendo Wireless Gamepad", "Wireless Gamepad")
}
