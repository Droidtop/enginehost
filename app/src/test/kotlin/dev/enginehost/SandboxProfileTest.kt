package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A learning run's profile (docs/engine-sandbox.md, "Learning mode"). */
class SandboxProfileTest {
    private val roots = listOf(
        "\$GAME" to "/storage/emulated/0/Games/Thermomorph",
        "\$SAVE" to "/storage/emulated/0/Enginehost/saves",
        "\$EXTERNAL" to "/storage/emulated/0",
        "\$APP" to "/data/user/0/dev.enginehost",
        "\$CACHE" to "/data/user/0/dev.enginehost/cache",
    )

    private fun profile() = SandboxProfile("dev.enginehost.love2d.11.v1", "0.9.5", "3F0C1A7A", "x86_64", roots)

    @Test
    fun `paths are named by the longest folder they are in`() {
        val p = profile()
        assertEquals("\$GAME/thermomorph.love", p.generalise("/storage/emulated/0/Games/Thermomorph/thermomorph.love"))
        assertEquals("\$EXTERNAL/Music", p.generalise("/storage/emulated/0/Music"))
        assertEquals("\$CACHE/shaders", p.generalise("/data/user/0/dev.enginehost/cache/shaders"))
        assertEquals("\$APP/files", p.generalise("/data/user/0/dev.enginehost/files"))
        assertEquals("\$GAME", p.generalise("/storage/emulated/0/Games/Thermomorph"))
        // A sibling whose name only starts like a root is not inside it.
        assertEquals("\$EXTERNAL/Games/Thermomorph2", p.generalise("/storage/emulated/0/Games/Thermomorph2"))
        assertEquals("/mnt/expand", p.generalise("/mnt/expand"))
    }

    @Test
    fun `records are counted, and only a new one says so`() {
        val p = profile()
        assertTrue(p.add("open", "/mnt/expand", "/mnt/expand", 1, -13))
        assertFalse(p.add("open", "/mnt/expand", "/mnt/expand", 1, -13))
        assertTrue(p.add("stat", "/mnt/expand", "", 0, -13))
        assertTrue(p.add("syscall", "socket(0x2, 0x1, 0)", "", 0, -13))
        assertFalse(p.add("syscall", "socket(0xa, 0x1, 0)", "", 0, -13))
        val json = p.toJson()
        val paths = json.getJSONArray("paths")
        assertEquals(2, paths.length())
        val open = (0 until paths.length()).map { paths.getJSONObject(it) }.single { it.getString("op") == "open" }
        assertEquals(2, open.getInt("count"))
        assertEquals("read", open.getString("need"))
        val call = json.getJSONArray("calls").getJSONObject(0)
        assertEquals("socket", call.getString("call"))
        assertEquals("EACCES", call.getString("would"))
        assertEquals(2, call.getInt("count"))
        assertEquals(2, call.getJSONArray("examples").length())
    }

    @Test
    fun `a later run adds to the profile of the same build`() {
        val first = profile()
        first.startRun("Thermomorph")
        first.add("open", "/storage/emulated/0/Games/Thermomorph/a", "/storage/emulated/0/Games/Thermomorph/a", 2, -13)
        first.add("syscall", "kill(0x1, 0)", "", 0, -1)
        val second = profile()
        second.merge(JSONObject(first.toJson().toString()))
        second.startRun("black-screen")
        second.add("open", "/storage/emulated/0/Games/Thermomorph/a", "/storage/emulated/0/Games/Thermomorph/a", 2, -13)
        val json = second.toJson()
        assertEquals(2, json.getInt("runs"))
        assertEquals(listOf("Thermomorph", "black-screen"), (0 until 2).map { json.getJSONArray("games").getString(it) })
        val path = json.getJSONArray("paths").getJSONObject(0)
        assertEquals("\$GAME/a", path.getString("path"))
        assertEquals("write", path.getString("need"))
        assertEquals(2, path.getInt("count"))
        assertEquals("EPERM", json.getJSONArray("calls").getJSONObject(0).getString("would"))
    }
}
