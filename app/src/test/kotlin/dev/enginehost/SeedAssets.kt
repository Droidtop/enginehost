package dev.enginehost

import java.io.File

/**
 * The seed registry these tests parse is the SHIPPED one -- the point of the
 * tests is that a registry edit that breaks classification fails CI before it
 * reaches a device. It is no longer checked in: `platformDatabaseSeed` copies
 * it out of the pinned vendor/droidtop-platforms submodule into the module's
 * generated assets, and the test task depends on that copy, so this is where
 * a test finds it.
 */
object SeedAssets {
    private val dir = File("build/generated/platformDatabase/assets")

    fun read(name: String): String = File(dir, name).readText()
}
