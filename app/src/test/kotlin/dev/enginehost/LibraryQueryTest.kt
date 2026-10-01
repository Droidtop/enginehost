package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryQueryTest {
    @Test
    fun `home lists the added games and an unfiltered library says so`() {
        val sql = LibraryQuery.build(LibraryFilter())
        assertEquals("added = 1", sql.where)
        assertTrue(sql.args.isEmpty())
        assertTrue(LibraryFilter().unfiltered)
        assertFalse(LibraryFilter(engine = "renpy").unfiltered)
        assertFalse(LibraryFilter(text = "dragon").unfiltered)
        assertTrue(LibraryFilter(text = "   ").unfiltered)
    }

    @Test
    fun `a scan scope is a range over the primary key and no root matches nothing`() {
        val sql = LibraryQuery.build(LibraryFilter(scope = LibraryScope.UNDER_ROOT, root = "/storage/Games"))
        assertEquals("(path = ? OR (path >= ? AND path < ?))", sql.where)
        assertEquals(listOf("/storage/Games", "/storage/Games/", "/storage/Games0"), sql.args)
        assertEquals("0", LibraryQuery.build(LibraryFilter(scope = LibraryScope.UNDER_ROOT)).where)
        // A trailing slash on the root makes the same range.
        assertEquals(sql.args, LibraryQuery.build(LibraryFilter(scope = LibraryScope.UNDER_ROOT, root = "/storage/Games/")).args.let { listOf("/storage/Games", it[1], it[2]) })
    }

    @Test
    fun `engine platform and folder filters are indexed column predicates`() {
        val sql = LibraryQuery.build(
            LibraryFilter(engine = "renpy", platform = BuildPlatform.WINDOWS, folder = "/storage/Games/Novels"),
        )
        assertEquals("added = 1 AND engine = ? AND (platforms & ?) != 0 AND parent = ?", sql.where)
        assertEquals(listOf("renpy", BuildPlatform.WINDOWS.bit.toString(), "/storage/Games/Novels"), sql.args)
    }

    @Test
    fun `builds with no recognised engine are filtered by a null engine`() {
        val sql = LibraryQuery.build(LibraryFilter(engine = LibraryFilter.NO_ENGINE))
        assertEquals("added = 1 AND engine IS NULL", sql.where)
        assertTrue(sql.args.isEmpty())
    }

    @Test
    fun `every word of a search must match and like wildcards in it are escaped`() {
        val sql = LibraryQuery.build(LibraryFilter(text = "Dragon  50%_off"))
        assertEquals(
            "added = 1 AND name_key LIKE ? ESCAPE '\\' AND name_key LIKE ? ESCAPE '\\'",
            sql.where,
        )
        assertEquals(listOf("%dragon%", "%50\\%\\_off%"), sql.args)
    }

    @Test
    fun `status is not part of the query because it is decided per engine line`() {
        val sql = LibraryQuery.build(LibraryFilter(support = Support.RUNS_HERE))
        assertEquals("added = 1", sql.where)
    }

    @Test
    fun `each sort order is an indexed column with a stable tie break`() {
        assertEquals("name_key ASC, path ASC", LibraryQuery.build(LibraryFilter(sort = SortOrder.NAME)).orderBy)
        assertEquals("added_at DESC, name_key ASC, path ASC", LibraryQuery.build(LibraryFilter(sort = SortOrder.RECENTLY_ADDED)).orderBy)
        assertEquals("played_at DESC, name_key ASC, path ASC", LibraryQuery.build(LibraryFilter(sort = SortOrder.RECENTLY_PLAYED)).orderBy)
        assertEquals("size_bytes DESC, name_key ASC, path ASC", LibraryQuery.build(LibraryFilter(sort = SortOrder.SIZE)).orderBy)
    }

    @Test
    fun `stored requirements come back as versions and a bad pair is skipped`() {
        val parsed = SupportResolver.parseRequirements("ruby=1.9.2;spine=4.2;broken;empty=;bad=x.y")
        assertEquals(setOf("ruby", "spine"), parsed.keys)
        assertEquals(Version.parse("1.9.2"), parsed["ruby"])
        assertTrue(SupportResolver.parseRequirements("").isEmpty())
    }

    @Test
    fun `platform bits compose and decompose`() {
        val mask = BuildPlatform.mask(BuildPlatform.WINDOWS, BuildPlatform.LINUX)
        assertEquals(listOf(BuildPlatform.WINDOWS, BuildPlatform.LINUX), BuildPlatform.of(mask))
        assertEquals(Confidence.HIGH, Confidence.ofRank(Confidence.HIGH.rank))
        assertEquals(Confidence.LOW, Confidence.ofRank(99))
    }
}

class FolderSizeTest {
    private fun tempRoot(): File = createTempDir(prefix = "size-test").also { it.deleteOnExit() }

    @Test
    fun `a folder is the sum of everything under it`() {
        val root = tempRoot()
        File(root, "a").writeBytes(ByteArray(100))
        File(root, "sub/deeper").apply { mkdirs() }
        File(root, "sub/deeper/b").writeBytes(ByteArray(250))
        assertEquals(350L, FolderSize.measure(root))
    }

    @Test
    fun `a file is its own length and a missing path is empty`() {
        val root = tempRoot()
        val file = File(root, "archive.zip").apply { writeBytes(ByteArray(64)) }
        assertEquals(64L, FolderSize.measure(file))
        assertEquals(0L, FolderSize.measure(File(root, "nothing")))
    }

    @Test
    fun `cancelling stops the walk`() {
        val root = tempRoot()
        repeat(20) { File(root, "f$it").writeBytes(ByteArray(10)) }
        assertTrue(FolderSize.measure(root) { true } < 200L)
    }
}
