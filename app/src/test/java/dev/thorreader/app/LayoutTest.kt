package dev.thorreader.app

import dev.thorreader.app.engine.Anchor
import dev.thorreader.app.engine.BlankUnit
import dev.thorreader.app.engine.FixedUnit
import dev.thorreader.app.engine.LayoutConfig
import dev.thorreader.app.engine.Pagination
import dev.thorreader.app.engine.Part
import dev.thorreader.app.engine.ScreenMetrics
import dev.thorreader.app.engine.SlotSize
import dev.thorreader.app.engine.bookUnitIndexFor
import dev.thorreader.app.engine.paginateFixed
import dev.thorreader.app.importer.FilenameParser
import dev.thorreader.app.io.NaturalOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val screens = ScreenMetrics.DEFAULT
    private val topPage = SlotSize(1080, 1920)
    private val bottomPage = SlotSize(1080, 1240)

    private fun cfg(mode: ReadingMode, rtl: Boolean = false, two: Boolean = false) =
        LayoutConfig.build(mode, rtl, screens, 10f, 1, two)

    private fun f(p: Int, part: Part = Part.WHOLE) = FixedUnit(p, part)

    @Test
    fun bookModesPutTheRightPageOnEachScreenAtFullSize() {
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.BOOK_REVERSED)) {
            for (rtl in listOf(false, true)) {
                val c = cfg(mode, rtl)
                val topIdx = bookUnitIndexFor(Screen.TOP, mode, rtl)
                val bottomIdx = bookUnitIndexFor(Screen.BOTTOM, mode, rtl)
                assertNotEquals("$mode rtl=$rtl", topIdx, bottomIdx)
                // Each screen's page is laid out for that screen's full area.
                assertEquals("$mode rtl=$rtl top", topPage, c.slots[topIdx])
                assertEquals("$mode rtl=$rtl bottom", bottomPage, c.slots[bottomIdx])
                // The physically LEFT screen shows the first page (LTR) or second page (RTL).
                val leftScreen = if (mode == ReadingMode.BOOK) Screen.TOP else Screen.BOTTOM
                assertEquals("$mode rtl=$rtl left", if (rtl) 1 else 0, bookUnitIndexFor(leftScreen, mode, rtl))
            }
        }
    }

    @Test
    fun modeRotationsAreOpposite() {
        assertEquals(90f, ReadingMode.BOOK.contentRotation)
        assertEquals(-90f, ReadingMode.BOOK_REVERSED.contentRotation)
        assertEquals(0f, ReadingMode.TOP.contentRotation)
        assertEquals(0f, ReadingMode.BOTTOM.contentRotation)
    }

    @Test
    fun singleScreenModesUseScreenHalvesOrWholeScreen() {
        assertEquals(listOf(SlotSize(960, 1080), SlotSize(960, 1080)), cfg(ReadingMode.TOP).slots)
        assertEquals(listOf(SlotSize(1240, 1080)), cfg(ReadingMode.BOTTOM).slots)
        assertEquals(listOf(SlotSize(620, 1080), SlotSize(620, 1080)), cfg(ReadingMode.BOTTOM, two = true).slots)
    }

    @Test
    fun bookModeSplitsSpreadsInReadingOrder() {
        // Page 1 is a double-page spread.
        val ltr = paginateFixed(3, { it == 1 }, { null }, cfg(ReadingMode.BOOK)).map { it.units }
        assertEquals(
            listOf(listOf(f(0), BlankUnit), listOf(f(1, Part.LEFT), f(1, Part.RIGHT)), listOf(f(2), BlankUnit)),
            ltr,
        )
        val rtl = paginateFixed(3, { it == 1 }, { null }, cfg(ReadingMode.BOOK, rtl = true)).map { it.units }
        assertEquals(
            listOf(listOf(f(0), BlankUnit), listOf(f(1, Part.RIGHT), f(1, Part.LEFT)), listOf(f(2), BlankUnit)),
            rtl,
        )
        // In RTL the physically left screen must show the LEFT half.
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.BOOK_REVERSED)) {
            val leftScreen = if (mode == ReadingMode.BOOK) Screen.TOP else Screen.BOTTOM
            val idx = bookUnitIndexFor(leftScreen, mode, rtl = true)
            assertEquals(f(1, Part.LEFT), rtl[1][idx])
        }
    }

    @Test
    fun spreadsAlreadyAlignedNeedNoBlank() {
        val views = paginateFixed(3, { it == 2 }, { null }, cfg(ReadingMode.BOOK)).map { it.units }
        assertEquals(listOf(listOf(f(0), f(1)), listOf(f(2, Part.LEFT), f(2, Part.RIGHT))), views)
    }

    @Test
    fun twoPageModesShowSpreadsWhole() {
        val views = paginateFixed(4, { it == 1 }, { null }, cfg(ReadingMode.TOP))
        assertEquals(listOf(f(0), BlankUnit), views[0].units)
        assertEquals(listOf(f(1)), views[1].units)
        assertTrue(views[1].wide)
        assertEquals(listOf(f(2), f(3)), views[2].units)
    }

    @Test
    fun positionSurvivesModeChange() {
        val book = Pagination(cfg(ReadingMode.BOOK), paginateFixed(10, { false }, { null }, cfg(ReadingMode.BOOK)), 1)
        val single = Pagination(cfg(ReadingMode.BOTTOM), paginateFixed(10, { false }, { null }, cfg(ReadingMode.BOTTOM)), 2)
        val anchor = book.anchorOf(3)!! // pages 6+7
        assertEquals(Anchor(6, 0f), anchor)
        assertEquals(6, single.findView(anchor))
        assertEquals(3, book.findView(single.anchorOf(7)!!))
    }

    @Test
    fun comicPagesSortNaturally() {
        val sorted = listOf("p10.jpg", "p2.jpg", "P1.jpg", "p02b.jpg", "cover.jpg").sortedWith(NaturalOrder)
        assertEquals(listOf("cover.jpg", "P1.jpg", "p2.jpg", "p02b.jpg", "p10.jpg"), sorted)
    }

    @Test
    fun filenameFallbacks() {
        val a = FilenameParser.parse("Terry Pratchett - Guards! Guards!.epub", comic = false)
        assertEquals("Terry Pratchett", a.author)
        assertEquals("Guards! Guards!", a.title)
        val c = FilenameParser.parse("Saga 012 (2013) (Digital).cbz", comic = true)
        assertEquals("Saga", c.series)
        assertEquals(12f, c.number)
        val v = FilenameParser.parse("One_Piece_v03.cbr", comic = true)
        assertEquals("One Piece", v.series)
        assertEquals(3f, v.number)
    }
}
