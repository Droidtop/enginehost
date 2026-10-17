package dev.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which caller-named save roots a launch takes (docs/contract.md, Droidtop/tracker#580). */
class SaveRootTest {
    @Test
    fun `a folder in shared storage or on a removable volume is taken`() {
        assertTrue(SaveRoot.acceptable("/storage/emulated/0/Droidtop/cloud/123456"))
        assertTrue(SaveRoot.acceptable("/storage/1A2B-3C4D/saves"))
        assertTrue(SaveRoot.acceptable("/sdcard/Droidtop/cloud/123456"))
    }

    @Test
    fun `relative paths, other places, volume roots and app folders are refused`() {
        assertFalse(SaveRoot.acceptable("Droidtop/cloud"))
        assertFalse(SaveRoot.acceptable("/data/data/dev.droidtop.app/files"))
        assertFalse(SaveRoot.acceptable("/storage/emulated/0"))
        assertFalse(SaveRoot.acceptable("/storage/emulated/0/Android/data/dev.enginehost/files"))
        assertFalse(SaveRoot.acceptable("/storage/emulated/0/Android/obb/x"))
        assertFalse(SaveRoot.acceptable("/storage/emulated/0/Saves/../Android/data/x"))
        assertFalse(SaveRoot.acceptable("/storage/self/primary/Saves"))
    }
}
