package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionBuildTest {
    private fun v(raw: String) = Version.parse(raw)

    @Test
    fun parsesTheBuildCounter() {
        assertEquals(listOf(1, 0, 0), v("1.0.0-57").parts)
        assertEquals(57, v("1.0.0-57").build)
        assertNull(v("1.0.0").build)
        assertEquals("1.0.0-57", v("1.0.0-57").toString())
    }

    @Test
    fun ordersByDeclaredVersionThenBuild() {
        assertTrue(v("1.0.0-2") > v("1.0.0-1"))
        assertTrue(v("1.0.0-10") > v("1.0.0-9"))
        assertTrue(v("1.0.1-1") > v("1.0.0-99"))
        assertTrue(v("1.1.0-1") > v("1.0.9-500"))
    }

    @Test
    fun aBuildOfAVersionIsNotBelowThatVersion() {
        assertTrue(v("1.0.0-1") >= v("1.0.0"))
        assertTrue(v("1.0.0-1") > v("1.0"))
        assertEquals(v("1.0.5"), v("1.0.5-0"))
    }

    @Test
    fun legacyRunNumberVersionsReadAsBuildsOfTheMinor() {
        val p = Version.Companion::parsePlugin
        assertEquals(p("1.0.0-57"), p("1.0.57"))
        assertEquals(p("0.9.0-12"), p("0.9.12"))
        assertTrue(p("1.0.58") > p("1.0.57"))
        assertTrue(p("1.0.1-1") > p("1.0.57"))
        assertTrue(p("0.9.1-1") > p("0.9.12"))
        assertTrue(p("1.0.57") > p("1.0.0-56"))
        assertTrue(p("1.1.0-1") > p("1.0.57"))
    }

    @Test
    fun parsePluginLeavesOtherVersionsAlone() {
        assertEquals(listOf(1, 0, 1), Version.parsePlugin("1.0.1-1").parts)
        assertEquals(listOf(1, 0, 0), Version.parsePlugin("1.0.0").parts)
        assertNull(Version.parsePlugin("1.0.0").build)
        assertEquals(listOf(1, 2), Version.parsePlugin("1.2").parts)
        assertEquals(listOf(1, 2, 3, 4), Version.parsePlugin("1.2.3.4").parts)
    }

    @Test
    fun engineVersionsAreNotReinterpreted() {
        assertEquals(listOf(4, 5, 1), v("4.5.1").parts)
        assertNull(v("4.5.1").build)
        assertTrue(v("4.5.1") > v("4.5.0-9"))
    }

    @Test
    fun allowlistReadsLegacyPluginVersions() {
        assertTrue(VersionConstraint.parse("1.0.57").matches(Version.parsePlugin("1.0.0-57")))
        assertTrue(VersionConstraint.parse("1.0.50-1.0.60").matches(Version.parsePlugin("1.0.57")))
    }

    @Test
    fun rejectsMalformedBuilds() {
        for (bad in listOf("1.0.0-", "1.0.0-a", "1.0.0--1", "-1", "1.0.0-1-2", "1.0.0-1.2")) {
            assertFalse(bad, runCatching { v(bad) }.isSuccess)
        }
    }

    @Test
    fun constraintsKeepRangesAndAcceptBuilds() {
        val c = VersionConstraint.parse("1.0.0,1.2.0-1.4.0,2.0.0-3")
        assertTrue(c.matches(v("1.0.0")))
        assertTrue(c.matches(v("1.3.5")))
        assertTrue(c.matches(v("2.0.0-3")))
        assertFalse(c.matches(v("2.0.0-4")))
        assertFalse(c.matches(v("1.5.0")))
        val builds = VersionConstraint.parse("1.0.0-3-1.0.0-9")
        assertTrue(builds.matches(v("1.0.0-5")))
        assertFalse(builds.matches(v("1.0.0-10")))
    }

    @Test
    fun displaysTheFullVersionString() {
        assertEquals("1.0.0-57", PluginVersions.display(v("1.0.0-57")))
        assertEquals("1.2.1-3", PluginVersions.display(v("1.2.1-3")))
        assertEquals("1.0.0-21", PluginVersions.display(Version.parsePlugin("1.0.21")))
        assertEquals("1.0.1", PluginVersions.display(v("1.0.1")))
    }
}
