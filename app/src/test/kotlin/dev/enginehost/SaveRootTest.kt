package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `a refusal names its reason`() {
        assertEquals(SaveRoot.OUTSIDE_SHARED_STORAGE, SaveRoot.refusal("/data/data/dev.droidtop.app/files"))
        assertEquals(SaveRoot.OUTSIDE_SHARED_STORAGE, SaveRoot.refusal("Droidtop/cloud"))
        assertEquals(SaveRoot.VOLUME_ROOT, SaveRoot.refusal("/storage/emulated/0"))
        assertEquals(SaveRoot.ANDROID_DATA, SaveRoot.refusal("/storage/emulated/0/Android/obb/x"))
        assertEquals(null, SaveRoot.refusal("/storage/emulated/0/Droidtop/cloud/123456"))
        assertEquals(SaveRoot.MISSING, (SaveRoot.check("{}") as SaveRoot.Check.Refused).reason)
        assertEquals(SaveRoot.MISSING, (SaveRoot.check(null) as SaveRoot.Check.Refused).reason)
    }

    @Test
    fun `only a true savesSynced flags a launch`() {
        assertTrue(SaveRoot.synced("""{"savesSynced": true}"""))
        assertFalse(SaveRoot.synced("""{"savesSynced": false}"""))
        assertFalse(SaveRoot.synced("""{}"""))
        assertFalse(SaveRoot.synced(null))
    }

    @Test
    fun `a synced game's fallback folder is its own, beside the engines' saves`() {
        val root = java.io.File("/storage/emulated/0/Enginehost")
        val one = SaveLocationStore.syncedFolderIn(root, java.io.File("/storage/emulated/0/Games/A/Game"))
        val two = SaveLocationStore.syncedFolderIn(root, java.io.File("/storage/emulated/0/Games/B/Game"))
        assertEquals(java.io.File(root, "synced-saves"), one.parentFile)
        assertTrue(one.name.startsWith("Game-"))
        assertNotEquals(one, two)
        assertEquals(one, SaveLocationStore.syncedFolderIn(root, java.io.File("/storage/emulated/0/Games/A/Game")))
    }
}
