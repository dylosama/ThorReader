package dev.thorreader.app

import dev.thorreader.app.data.Book
import dev.thorreader.app.ui.MangaShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Library series tiles for manga. */
class MangaShelfTest {
    private var id = 0L
    private fun book(file: String, series: String? = null, rtl: Boolean = true, format: String = "CBZ") =
        Book(id = ++id, uri = "u$id", fileName = file, format = format, sizeBytes = 1, title = file.substringBeforeLast('.'), series = series, mangaRtl = rtl)

    @Test
    fun aSeriesWithTwoOrMoreBooksIsOneTile() {
        val dune = book("Dune.epub", rtl = false, format = "EPUB")
        val ch47 = book("Blue Period - c047.cbz", "Blue Period")
        val v2 = book("Blue Period v02 (2021) (Digital).cbz", "Blue Period")
        val csm = book("Chainsaw Man, Ch. 1 A Dog and a Chainsaw.cbz") // a single book: stays a cover
        val v1 = book("Blue Period v01 (2020) (Digital).cbz", "blue period")
        val items = MangaShelf.group(listOf(dune, ch47, csm, v2, v1))
        assertEquals(3, items.size) // Dune, the Blue Period tile, Chainsaw Man
        assertEquals(MangaShelf.Item.One(dune), items[0])
        val tile = items[1] as MangaShelf.Item.Series // where its first book (ch47) was
        assertEquals("Blue Period", tile.name)
        assertEquals(listOf(v1, v2, ch47), tile.books) // volumes first, then chapters
        assertEquals(MangaShelf.Item.One(csm), items[2])
    }

    @Test
    fun aNewChapterJoinsItsMangaSeries() {
        // Just imported: no right-to-left flag or lookup yet, but its series is manga in the library.
        val v1 = book("Blue Period v01 (2020) (Digital).cbz", "Blue Period")
        val v2 = book("Blue Period v02 (2021) (Digital).cbz", "Blue Period")
        val ch47 = book("Blue Period - c047.cbz", rtl = false)
        val tile = MangaShelf.group(listOf(ch47, v1, v2)).single() as MangaShelf.Item.Series
        assertEquals(listOf(v1, v2, ch47), tile.books)
        assertEquals(3, MangaShelf.owned("Blue Period", listOf(v1, v2, ch47)).size)
        // A western comic with the same kind of name is left alone.
        assertEquals(2, MangaShelf.group(listOf(book("Saga 047.cbz", rtl = false), v1)).size)
    }

    @Test
    fun onlyMangaCounts() {
        assertNull(MangaShelf.unitOf(book("Spectacular Spider-Man v1 001.cbr", rtl = false)))
        assertEquals("Chainsaw Man", MangaShelf.seriesOf(book("Chainsaw Man, Ch. 1 A Dog and a Chainsaw.cbz")))
    }
}
