package dev.enginehost

import dev.droidtop.shared.version.Version
import org.junit.Assert.assertEquals
import org.junit.Test

/** What stays in Enginehost of the version tests: the display of a plugin version. The ordering tests run in droidtop-shared. */
class PluginVersionsTest {
    private fun v(raw: String) = Version.parse(raw)

    @Test
    fun displaysTheFullVersionString() {
        assertEquals("1.0.0-57", PluginVersions.display(v("1.0.0-57")))
        assertEquals("1.2.1-3", PluginVersions.display(v("1.2.1-3")))
        assertEquals("1.0.0-21", PluginVersions.display(Version.parsePlugin("1.0.21")))
        assertEquals("1.0.1", PluginVersions.display(v("1.0.1")))
    }
}
