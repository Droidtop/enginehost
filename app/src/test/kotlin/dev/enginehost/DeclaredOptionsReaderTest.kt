package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which bundles' declarations apply to a config, and what happens when two
 * of them declare the same key. Pure rules, so no Context and no files.
 */
class DeclaredOptionsReaderTest {
    private fun bundle(
        bundleId: String,
        engine: String = "rpgmaker",
        pluginVersion: String = "1.0.0",
    ) = InstalledPlugin(
        info = PluginInfo(engine, Version.parse(pluginVersion), emptyList()),
        bundleId = bundleId,
        entrypointClass = "dev.example.Entry",
        directory = File(bundleId),
    )

    private val mkxpz = bundle("mkxp-z")
    private val easyrpg = bundle("easyrpg")
    private val mv = bundle("rpgmaker-mv")

    @Test
    fun `a resolved bundle is asked, and only it`() {
        val applicable = DeclaredOptionsReader.applicableBundles(
            listOf(mkxpz, easyrpg, mv),
            resolved = mv,
            engine = "rpgmaker",
        )

        // The real bug: all three declare engine "rpgmaker", so matching on
        // the engine name offered an MV game mkxp-z's rgssVersion.
        assertEquals(listOf(mv), applicable)
    }

    @Test
    fun `the resolved bundle wins even when others share its engine and are newer`() {
        val newer = bundle("mkxp-z", pluginVersion = "9.0.0")

        assertEquals(
            listOf(easyrpg),
            DeclaredOptionsReader.applicableBundles(listOf(newer, easyrpg), easyrpg, "rpgmaker"),
        )
    }

    @Test
    fun `with no resolved bundle the engine's bundles apply in a defined order`() {
        val discoveryOrders = listOf(
            listOf(mkxpz, easyrpg, mv),
            listOf(mv, mkxpz, easyrpg),
            listOf(easyrpg, mv, mkxpz),
        )

        // Whatever order PluginRegistry.discover happened to return, the
        // answer is the same one.
        discoveryOrders.forEach { installed ->
            assertEquals(
                listOf("easyrpg", "mkxp-z", "rpgmaker-mv"),
                DeclaredOptionsReader.applicableBundles(installed, null, "rpgmaker")
                    .map { it.bundleId },
            )
        }
    }

    @Test
    fun `bundles for another engine never contribute`() {
        val applicable = DeclaredOptionsReader.applicableBundles(
            listOf(mkxpz, bundle("godot", engine = "godot")),
            resolved = null,
            engine = "rpgmaker",
        )

        assertEquals(listOf("mkxp-z"), applicable.map { it.bundleId })
    }

    @Test
    fun `with neither a bundle nor an engine nothing is claimed`() {
        assertTrue(DeclaredOptionsReader.applicableBundles(listOf(mkxpz), null, null).isEmpty())
        assertTrue(DeclaredOptionsReader.applicableBundles(listOf(mkxpz), null, "").isEmpty())
    }

    @Test
    fun `a duplicate key keeps the first declaration and the result is display ordered`() {
        val merged = DeclaredOptionsReader.merge(
            listOf(
                option("fullscreen", "Fullscreen", "EasyRPG's wording"),
                option("rgssVersion", "RGSS version", ""),
                option("fullscreen", "Fullscreen", "mkxp-z's wording"),
            ),
        )

        assertEquals(listOf("fullscreen", "rgssVersion"), merged.map { it.key })
        assertEquals("EasyRPG's wording", merged.first().description)
    }

    @Test
    fun `options sharing a label still come out in one defined order`() {
        val merged = DeclaredOptionsReader.merge(
            listOf(option("zeta", "Same label"), option("alpha", "same LABEL")),
        )

        assertEquals(listOf("alpha", "zeta"), merged.map { it.key })
    }

    private fun option(key: String, label: String, description: String = "") = DeclaredOption(
        key = key,
        label = label,
        type = "string",
        repeats = false,
        description = description,
        choices = emptyList(),
    )
}
