package dev.thorreader.app

import dev.thorreader.app.convert.ConvBook
import dev.thorreader.app.convert.EpubWriter
import dev.thorreader.app.convert.MobiFile
import dev.thorreader.app.convert.RtfConverter
import dev.thorreader.app.data.Book
import dev.thorreader.app.engine.LayoutConfig
import dev.thorreader.app.engine.LinePaginator
import dev.thorreader.app.engine.ScreenMetrics
import dev.thorreader.app.engine.bookUnitIndexFor
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.ui.LibraryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Tests for v0.2.0: per-screen text size, Kindle/RTF/EPUB converters, library logic. */
class V020Test {

    // ---- 1. separate text size per screen ------------------------------------------------

    private fun cfg(mode: ReadingMode, rtl: Boolean, top: Float, bottom: Float) =
        LayoutConfig.build(mode, rtl, ScreenMetrics.DEFAULT, top, 1, false, showHeader = true, bottomFontPt = bottom)

    @Test
    fun eachScreenGetsItsOwnSizeInBookModes() {
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.BOOK_REVERSED)) for (rtl in listOf(false, true)) {
            val c = cfg(mode, rtl, top = 9f, bottom = 10f)
            assertEquals(9f, c.slotFontPt[bookUnitIndexFor(Screen.TOP, mode, rtl)])
            assertEquals(10f, c.slotFontPt[bookUnitIndexFor(Screen.BOTTOM, mode, rtl)])
            assertEquals("laid out at the larger size", 10f, c.fontPt)
            assertEquals(0.9f, c.slotScale(bookUnitIndexFor(Screen.TOP, mode, rtl)), 1e-4f)
            assertEquals(1f, c.slotScale(bookUnitIndexFor(Screen.BOTTOM, mode, rtl)), 1e-4f)
        }
    }

    @Test
    fun modes3And4UseOneSize() {
        val top = cfg(ReadingMode.TOP, false, 9f, 12f)
        assertEquals(listOf(9f, 9f), top.slotFontPt)
        val bottom = cfg(ReadingMode.BOTTOM, false, 9f, 12f)
        assertEquals(listOf(12f), bottom.slotFontPt)
        assertEquals(12f, bottom.fontPt)
    }

    @Test
    fun sameSizeIsUnchangedFromBefore() {
        val c = cfg(ReadingMode.BOOK, false, 10f, 10f)
        assertEquals(1f, c.slotScale(0))
        assertEquals(1f, c.slotScale(1))
        // Full width column, exactly as in v0.1.0.
        assertEquals((1080 - 2 * c.marginPx) / c.pxPerPt, c.textWidthPt(), 1e-3f)
    }

    @Test
    fun smallerTextScreenFitsTheSameColumnAndMoreLines() {
        val c = cfg(ReadingMode.BOOK, false, top = 9f, bottom = 10f) // slot 0 = top
        // Column limited by the larger-text (bottom) screen; on top it is 90% as wide in pixels.
        val colTopPx = c.textWidthPt() * c.pxPerPt * c.slotScale(0)
        assertTrue(colTopPx <= 1080 - 2 * c.marginPx + 0.5f)
        val colBottomPx = c.textWidthPt() * c.pxPerPt * c.slotScale(1)
        assertEquals((1080 - 2 * c.marginPx).toFloat(), colBottomPx, 0.5f)
        // The top screen holds proportionally more layout points (smaller text).
        val topPx = 1920 - 2 * c.marginPx - 2 * c.headerPx
        assertEquals(topPx / (c.pxPerPt * 0.9f), c.textHeightPt(0), 0.01f)
    }

    /**
     * Text flows across screens with different sizes: every line lands on exactly one page, in
     * order, and no page is more than full (unless a single line is taller than the page).
     */
    @Test
    fun noLineLostRepeatedOrCutWithDifferentSizes() {
        for ((top, bottom) in listOf(9f to 10f, 14f to 8f, 10f to 10f, 6f to 24f)) {
            val c = cfg(ReadingMode.BOOK, false, top, bottom)
            // A long chapter of 13pt lines with the usual slight overlap, plus a few tall images.
            val items = ArrayList<FloatArray>()
            var y = 0f
            for (i in 0 until 2000) {
                val h = if (i % 400 == 399) 300f else 13f
                items += floatArrayOf(y, y + h)
                y += h - 1f
            }
            val rows = LinePaginator.rows(items)
            val spans = LinePaginator.pages(rows, 0) { k -> c.textHeightPt(k % 2) }
            var row = 0
            var prevEnd = 0f
            spans.forEachIndexed { k, s ->
                assertTrue("pages in order", s.y0 >= prevEnd - 0.01f)
                val h = c.textHeightPt(k % 2)
                if (s.overflow == 0f) assertTrue("page fits", s.y1 - s.y0 <= h + 0.02f)
                // Rows on this page: whole rows (the cut may fall inside the 1pt overlap between lines).
                val first = row
                while (row < rows.size && rows.bottoms[row] <= s.y1 + 1.01f) {
                    assertTrue("row $row not cut at the top (sizes $top/$bottom)", rows.tops[row] >= s.y0 - 1.01f)
                    row++
                }
                assertTrue("page $k has at least one whole row", row > first)
                prevEnd = s.y1
            }
            assertEquals("every line placed once ($top/$bottom)", rows.size, row)
        }
    }

    // ---- 2. formats ----------------------------------------------------------------------

    @Test
    fun newFormatsAreRecognised() {
        assertEquals(BookFormat.AZW3, BookFormat.fromFileName("a.azw3"))
        assertEquals(BookFormat.AZW, BookFormat.fromFileName("a.AZW"))
        assertEquals(BookFormat.HTML, BookFormat.fromFileName("a.xhtml"))
        assertEquals(BookFormat.MARKDOWN, BookFormat.fromFileName("notes.md"))
        assertEquals(BookFormat.DOCX, BookFormat.fromFileName("a.docx"))
        assertEquals(BookFormat.ODT, BookFormat.fromFileName("a.odt"))
        assertEquals(BookFormat.RTF, BookFormat.fromFileName("a.rtf"))
        assertEquals(BookFormat.CBT, BookFormat.fromFileName("a.cbt"))
        assertEquals(BookFormat.IMAGE, BookFormat.fromFileName("p.avif"))
        assertEquals(null, BookFormat.fromFileName("folder"))
    }

    @Test
    fun palmDocDecompression() {
        // 'a','b','c' literals, then "copy 6 bytes from 3 back", then 0xC1 = space + 'A',
        // then a 2-byte literal run.
        val src = byteArrayOf(0x61, 0x62, 0x63, 0x80.toByte(), 0x1B, 0xC1.toByte(), 0x02, 0x7A, 0x21)
        assertEquals("abcabcabc Az!", String(MobiFile.palmDoc(src), Charsets.ISO_8859_1))
    }

    @Test
    fun trailingEntriesAndNumbers() {
        // One trailing entry of 3 bytes (its size byte 0x83 last), flags bit 1.
        val rec = "hello".toByteArray() + byteArrayOf(0x00, 0x00, 0x83.toByte())
        assertEquals(3, MobiFile.trailingSize(rec, 0b10))
        // Multibyte flag (bit 0): low 2 bits of the last byte + 1.
        assertEquals(2, MobiFile.trailingSize("hi".toByteArray() + byteArrayOf(0x01), 0b1))
        assertEquals(2 to 641L, MobiFile.vwi(byteArrayOf(0x05, 0x81.toByte()), 0))
        assertEquals(10, MobiFile.base32("000A"))
        assertEquals(32, MobiFile.base32("0010"))
    }

    /** A tiny hand-made MOBI file: header record + one uncompressed text record. */
    private fun mobi(html: String, encryption: Int = 0): ByteArray {
        val text = html.toByteArray(Charsets.UTF_8)
        val name = "Test Book".toByteArray()
        val r0 = ByteArray(248 + name.size)
        fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
        fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0..3) b[o + i] = (v shr (24 - 8 * i)).toByte() }
        put16(r0, 0, 1) // no compression
        put32(r0, 4, text.size.toLong())
        put16(r0, 8, 1) // one text record
        put16(r0, 10, 4096)
        put16(r0, 12, encryption)
        "MOBI".toByteArray().copyInto(r0, 16)
        put32(r0, 20, 0xE8) // header length
        put32(r0, 24, 2)
        put32(r0, 28, 65001) // UTF-8
        put32(r0, 36, 6) // version 6
        put32(r0, 0x54, 248) // full name offset
        put32(r0, 0x58, name.size.toLong())
        put32(r0, 0x6C, 0xFFFFFFFFL) // no pictures
        put32(r0, 0x80, 0) // no EXTH
        name.copyInto(r0, 248)
        val header = ByteArray(78 + 2 * 8 + 2)
        "Test".toByteArray().copyInto(header, 0)
        "BOOKMOBI".toByteArray().copyInto(header, 60)
        put16(header, 76, 2)
        put32(header, 78, header.size.toLong())
        put32(header, 86, (header.size + r0.size).toLong())
        return header + r0 + text
    }

    @Test
    fun oldMobiBecomesChapters() {
        val m = MobiFile(mobi("<html><body><h1>One</h1><p>Hello</p><mbp:pagebreak/><h1>Two</h1><p>World</p></body></html>"))
        assertFalse(m.drm)
        assertEquals(null, m.kf8)
        val b = m.mobi6Book()
        assertEquals("Test Book", b.title)
        assertEquals(listOf("One", "Two"), b.sections.map { it.title })
        assertTrue(b.sections[1].body.contains("World"))
    }

    @Test
    fun drmIsDetected() {
        val bytes = mobi("<p>x</p>", encryption = 2)
        assertTrue(MobiFile(bytes).drm)
        assertTrue(MobiFile.isDrmProtected(bytes))
        assertFalse(MobiFile.isDrmProtected(mobi("<p>x</p>")))
    }

    @Test
    fun rtfToHtml() {
        val rtf = """{\rtf1\ansi\ansicpg1252{\fonttbl{\f0 Times;}}{\info{\title My RTF}{\author Ann Writer}}""" +
            """\pard\outlinelevel0 Chapter One\par\pard This is \b bold\b0 and caf\'e9 """ + "\\" + "u8212? done.\\par}"
        val b = RtfConverter.convert(rtf.toByteArray(Charsets.ISO_8859_1))
        assertEquals("My RTF", b.title)
        assertEquals(listOf("Ann Writer"), b.authors)
        val body = b.sections.joinToString("") { it.body }
        assertTrue(body, body.contains("<h1>Chapter One</h1>"))
        assertTrue(body, body.contains("<b>bold</b>"))
        assertTrue(body, body.contains("café"))
        assertTrue(body, body.contains("—"))
        assertFalse("font table not shown", body.contains("Times"))
    }

    @Test
    fun epubWriterMakesAValidEpub() {
        val b = ConvBook().apply {
            title = "T & co"
            authors += "A"
            addSection("First", "<p>one</p>")
            addSection("Second", "<p>two</p>")
            coverPath = addImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))
        }
        val f = File.createTempFile("conv", ".epub")
        try {
            EpubWriter.write(b, f)
            ZipFile(f).use { z ->
                val first = z.entries().nextElement()
                assertEquals("mimetype", first.name)
                assertEquals(ZipEntry.STORED, first.method)
                val opf = z.getInputStream(z.getEntry("OEBPS/content.opf")).readBytes().toString(Charsets.UTF_8)
                assertTrue(opf.contains("<dc:title>T &amp; co</dc:title>"))
                assertTrue(opf.contains("""<itemref idref="s2"/>"""))
                assertTrue(opf.contains("cover-image"))
                val ncx = z.getInputStream(z.getEntry("OEBPS/toc.ncx")).readBytes().toString(Charsets.UTF_8)
                assertTrue(ncx.contains("Second"))
                assertTrue(z.getEntry("OEBPS/images/img1.jpg") != null)
            }
        } finally {
            f.delete()
        }
    }

    // ---- 4-6. library --------------------------------------------------------------------

    private fun book(
        title: String, format: BookFormat = BookFormat.EPUB, author: String? = null, progress: Float = 0f,
        pages: Int = 0, layoutPages: Int = 0, rtl: Boolean = false, completedAt: Long? = null, series: String? = null, n: Float? = null,
        opened: Long = 0, lastPage: Int = 0,
    ) = Book(
        uri = "u:$title", fileName = "$title.x", format = format.name, sizeBytes = 1, title = title, author = author,
        progress = progress, pageCount = pages, layoutPages = layoutPages, mangaRtl = rtl, completedAt = completedAt,
        series = series, seriesNumber = n, lastOpenedAt = opened, lastPage = lastPage,
    )

    @Test
    fun rowSecondLine() {
        val a = LibraryLogic.detailLine(book("a", pages = 300, layoutPages = 1794, opened = 1, lastPage = 277))
        assertEquals("277", a.current)
        assertEquals("277 / 1794 Pages · EPUB", a.toString())
        // With a known year: "Published: 1965"; without one the part is left out entirely.
        assertEquals("277 / 1794 Pages · Published: 1965 · EPUB", LibraryLogic.detailLine(book("a", pages = 1794, opened = 1, lastPage = 277).copy(year = 1965)).toString())
        assertEquals("Not started / 120 Pages · PDF", LibraryLogic.detailLine(book("b", BookFormat.PDF, pages = 120)).toString())
        assertEquals("Not started / 1794 Pages · Published: 1965 · EPUB", LibraryLogic.detailLine(book("b2", pages = 1794).copy(year = 1965)).toString())
        // Read before the page was saved: estimated from progress, against the source count.
        assertEquals("30 / 120 Pages · PDF", LibraryLogic.detailLine(book("c", BookFormat.PDF, pages = 120, opened = 1, progress = 0.25f)).toString())
        assertEquals("1 / 1 Pages · Image", LibraryLogic.detailLine(book("d", BookFormat.IMAGE, pages = 1, opened = 1)).toString())
        // No page count known at all.
        assertEquals("Page 5 · EPUB", LibraryLogic.detailLine(book("f", opened = 1, lastPage = 5)).toString())
        // Completed: total / total (green in the UI), even if the last view started on page 1793.
        val done = LibraryLogic.detailLine(book("e", BookFormat.CBR, pages = 1794, opened = 1, lastPage = 1793, completedAt = 3))
        assertEquals("1794 / 1794 Pages · CBR", done.toString())
        assertEquals("1794", done.current)
        assertTrue(done.completed)
        assertEquals(dev.thorreader.app.Settings.BOOKMARK_PRESETS[5], dev.thorreader.app.Settings.DEFAULT_BOOKMARK_COLOR)
    }

    @Test
    fun typesAndAuthors() {
        assertEquals(LibraryLogic.MANGA, LibraryLogic.typeOf(book("m", BookFormat.CBZ, rtl = true)))
        assertEquals(LibraryLogic.COMICS, LibraryLogic.typeOf(book("c", BookFormat.CBR)))
        assertEquals(LibraryLogic.BOOKS, LibraryLogic.typeOf(book("p", BookFormat.PDF)))
        assertEquals(listOf("Tolkien, J. R. R."), LibraryLogic.authorsOf("Tolkien, J. R. R."))
        assertEquals(listOf("Neil Gaiman", "Terry Pratchett"), LibraryLogic.authorsOf("Neil Gaiman, Terry Pratchett"))
        assertEquals(listOf("A B", "C D"), LibraryLogic.authorsOf("A B & C D"))
    }

    @Test
    fun categoriesAndSorting() {
        val books = listOf(
            book("Vol 2", BookFormat.CBZ, series = "Saga", n = 2f),
            book("Vol 1", BookFormat.CBZ, series = "saga", n = 1f),
            book("Novel", author = "Neil Gaiman, Terry Pratchett", completedAt = 5),
            book("Other", author = "Terry Pratchett", completedAt = 9),
        )
        assertEquals(listOf("Saga" to 2), LibraryLogic.categories(books, LibraryView.SERIES))
        assertEquals(listOf("Vol 1", "Vol 2"), LibraryLogic.booksIn(books, LibraryView.SERIES, "Saga", SortOrder.TITLE).map { it.title })
        assertEquals(listOf(LibraryLogic.BOOKS to 2, LibraryLogic.COMICS to 2), LibraryLogic.categories(books, LibraryView.TYPE))
        assertTrue(LibraryLogic.categories(books, LibraryView.AUTHORS).contains("Terry Pratchett" to 2))
        assertEquals(listOf("Other", "Novel"), LibraryLogic.sort(books.filter { it.completed }, SortOrder.DATE_COMPLETED).map { it.title })
        assertFalse(SortOrder.DATE_COMPLETED in LibraryLogic.sortsFor(Shelf.LIBRARY))
    }
}

