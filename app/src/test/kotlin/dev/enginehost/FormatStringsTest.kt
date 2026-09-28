package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The actual settings-screen crash (2026-09-28, console report) was not the
 * missing-layout-row bug the previous fix here addressed: it was
 * `launch_access_summary`'s two `<item>`s reading `%1 app decided` /
 * `%1 apps decided` -- a positional index with no `$` and no conversion
 * character, which `Resources.getQuantityString` runs straight through
 * `java.util.Formatter` and which throws `UnknownFormatConversionException`
 * the moment the screen that uses it is ever opened. Every `%`-using
 * `<string>` and plural `<item>` in the resources gets the same runtime
 * check `getQuantityString`/`getString` would give it, without a Context:
 * `String.format` needs no Android framework, only real-looking arguments
 * for whatever conversions the text actually names.
 */
class FormatStringsTest {
    private val res = File("src/main/res")

    private data class Entry(val file: String, val name: String, val text: String)

    private fun entries(): List<Entry> {
        val valuesDirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
        val found = mutableListOf<Entry>()
        for (dir in valuesDirs) {
            val stringsFile = File(dir, "strings.xml")
            if (!stringsFile.isFile) continue
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(stringsFile)
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val node = strings.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                found += Entry(stringsFile.path, name, node.textContent)
            }
            val plurals = doc.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val plural = plurals.item(i)
                val name = plural.attributes.getNamedItem("name")?.nodeValue ?: continue
                val items = plural.childNodes
                for (j in 0 until items.length) {
                    val item = items.item(j)
                    if (item.nodeName != "item") continue
                    found += Entry(stringsFile.path, "$name (${item.attributes.getNamedItem("quantity")?.nodeValue})", item.textContent)
                }
            }
        }
        return found
    }

    /** A conversion character Formatter accepts, wide enough to cover this app's actual usages. */
    private val conversions = "bBhHsScCdoxXeEfgGaAtTn"

    @Test
    fun `every format placeholder names a real conversion`() {
        assertTrue("no string resources found", entries().isNotEmpty())
        val problems = mutableListOf<String>()
        for (entry in entries()) {
            if (!entry.text.contains('%')) continue
            // Android string resources escape a literal percent as "%%",
            // exactly like Formatter itself; strip those pairs first so
            // they are not mistaken for the start of a placeholder.
            val withoutLiterals = entry.text.replace("%%", "")
            val bad = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?(.?)").findAll(withoutLiterals)
                .filter { it.value.isNotEmpty() }
                .filter { it.groupValues[3].firstOrNull()?.let { c -> c !in conversions } != false }
                .toList()
            if (bad.isNotEmpty()) {
                problems += "${entry.file}: ${entry.name} has an invalid format placeholder in ${entry.text.trim()}"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
