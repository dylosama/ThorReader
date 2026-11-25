package dev.thorreader.app

import dev.thorreader.app.ui.gridColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Library grid view: column count and the shared filling grid. */
class GridTest {
    @Test
    fun aboutSevenCoversAcrossTheTopScreen() {
        // Top screen 1920 px at density 2.30625 = 832 dp, minus the 16 dp padding on each side.
        assertEquals(7, gridColumns(800f))
        assertTrue(gridColumns(300f) >= 3)
        assertTrue(gridColumns(3000f) <= 10)
    }

    @Test
    fun bothBookViewsFillTheSpace() {
        val rel = "src/main/java/dev/thorreader/app/ui/"
        fun src(n: String) = listOf(File(rel + n), File("app/$rel$n")).first { it.exists() }.readText().replace("\r", "")
        val bookList = src("Library.kt").substringAfter("private fun BookList(").substringBefore("\n}\n")
        assertTrue("list view", "FillList(" in bookList)
        assertTrue("grid view", "BookGrid(" in bookList)
        assertTrue("the grid is the shared FillGrid", "FillGrid(" in src("LibraryGrid.kt"))
    }
}
