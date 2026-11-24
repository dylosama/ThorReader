package dev.thorreader.app

import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.ui.LibraryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 19: hiding list entries, adding authors / series. */
class ListManageTest {
    private fun b(title: String, author: String? = null, series: String? = null, n: Float? = null, genres: String? = null) = Book(
        uri = "u:$title", fileName = "$title.epub", format = BookFormat.EPUB.name, sizeBytes = 1, title = title,
        author = author, series = series, seriesNumber = n, genres = genres,
    )

    private val books = listOf(
        b("Dune", "Frank Herbert", genres = "Science Fiction"),
        b("Emma", "Jane Austen", genres = "Romance"),
        b("Saga 1", series = "Saga", n = 1f),
    )

    @Test
    fun hidingOnlyAffectsThatList() {
        val authors = LibraryLogic.categories(books, LibraryView.AUTHORS, hidden = setOf("frank  HERBERT"))
        assertEquals(listOf("Jane Austen", LibraryLogic.UNKNOWN_AUTHOR), authors.map { it.first })
        // The book is still in its genre and in the flat list.
        assertEquals(listOf("Romance", "Science Fiction"), LibraryLogic.categories(books, LibraryView.GENRE).map { it.first })
        assertEquals(3, LibraryLogic.sort(books, SortOrder.TITLE).size)
        assertEquals(listOf("Romance"), LibraryLogic.categories(books, LibraryView.GENRE, hidden = setOf("Science Fiction")).map { it.first })
    }

    @Test
    fun namesMergeIgnoringCase() {
        assertEquals("Saga", LibraryLogic.matchExisting("  saga ", LibraryLogic.names(books, LibraryView.SERIES)))
        assertNull(LibraryLogic.matchExisting("Sagas", LibraryLogic.names(books, LibraryView.SERIES)))
        assertTrue(LibraryLogic.UNKNOWN_AUTHOR !in LibraryLogic.names(books, LibraryView.AUTHORS))
    }

    @Test
    fun addAuthorMoveOrAlongside() {
        val dune = books[0]
        val moved = LibraryLogic.withAuthor(dune, "Brian Herbert", move = true)
        assertEquals("Brian Herbert", moved.author)
        assertTrue(moved.edited("authors"))
        val both = LibraryLogic.withAuthor(dune, "Brian Herbert", move = false)
        assertEquals(listOf("Frank Herbert", "Brian Herbert"), LibraryLogic.authorsOf(both.author))
        // Single-word names stay separate authors too.
        assertEquals(listOf("Frank Herbert", "Moebius"), LibraryLogic.authorsOf(LibraryLogic.withAuthor(dune, "Moebius", move = false).author))
        // Adding someone already there doesn't duplicate them.
        assertEquals("Frank Herbert", LibraryLogic.withAuthor(dune, "frank herbert", move = false).author)
        // A book without an author just gets the new one.
        assertEquals("X Y", LibraryLogic.withAuthor(books[2], "X Y", move = false).author)
    }

    @Test
    fun addSeriesWithNumbers() {
        val s = LibraryLogic.withSeries(books[1], "Classics", 2f)
        assertEquals("Classics", s.series)
        assertEquals(2f, s.seriesNumber)
        assertTrue(s.edited("series") && s.edited("number"))
        assertEquals(listOf("Classics" to 1, "Saga" to 1), LibraryLogic.categories(books.map { if (it.title == "Emma") s else it }, LibraryView.SERIES))
    }
}
