package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerPickerTest {
    private fun app(
        pkg: String,
        label: String = pkg,
        user: Boolean = true,
        home: Boolean = false,
        game: Boolean = false,
        blocked: Boolean = false,
    ) = CallerCandidate(pkg, label, user, home, game, blocked)

    @Test
    fun deviceAppsAreLeftOutUntilAsked() {
        val all = listOf(
            app("com.android.camera2", "Camera", user = false),
            app("com.android.deskclock", "Clock", user = false),
            app("com.example.notes", "Notes"),
        )
        assertEquals(listOf("Notes"), CallerPicker.relevant(all).map { it.label })
        assertEquals(listOf("Camera", "Clock", "Notes"), CallerPicker.everything(all).map { it.label })
    }

    @Test
    fun frontendsRankFirstThenGamesThenOtherAppsThenBlockedByDefault() {
        val all = listOf(
            app("a.browser", "Aardvark Browser", blocked = true),
            app("b.notes", "Notes"),
            app("c.game", "Some Game", game = true),
            app("d.front", "Zed Frontend", home = true),
            app("e.stock", "Stock Launcher", user = false, home = true),
        )
        assertEquals(
            listOf("Stock Launcher", "Zed Frontend", "Some Game", "Notes", "Aardvark Browser"),
            CallerPicker.relevant(all).map { it.label },
        )
    }

    @Test
    fun namesWithinAGroupSortIgnoringCase() {
        val all = listOf(app("p.b", "beta"), app("p.a", "Alpha"), app("p.c", "Charlie"))
        assertEquals(listOf("Alpha", "beta", "Charlie"), CallerPicker.relevant(all).map { it.label })
    }
}