/** Step 12: bookmarks are book locations, so they survive font / margin / mode changes. */
class BookmarkTest {
    private fun slice(ch: Int, a: Float, b: Float) =
        dev.thorreader.app.engine.SliceUnit(ch, 0, 0f, 1f, dev.thorreader.app.engine.SlotSize(1, 1), a, b, null, false)

    @Test
    fun reflowBookmarkFindsItsPageInAnyLayout() {
        // Bookmark a page covering 30-40% of chapter 2 ...
        val a = dev.thorreader.app.engine.bookmarkAnchorOf(slice(2, 0.30f, 0.40f))!!
        // ... after a font change the pages are cut differently: the page holding 30% wins.
        assertTrue(dev.thorreader.app.engine.unitHolds(slice(2, 0.25f, 0.33f), a))
        assertFalse(dev.thorreader.app.engine.unitHolds(slice(2, 0.33f, 0.45f), a))
        assertFalse(dev.thorreader.app.engine.unitHolds(slice(3, 0.25f, 0.33f), a))
        // The last page of a chapter (ends at 1.0) still holds a bookmark at its very end.
        val end = dev.thorreader.app.engine.Anchor(1, 0.99995f)
        assertTrue(dev.thorreader.app.engine.unitHolds(slice(1, 0.9f, 1f), end))
    }

