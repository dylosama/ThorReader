package dev.thorreader.app

import dev.thorreader.app.engine.BlankUnit
import dev.thorreader.app.engine.ChapterTitle
import dev.thorreader.app.engine.FixedUnit
import dev.thorreader.app.engine.HtmlHeading
import dev.thorreader.app.engine.LayoutConfig
import dev.thorreader.app.engine.RunningHeader
import dev.thorreader.app.engine.headerTextPx
import dev.thorreader.app.engine.PageView
import dev.thorreader.app.engine.Pagination
import dev.thorreader.app.engine.Part
import dev.thorreader.app.engine.ScreenMetrics
import dev.thorreader.app.engine.SliceUnit
import dev.thorreader.app.engine.SlotSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderTest {

    @Test
    fun chapterHeaders() {
        assertEquals("Ch. 3 · The Humming Ridge", ChapterTitle.format("Chapter 3: The Humming Ridge"))
        assertEquals("Ch. 3 · The Humming Ridge", ChapterTitle.format("3. The Humming Ridge"))
        assertEquals("Ch. 12", ChapterTitle.format("CHAPTER 12"))
        assertEquals("Ch. XII · Arrakis", ChapterTitle.format("Chapter XII - Arrakis"))
        assertEquals("Ch. Three · Home", ChapterTitle.format("chapter three: Home"))
        assertEquals("Prologue", ChapterTitle.format("Prologue"))
        assertEquals("1984", ChapterTitle.format("1984"))
        assertEquals("I Am Legend", ChapterTitle.format("I Am Legend"))
        assertEquals("Mix: Tape", ChapterTitle.format("Mix: Tape"))
        assertNull(ChapterTitle.format("   "))
        assertNull(ChapterTitle.format(null))
    }

    private fun cfg(header: Boolean) =
        LayoutConfig.build(ReadingMode.BOOK, false, ScreenMetrics.DEFAULT, 10f, 1, false, showHeader = header)

    @Test
    fun headerSpaceIsReservedOnlyWhenOnAndGrowsWithFont() {
        assertEquals(0, cfg(false).headerPx)
        val c = cfg(true)
        val text = headerTextPx(c.fontPt, c.pxPerPt)
        assertTrue(c.headerPx >= text * 1.7f) // room for the smaller header text
        val big = LayoutConfig.build(ReadingMode.BOOK, false, ScreenMetrics.DEFAULT, 20f, 1, false, showHeader = true)
        assertTrue(big.headerPx > c.headerPx)
    }

    // ---- choosing the header text --------------------------------------------------------

    private fun e(title: String, depth: Int, ch: Int, children: Boolean = false, page: Int = 0, y: Float = 0f) =
        RunningHeader.Entry(title, depth, ch, page, y, children)

    /** Sections 0-2 front matter, 3 = "Book 1" title page, 4+ real chapters. */
    private val real: (Int) -> Boolean? = { ch -> ch >= 4 && ch != 25 }

    @Test
    fun duneStylePartOnlyTocIsNumbered() {
        val toc = listOf(e("Book 1 DUNE", 0, 3), e("Book 2 MUAD'DIB", 0, 25))
        val r = RunningHeader.choose(toc, "Dune", 5, 0, 100f, { null }, real)
        assertEquals("Chapter 2", r.text)
        assertEquals(RunningHeader.Source.NUMBERED, r.source)
        // Numbering restarts in the next part (section 25 is its title page).
        assertEquals("Chapter 1", RunningHeader.choose(toc, "Dune", 26, 0, 0f, { null }, real).text)
        // The part's own title page gets no header, never the book title.
        assertNull(RunningHeader.choose(toc, "Dune", 3, 0, 0f, { null }, real).text)
    }

    @Test
    fun deepestTocEntryWins() {
        val toc = listOf(
            e("Part One", 0, 3, children = true),
            e("Chapter 1: The Start", 1, 4),
            e("Chapter 2", 1, 5),
            e("Chapter 3 - Mid-section", 1, 5, page = 0, y = 900f), // starts halfway through section 5
        )
        val r = RunningHeader.choose(toc, "Some Book", 5, 0, 10f, { null }, real)
        assertEquals("Ch. 2", r.text)
        assertEquals(RunningHeader.Source.TOC, r.source)
        assertEquals("Ch. 3 · Mid-section", RunningHeader.choose(toc, "Some Book", 5, 0, 950f, { null }, real).text)
        assertEquals("Ch. 1 · The Start", RunningHeader.choose(toc, "Some Book", 4, 0, 0f, { null }, real).text)
    }

    @Test
    fun sectionHeadingWhenTocOnlyNamesThePart() {
        val toc = listOf(e("Book 1 DUNE", 0, 3))
        val headings = mapOf(6 to "Chapter 7: Water", 7 to "DUNE")
        val r = RunningHeader.choose(toc, "Dune", 6, 0, 0f, { headings[it] }, real)
        assertEquals("Ch. 7 · Water", r.text)
        assertEquals(RunningHeader.Source.HEADING, r.source)
        // A heading that is just the book title is ignored -> numbered instead.
        assertEquals("Chapter 4", RunningHeader.choose(toc, "Dune", 7, 0, 0f, { headings[it] }, real).text)
    }

    @Test
    fun noTocNumbersRealSectionsFromTheStart() {
        assertEquals("Chapter 1", RunningHeader.choose(emptyList(), "X", 4, 0, 0f, { null }, real).text)
        assertEquals("Chapter 3", RunningHeader.choose(emptyList(), "X", 6, 0, 0f, { null }, real).text)
        // Unknown yet (still laying out): no header rather than a wrong number.
        assertNull(RunningHeader.choose(emptyList(), "X", 6, 0, 0f, { null }, { ch -> if (ch == 5) null else ch >= 4 }).text)
    }

    @Test
    fun htmlHeadings() {
        assertEquals(
            "Chapter 3: The Humming Ridge",
            HtmlHeading.find("<html><body><div class='c'><h2 class=\"num\">Chapter 3</h2>\n<h3>The&nbsp;Humming <i>Ridge</i></h3><p>Text.</p></div></body></html>"),
        )
        assertEquals("Prologue", HtmlHeading.find("<body><h1>Prologue</h1><p>a</p></body>"))
        // A heading far into the text is not "at the start".
        assertNull(HtmlHeading.find("<body><p>" + "x".repeat(800) + "</p><h2>Later</h2></body>"))
        assertNull(HtmlHeading.find("<body><p>No headings here.</p></body>"))
    }

    private fun slice(ch: Int, y0: Float, label: String? = null) =
        SliceUnit(ch, 0, y0, y0 + 10f, SlotSize(1080, 1240), 0f, 0f, label, false)

    @Test
    fun quickNavigationLookups() {
        val views = listOf(
            PageView(listOf(slice(0, 0f, "Prologue"), slice(0, 10f, "Prologue"))),
            PageView(listOf(slice(1, 0f, "Ch. 1"), slice(1, 10f, "Ch. 1"))),
            PageView(listOf(slice(1, 20f, "Ch. 1"), BlankUnit)),
            PageView(listOf(slice(2, 0f, "Ch. 2"), slice(2, 10f, "Ch. 2"))),
        )
        val p = Pagination(cfg(true), views, 1, "x", complete = true, chapterCount = 3)
        assertEquals(7, p.pageCount)
        assertEquals(1, p.viewOfPage(3))
        assertEquals(2, p.viewOfPage(5))
        assertEquals(3, p.viewOfPage(6))
        assertNull(p.viewOfPage(99))
        assertEquals(5, p.pageNumberOfView(2))
        assertEquals(listOf("Prologue" to 0, "Ch. 1" to 1, "Ch. 2" to 3), p.chapters)
        assertEquals(1, p.chapterIndexAt(2))

        val fixed = Pagination(cfg(true), paginateFixedForTest(), 2)
        assertEquals(10, fixed.pageCount)
        assertEquals(4, fixed.viewOfPage(5)) // single-page views: page 5 is view 4
    }

    private fun paginateFixedForTest() =
        dev.thorreader.app.engine.paginateFixed(10, { false }, { null }, LayoutConfig.build(ReadingMode.BOTTOM, false, ScreenMetrics.DEFAULT, 10f, 1, false))

    @Test
    fun pageNumbersCountWholeBookAndSkipBlanks() {
        val views = listOf(
            PageView(listOf(slice(0, 0f), slice(0, 10f))),
            PageView(listOf(slice(0, 20f), BlankUnit)), // chapter ends; next chapter starts a new view
            PageView(listOf(slice(1, 0f), slice(1, 10f))),
        )
        val complete = Pagination(cfg(true), views, 1, "x", complete = true, chapterCount = 2)
        assertEquals(1, complete.pageNumber(views[0].units[0]))
        assertEquals(3, complete.pageNumber(views[1].units[0]))
        assertNull(complete.pageNumber(BlankUnit))
        assertEquals(4, complete.pageNumber(views[2].units[0]))
        assertTrue(complete.isLastView(2))

        // While still laying out, numbers aren't known yet and the end isn't the end.
        val partial = Pagination(cfg(true), views, 2, "x", complete = false, chapterCount = 5)
        assertNull(partial.pageNumber(views[0].units[0]))
        assertFalse(partial.isLastView(2))

        // Comics/PDF: the page number is the page itself, also on spread halves.
        assertEquals(7, complete.pageNumber(FixedUnit(6, Part.LEFT)))
    }
}
