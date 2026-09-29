package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The save-root half of the launch preflight, tested through the
 * Context-free overloads of [SaveLocationStore]: the whole decision, whose
 * exceptions [GameRunner.plan] turns into Plan.Failure sentences. This app
 * has no Robolectric, so the store's SharedPreferences-backed half cannot
 * run in a unit test -- which is why the decision sits in overloads that
 * take the resolved root. The two failure paths are exactly the two plan()
 * catches: an unusable save folder, and a save folder name that is not one
 * plain name.
 */
class SaveLocationStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun makeConfig(
        engine: String = "html",
        engineContext: String? = null,
        saveFolder: String? = null,
    ): EngineConfig {
        return EngineConfig(
            engine = engine,
            engineContext = engineContext,
            engineVersion = Version.parse("1.0.0"),
            runtimeRequirements = emptyMap(),
            pluginVersionConstraint = null,
            execFile = null,
            saveFolder = saveFolder,
            options = null,
        )
    }

    @Test
    fun `a root that cannot hold the saves folder is unusable, not a crash`() {
        // A card root that has gone, or a root path a file now occupies.
        val root = temporaryFolder.newFolder("holder").resolve("gone").apply { writeText("") }
        assertThrows(UnusableSaveFolderException::class.java) { SaveLocationStore.saveRootFor(root) }
    }

    @Test
    fun `a saves folder that exists but is not writable is unusable, not a crash`() {
        val root = temporaryFolder.newFolder("root")
        val saves = File(root, "saves").apply {
            mkdir()
            setWritable(false)
        }
        try {
            assertThrows(UnusableSaveFolderException::class.java) { SaveLocationStore.saveRootFor(root) }
        } finally {
            saves.setWritable(true)
        }
    }

    @Test
    fun `a per-game save folder beneath a root that cannot hold it is unusable, not a crash`() {
        val root = temporaryFolder.newFolder("holder").resolve("gone").apply { writeText("") }
        assertThrows(UnusableSaveFolderException::class.java) {
            SaveLocationStore.saveFolderFor(root, makeConfig(saveFolder = "My Game"))
        }
    }

    @Test
    fun `a save folder name that is not one plain folder name is refused with its reason`() {
        val root = temporaryFolder.newFolder("root")
        listOf("", ".", "..", "a/b", "a\\b", "a\u0000b").forEach { name ->
            val thrown = assertThrows(IllegalArgumentException::class.java) {
                SaveLocationStore.saveFolderFor(root, makeConfig(saveFolder = name))
            }
            // plan() shows this message as the failure sentence, so it is the contract.
            assertEquals("A save folder must be a single folder name", thrown.message)
        }
    }

    @Test
    fun `a plain save folder name gets its folder created beneath the root`() {
        val root = temporaryFolder.newFolder("root")
        val folder = SaveLocationStore.saveFolderFor(root, makeConfig(saveFolder = "My Game"))
        assertEquals(File(root, "My Game"), folder)
        assertTrue(folder.isDirectory)
    }

    @Test
    fun `an engine that names its own saves gets the root, and so does a config without a save folder`() {
        val root = temporaryFolder.newFolder("root")
        // A `saveFolder` an older config names for such an engine is not used.
        assertEquals(root, SaveLocationStore.saveFolderFor(root, makeConfig(engine = "renpy", saveFolder = "left over")))
        assertEquals(root, SaveLocationStore.saveFolderFor(root, makeConfig(saveFolder = null)))
    }
}