    @Test
    fun splitSpreadHalvesAreSeparateButWholePageHoldsBoth() {
        val p = dev.thorreader.app.engine.Part.RIGHT
        val right = dev.thorreader.app.engine.bookmarkAnchorOf(dev.thorreader.app.engine.FixedUnit(7, p))!!
        val left = dev.thorreader.app.engine.bookmarkAnchorOf(dev.thorreader.app.engine.FixedUnit(7, dev.thorreader.app.engine.Part.LEFT))!!
        assertTrue(dev.thorreader.app.engine.unitHolds(dev.thorreader.app.engine.FixedUnit(7, p), right))
        assertFalse(dev.thorreader.app.engine.unitHolds(dev.thorreader.app.engine.FixedUnit(7, p), left))
        // Mode 3/4 show the spread whole: it holds either half's bookmark.
        assertTrue(dev.thorreader.app.engine.unitHolds(dev.thorreader.app.engine.FixedUnit(7, dev.thorreader.app.engine.Part.WHOLE), right))
        assertFalse(dev.thorreader.app.engine.unitHolds(dev.thorreader.app.engine.FixedUnit(8, dev.thorreader.app.engine.Part.WHOLE), right))
        assertEquals(0, dev.thorreader.app.engine.leftUnitIndex(false))
        assertEquals(1, dev.thorreader.app.engine.leftUnitIndex(true))
    }
}

