package dev.thorreader.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for the grouped lists (Author, Series, Genre, Type) being cut to half the
 * screen on the Thor: the list had `weight(1f, fill = false)` and a weighted Box under it, so it
 * never got more than half the height (395 of 791 px). Every library list must be the shared
 * FillList, which fills the space it is given.
 */
class LayoutRulesTest {
    private fun source(name: String): String {
        val rel = "src/main/java/dev/thorreader/app/ui/$name"
        val f = listOf(File(rel), File("app/$rel")).first { it.exists() }
        return f.readText().replace("\r", "")
    }

    private val libraryFiles = listOf("Library.kt", "ListManage.kt", "MetaScreens.kt")

    @Test
    fun libraryListsUseTheSharedFillingList() {
        libraryFiles.forEach { name ->
            val code = source(name)
            assertTrue("$name builds its own LazyColumn; use FillList", !code.contains("LazyColumn("))
            code.lines().filter { "FillList(" in it }.forEach { line ->
                assertTrue("$name: a list must not be capped or half-weighted: $line", "fill = false" !in line && "heightIn(max" !in line)
            }
        }
    }

    @Test
    fun everyListThatWasCutOffIsAFillList() {
        val lib = source("Library.kt")
        fun body(fn: String) = lib.substringAfter("private fun $fn(").substringBefore("\n}\n")
        assertTrue("flat lists / books in a group / Completed", "FillList(" in body("BookList"))
        assertTrue("Author / Series / Genre / Type", "FillList(" in body("CategoryList"))
        assertTrue("no weighted filler next to the grouped list", ".weight(" !in body("CategoryList"))
        val manage = source("ListManage.kt")
        assertTrue("Add author/series picker", "FillList(Modifier.weight(1f)" in manage)
    }

    @Test
    fun theSharedListFillsAndLeavesRoomAfterTheLastRow() {
        val code = source("FillList.kt")
        assertTrue(".fillMaxSize()" in code)
        assertTrue("contentPadding = PaddingValues(bottom = ListBottomPadding)" in code)
    }
}
