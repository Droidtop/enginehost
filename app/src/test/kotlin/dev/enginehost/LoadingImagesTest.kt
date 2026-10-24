package dev.enginehost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where each engine family's own loading picture is found (Droidtop/tracker#640). */
class LoadingImagesTest {
    private val root: File = Files.createTempDirectory("loading").toFile().canonicalFile

    private fun file(path: String, text: String = "x"): File =
        File(root, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun still(art: LoadingArt?): LoadingArt.Still = art as LoadingArt.Still

    // ---- Ren'Py ---------------------------------------------------------

    @Test
    fun renpyAndroidPresplashBesideTheGameIsFirst() {
        val android = file("android-presplash.jpg")
        file("game/presplash.png")
        assertEquals(android, still(LoadingImages.find(root, "renpy", null)).file)
    }

    @Test
    fun renpyFallsBackToThePresplashInTheGameFolder() {
        val presplash = file("game/presplash.png")
        assertEquals(presplash, still(LoadingImages.find(root, "renpy", null)).file)
    }

    @Test
    fun renpyAndroidPresplashTakesAnyOfRenpysExtensions() {
        for (extension in listOf("png", "jpg", "webp")) {
            val folder = Files.createTempDirectory("ext").toFile()
            val picture = File(folder, "android-presplash.$extension").also { it.writeText("x") }
            assertEquals(picture, still(LoadingImages.find(folder, "renpy", null)).file)
        }
    }

    @Test
    fun renpyForegroundAndBackgroundMakeAProgressFill() {
        val background = file("game/presplash_background.png")
        val foreground = file("game/presplash_foreground.png")
        file("game/presplash.png")
        assertEquals(LoadingArt.Progress(background, foreground), LoadingImages.find(root, "renpy", null))
    }

    @Test
    fun renpyProgressFillAlsoTakesTheHyphenatedNames() {
        val background = file("game/presplash-background.png")
        val foreground = file("game/presplash-foreground.png")
        assertEquals(LoadingArt.Progress(background, foreground), LoadingImages.find(root, "renpy", null))
    }

    @Test
    fun renpyOneHalfOfAProgressFillIsNotAFill() {
        file("game/presplash_foreground.png")
        val presplash = file("game/presplash.png")
        assertEquals(presplash, still(LoadingImages.find(root, "renpy", null)).file)
    }

    @Test
    fun renpyFindsFilesWhateverTheirCase() {
        val presplash = file("Game/PreSplash.PNG")
        assertEquals(presplash, still(LoadingImages.find(root, "renpy", null)).file)
    }

    @Test
    fun renpyWithNoPresplashHasNone() {
        file("game/script.rpy")
        file("game/gui/window_icon.png")
        assertNull(LoadingImages.find(root, "renpy", null))
    }

    @Test
    fun anEmptyPresplashIsNotOne() {
        file("game/presplash.png", "")
        assertNull(LoadingImages.find(root, "renpy", null))
    }

    // ---- Godot ----------------------------------------------------------

    private fun godotProject(application: String) = file(
        "project.godot",
        "config_version=5\n\n[application]\n\n$application\n\n[display]\n\nwindow/size/viewport_width=1280\n",
    )

    @Test
    fun godotBootSplashImageIsResolvedInsideTheGameFolder() {
        godotProject("config/name=\"Game\"\nboot_splash/image=\"res://art/splash.png\"")
        val splash = file("art/splash.png")
        val art = still(LoadingImages.find(root, "godot", "4"))
        assertEquals(splash, art.file)
        assertNull(art.background)
    }

    @Test
    fun godotBootSplashColourIsRead() {
        godotProject("boot_splash/image=\"res://splash.png\"\nboot_splash/bg_color=Color(0.5, 0, 1, 1)")
        file("splash.png")
        assertEquals(0xFF8000FF.toInt(), still(LoadingImages.find(root, "godot", "4")).background)
    }

    @Test
    fun godotImageInsideThePackIsSkipped() {
        // project.godot names it, but the file is only in the .pck.
        godotProject("boot_splash/image=\"res://art/splash.png\"")
        file("game.pck", "GDPC")
        assertNull(LoadingImages.find(root, "godot", "4"))
    }

    @Test
    fun godotWithNoProjectFileIsSkipped() {
        file("game.pck", "GDPC")
        file("splash.png")
        assertNull(LoadingImages.find(root, "godot", "4"))
    }

    @Test
    fun godotWithTheSplashTurnedOffIsSkipped() {
        godotProject("boot_splash/image=\"res://splash.png\"\nboot_splash/show_image=false")
        file("splash.png")
        assertNull(LoadingImages.find(root, "godot", "4"))
    }

    @Test
    fun godotSettingOutsideTheApplicationSectionIsNotTheSplash() {
        file("project.godot", "[rendering]\nboot_splash/image=\"res://splash.png\"\n")
        file("splash.png")
        assertNull(LoadingImages.find(root, "godot", "4"))
    }

    @Test
    fun godotImageThatLeavesTheFolderIsRefused() {
        val game = File(root, "inner").also { it.mkdirs() }
        File(game, "project.godot").writeText("[application]\nboot_splash/image=\"res://../outside.png\"\n")
        file("outside.png")
        assertNull(LoadingImages.find(game, "godot", "4"))
    }

    @Test
    fun godotImageThatIsNotAResourcePathIsSkipped() {
        godotProject("boot_splash/image=\"/sdcard/splash.png\"")
        assertNull(LoadingImages.find(root, "godot", "4"))
    }

    @Test
    fun godotColoursParseOnlyAsColours() {
        assertEquals(0xFF000000.toInt(), GodotProjectSettings.color("Color(0, 0, 0)"))
        assertEquals(0xFFFFFFFF.toInt(), GodotProjectSettings.color("Color(2, 1, 1, 0.5)"))
        assertNull(GodotProjectSettings.color("\"#ffffff\""))
        assertNull(GodotProjectSettings.color("Color(a, b, c)"))
    }

    // ---- RPG Maker ------------------------------------------------------

    @Test
    fun rpgMakerMvAndMzUseImgSystemLoading() {
        val loading = file("img/system/Loading.png")
        assertEquals(loading, still(LoadingImages.find(root, "rpgmaker", "mz")).file)
        assertEquals(loading, still(LoadingImages.find(root, "rpgmaker", "mv")).file)
    }

    @Test
    fun rpgMakerMvDeployedUnderWwwIsFound() {
        val loading = file("www/img/system/Loading.png")
        assertEquals(loading, still(LoadingImages.find(root, "rpgmaker", "mv")).file)
    }

    @Test
    fun rpgMakerEncryptedLoadingIsSkipped() {
        file("img/system/Loading.rpgmvp")
        assertNull(LoadingImages.find(root, "rpgmaker", "mv"))
    }

    @Test
    fun rpgMakerOlderThanMvHasNone() {
        file("img/system/Loading.png")
        assertNull(LoadingImages.find(root, "rpgmaker", "vxace"))
        assertNull(LoadingImages.find(root, "rpgmaker", null))
    }

    // ---- KiriKiri and the rest -----------------------------------------

    @Test
    fun kirikiriHasNoPlainFileLocationAndIsSkipped() {
        file("data.xp3")
        file("startup.png")
        file("game/presplash.png")
        assertNull(LoadingImages.find(root, "kirikiri2", "default"))
        assertNull(LoadingImages.find(root, "kirikiri", null))
    }

    @Test
    fun anUnreadableFolderIsNoArtAndNoCrash() {
        assertNull(LoadingImages.find(File(root, "missing"), "renpy", null))
        assertNull(LoadingImages.find(File(root, "missing"), "godot", null))
    }

    @Test
    fun theEstimatedProgressRisesAndNeverFills() {
        val steps = listOf(0L, 1_000L, 5_000L, 20_000L, 600_000L).map(LoadingProgress::estimate)
        assertEquals(steps.sorted(), steps)
        assertTrue(steps.last() < 1f)
    }
}