/** Step 14: sort options, directions and where they apply. */
class SortTest {
    private fun b(title: String, author: String? = null, year: Int? = null, opened: Long = 0, added: Long = 0, progress: Float = 0f, series: String? = null, n: Float? = null) = Book(
        uri = "u:$title", fileName = "$title.epub", format = BookFormat.EPUB.name, sizeBytes = 1, title = title, author = author,
        year = year, lastOpenedAt = opened, addedAt = added, progress = progress, series = series, seriesNumber = n,
    )

    private val books = listOf(
        b("Dune", "Frank Herbert", 1965, opened = 30, added = 1, progress = 0.5f),
        b("Emma", "Jane Austen", 1815, opened = 10, added = 3, progress = 0.9f),
        b("Zed", null, null, opened = 0, added = 2, progress = 0f),
    )

    private fun titles(l: List<Book>) = l.map { it.title }

    @Test
    fun defaultsAndFlipping() {
        assertTrue(SortOrder.TITLE.defaultAscending)
        assertTrue(SortOrder.AUTHOR.defaultAscending)
        assertFalse(SortOrder.PUBLISHED.defaultAscending)
        assertFalse(SortOrder.PROGRESS.defaultAscending)
        assertFalse(SortOrder.RECENTLY_READ.defaultAscending)
        assertEquals(listOf("Dune", "Emma", "Zed"), titles(LibraryLogic.sort(books, SortOrder.TITLE)))
        assertEquals(listOf("Zed", "Emma", "Dune"), titles(LibraryLogic.sort(books, SortOrder.TITLE, ascending = false)))
        // Newest first; a book without a year always goes last, in both directions.
        assertEquals(listOf("Dune", "Emma", "Zed"), titles(LibraryLogic.sort(books, SortOrder.PUBLISHED)))
        assertEquals(listOf("Emma", "Dune", "Zed"), titles(LibraryLogic.sort(books, SortOrder.PUBLISHED, ascending = true)))
        // Never opened goes last for "Recently read".
        assertEquals(listOf("Dune", "Emma", "Zed"), titles(LibraryLogic.sort(books, SortOrder.RECENTLY_READ)))
        assertEquals(listOf("Emma", "Dune", "Zed"), titles(LibraryLogic.sort(books, SortOrder.RECENTLY_READ, ascending = true)))
        assertEquals(listOf("Emma", "Zed", "Dune"), titles(LibraryLogic.sort(books, SortOrder.RECENTLY_ADDED)))
        assertEquals(listOf("Emma", "Dune", "Zed"), titles(LibraryLogic.sort(books, SortOrder.PROGRESS)))
        // No author: last.
        assertEquals(listOf("Dune", "Emma", "Zed"), titles(LibraryLogic.sort(books, SortOrder.AUTHOR)))
        assertTrue(SortOrder.PUBLISHED in LibraryLogic.sortsFor(Shelf.LIBRARY))
    }

