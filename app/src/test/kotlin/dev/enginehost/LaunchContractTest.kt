package dev.enginehost

import dev.droidtop.shared.launchcontract.EnginehostConfig
import dev.droidtop.shared.launchcontract.EnginehostIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contract with droidtop is held once, in droidtop-shared's `:launch-contract`
 * (Droidtop/tracker#204). What cannot import a Kotlin constant -- the manifest and the prose in
 * docs/contract.md -- is checked against it here, so a rename on either side fails a test instead of
 * a launch.
 */
class LaunchContractTest {
    private fun fromModule(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("$path not found from ${File(".").absolutePath}")

    private val manifest get() = fromModule("src/main/AndroidManifest.xml").readText()
    private val contractDoc get() = fromModule("../docs/contract.md").readText()

    @Test
    fun theManifestAnswersTheActionsTheContractNames() {
        for (action in listOf(
            EnginehostIntents.ACTION_LAUNCH,
            EnginehostIntents.ACTION_CONFIGURE,
            EnginehostIntents.ACTION_CONFIGURE_SETTINGS,
            EnginehostIntents.ACTION_CONFIGURE_SAVES,
            EnginehostIntents.ACTION_SCAN,
            EnginehostIntents.ACTION_END_GAME,
        )) {
            assertTrue("$action is not in the manifest", manifest.contains("android:name=\"$action\""))
        }
    }

    @Test
    fun theManifestDeclaresTheCapabilitiesProviderAtTheContractsAuthority() {
        assertTrue(manifest.contains("android:authorities=\"${EnginehostIntents.CAPABILITIES_AUTHORITY}\""))
    }

    @Test
    fun theCodesOwnNamesAreTheContractsNotCopiesOfThem() {
        assertEquals(EnginehostIntents.EXTRA_PATH, LaunchActivity.EXTRA_PATH)
        assertEquals(EnginehostIntents.EXTRA_CONFIG, LaunchActivity.EXTRA_CONFIG)
        assertEquals(EnginehostIntents.EXTRA_AUTOINSTALL_PLUGIN, LaunchActivity.EXTRA_AUTOINSTALL)
        assertEquals(EnginehostIntents.ACTION_CONFIGURE, ConfigEditorActivity.ACTION_CONFIGURE)
        assertEquals(EnginehostIntents.ACTION_SCAN, GameScanActivity.ACTION_SCAN)
        assertEquals(EnginehostIntents.ACTION_GAME_ENDED, LaunchEntryActivity.ACTION_GAME_ENDED)
        assertEquals(EnginehostIntents.CAPABILITIES_AUTHORITY, CapabilityProvider.AUTHORITY)
    }

    @Test
    fun theContractDocumentNamesTheActionsAndExtrasTheCodeUses() {
        val doc = contractDoc
        assertTrue(doc.contains(EnginehostIntents.ACTION_LAUNCH))
        assertTrue(doc.contains(EnginehostIntents.ACTION_CONFIGURE))
        assertTrue(doc.contains(EnginehostIntents.ACTION_SCAN))
        assertTrue(doc.contains(EnginehostIntents.ACTION_END_GAME))
        assertTrue(doc.contains("extra \"${EnginehostIntents.EXTRA_PATH}\""))
        assertTrue(doc.contains("extra \"${EnginehostIntents.EXTRA_CONFIG}\""))
    }

    @Test
    fun theExampleConfigInTheContractDocumentIsAConfigTheSharedCheckAccepts() {
        val example = Regex("```json\\s*(\\{.*?\\n\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(contractDoc)?.groupValues?.get(1) ?: error("no json example in docs/contract.md")
        assertEquals(emptyList<String>(), EnginehostConfig.problems(example))
    }
}
