package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every layout qualifier variant must declare the same view ids as the
 * default layout it stands in for. Activities bind ids with findViewById
 * and a qualifier variant that stopped tracking the default is a null
 * pointer on exactly the devices that select it. The Settings screen
 * crashed on the landscape handheld this project targets because the
 * landscape layout never received the update controls; this is what
 * would have caught that on CI.
 */
class LayoutVariantsTest {
    private val res = File("src/main/res")

    private fun ids(file: File): Set<String> =
        Regex("@\\+id/([A-Za-z0-9_]+)").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `layout variants declare the same ids as the default layout`() {
        assertTrue("run from the app module: ${res.absolutePath}", res.isDirectory)
        val variants = res.listFiles { f -> f.isDirectory && f.name.startsWith("layout-") }.orEmpty()
        assertTrue("no layout variants found", variants.isNotEmpty())
        val problems = mutableListOf<String>()
        for (dir in variants) {
            for (file in dir.listFiles { f -> f.extension == "xml" }.orEmpty()) {
                val base = File(res, "layout/${file.name}")
                if (!base.isFile) {
                    problems += "${dir.name}/${file.name} has no default layout"
                    continue
                }
                val missing = ids(base) - ids(file)
                val extra = ids(file) - ids(base)
                if (missing.isNotEmpty()) problems += "${dir.name}/${file.name} lacks ${missing.sorted()}"
                if (extra.isNotEmpty()) problems += "${dir.name}/${file.name} adds ${extra.sorted()} the default layout lacks"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