    @Test
    fun categoriesFollowTheSort() {
        // By name for Author sort, Z-A when flipped.
        assertEquals(listOf("Frank Herbert", "Jane Austen", LibraryLogic.UNKNOWN_AUTHOR), LibraryLogic.categories(books, LibraryView.AUTHORS, SortOrder.AUTHOR).map { it.first })
        assertEquals(listOf(LibraryLogic.UNKNOWN_AUTHOR, "Jane Austen", "Frank Herbert"), LibraryLogic.categories(books, LibraryView.AUTHORS, SortOrder.AUTHOR, false).map { it.first })
        // By each author's best book for the other sorts (most recently read first).
        assertEquals(listOf("Frank Herbert", "Jane Austen", LibraryLogic.UNKNOWN_AUTHOR), LibraryLogic.categories(books, LibraryView.AUTHORS, SortOrder.RECENTLY_READ).map { it.first })
        assertEquals("Jane Austen", LibraryLogic.categories(books, LibraryView.AUTHORS, SortOrder.PROGRESS).first().first)
        // Inside a series, "Title" = volume order; flipping reverses it.
        val s = listOf(b("C", series = "S", n = 3f), b("A", series = "S", n = 1f), b("B", series = "S", n = 2f))
        assertEquals(listOf("A", "B", "C"), titles(LibraryLogic.booksIn(s, LibraryView.SERIES, "S", SortOrder.TITLE)))
        assertEquals(listOf("C", "B", "A"), titles(LibraryLogic.booksIn(s, LibraryView.SERIES, "S", SortOrder.TITLE, false)))
    }
}
