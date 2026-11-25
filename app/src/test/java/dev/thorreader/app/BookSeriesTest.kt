package dev.thorreader.app

import dev.thorreader.app.data.Book
import dev.thorreader.app.engine.BookCollection
import dev.thorreader.app.engine.BookSeriesList
import dev.thorreader.app.engine.MangaCollection.BookRow
import dev.thorreader.app.engine.MangaCollection.MissingBooksRow
import dev.thorreader.app.ui.MangaShelf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book series (Dune 1-6): the Wikidata list, the collection, the library tiles. */
class BookSeriesTest {
    private fun sparql(vararg rows: Array<Any?>): String {
        val b = JSONArray()
        rows.forEach { (series, label, ord, links) ->
            val o = JSONObject()
            o.put("series", JSONObject().put("value", "http://www.wikidata.org/entity/$series"))
            label?.let { o.put("bookLabel", JSONObject().put("value", it)) }
            ord?.let { o.put("ord", JSONObject().put("value", it)) }
            links?.let { o.put("links", JSONObject().put("value", it.toString())) }
            b.put(o)
        }
        return JSONObject().put("results", JSONObject().put("bindings", b)).toString()
    }

    private operator fun Array<Any?>.component4() = this[3]

    @Test
    fun theWikidataListKeepsOneEnglishBookPerWholeNumber() {
        val json = sparql(
            arrayOf("Q45875", "A Game of Thrones", "1", 90),
            arrayOf("Q45875", "A Clash of Kings", "2", 60),
            arrayOf("Q45875", "Kampen om jerntronen", "2", 1), // a translation's own item
            arrayOf("Q45875", "A Storm of Swords", "3", 55),
            arrayOf("Q45875", "Q5813009", "4", 0), // no English title
            arrayOf("Q8337", "Harry Potter prequel", "0.5", 3), // not a whole number
            arrayOf("Q8337", "Harry Potter and the Philosopher's Stone", "1", 100),
        )
        val list = BookSeriesList.fromSparql(json, listOf("Q45875"))!!
        assertEquals(
            listOf(
                BookSeriesList.Entry(1, "A Game of Thrones"), BookSeriesList.Entry(2, "A Clash of Kings"),
                BookSeriesList.Entry(3, "A Storm of Swords"), BookSeriesList.Entry(4, null),
            ),
            list.books,
        )
        // Q8337 has only one whole-numbered book: not a series list.
        assertEquals("A Game of Thrones", BookSeriesList.fromSparql(json, listOf("Q8337", "Q45875"))!!.books.first().title)
    }

    @Test
    fun theBestSearchMatchWinsOverABiggerFranchise() {
        val json = sparql(
            arrayOf("Q1", "Dune", "1", 50), arrayOf("Q1", "Dune Messiah", "2", 40),
            arrayOf("Q2", "Dune", "1", 50), arrayOf("Q2", "Dune Messiah", "2", 40), arrayOf("Q2", "House Atreides", "3", 10),
        )
        assertEquals(2, BookSeriesList.fromSparql(json, listOf("Q1", "Q2"))!!.books.size)
        assertNull(BookSeriesList.fromSparql("""{"results":{"bindings":[]}}"""))
        assertNull(BookSeriesList.fromSparql("not json"))
        assertTrue(BookSeriesList.query(listOf("Q6095696", "Q1 } ; DROP")).contains("VALUES ?series { wd:Q6095696 }"))
        assertEquals(listOf("Q8337", "Q43361"), BookSeriesList.searchIds("""{"search":[{"id":"Q8337"},{"id":"Q43361"},{"id":"P31"}]}"""))
    }

    private fun bookAnswer(vararg rows: List<String?>): String {
        val b = JSONArray()
        rows.forEach { (book, label, series, ord, author) ->
            val o = JSONObject()
            o.put("book", JSONObject().put("value", "http://www.wikidata.org/entity/$book"))
            o.put("bookLabel", JSONObject().put("value", label))
            o.put("seriesLabel", JSONObject().put("value", series))
            ord?.let { o.put("ord", JSONObject().put("value", it)) }
            author?.let { o.put("aLabel", JSONObject().put("value", it)) }
            b.put(o)
        }
        return JSONObject().put("results", JSONObject().put("bindings", b)).toString()
    }

    @Test
    fun aBooksSeriesIsFoundByItsTitleAndAuthor() {
        val hp = bookAnswer(
            listOf("Q43361", "Harry Potter and the Philosopher's Stone", "Wizarding World", null, "J. K. Rowling"),
            listOf("Q43361", "Harry Potter and the Philosopher's Stone", "Harry Potter", "1", "J. K. Rowling"),
        )
        assertEquals("Harry Potter" to 1, BookSeriesList.seriesOfBook(hp, "Harry Potter and the Philosopher's Stone", listOf("Q43361"), "J.K. Rowling"))
        // Someone else's book with that title isn't it; another title isn't either.
        assertNull(BookSeriesList.seriesOfBook(hp, "Harry Potter and the Philosopher's Stone", listOf("Q43361"), "Jane Smith"))
        assertNull(BookSeriesList.seriesOfBook(hp, "Harry Potter and the Chamber of Secrets", listOf("Q43361"), null))
        // A longer file title still finds it; an id-only series name doesn't count.
        val dune = bookAnswer(listOf("Q190192", "Dune", "Dune", "1", "Frank Herbert"), listOf("Q9", "Dune", "Q123", "1", null))
        assertEquals("Dune" to 1, BookSeriesList.seriesOfBook(dune, "Dune (Dune Chronicles, Book 1)", listOf("Q190192", "Q9"), "Frank Herbert"))
        assertNull(BookSeriesList.seriesOfBook("<html>", "Dune"))
    }

    private val dune = BookSeriesList(
        listOf("Dune", "Dune Messiah", "Children of Dune", "God Emperor of Dune", "Heretics of Dune", "Chapterhouse: Dune")
            .mapIndexed { i, t -> BookSeriesList.Entry(i + 1, t) },
    )

    @Test
    fun titlesFindTheirNumbers() {
        assertEquals(1, dune.numberOfTitle("Dune"))
        assertEquals(2, dune.numberOfTitle("Dune Messiah"))
        assertEquals(6, dune.numberOfTitle("Chapterhouse Dune"))
        assertEquals(1, dune.numberOfTitle("Dune (Dune Chronicles, Book 1)"))
        assertEquals(2, dune.numberOfTitle("Dune Messiah - Frank Herbert"))
        assertNull(dune.numberOfTitle("Project Hail Mary"))
        val mistborn = BookSeriesList(listOf(BookSeriesList.Entry(1, "Mistborn: The Final Empire"), BookSeriesList.Entry(2, "Mistborn: The Well of Ascension")))
        assertEquals(1, mistborn.numberOfTitle("The Final Empire"))
    }

    @Test
    fun ownedBooksAndTheMissingOnesByTitle() {
        // The user has Dune (no number, found by title) and God Emperor (#4 from its metadata).
        val c = BookCollection.build(listOf(BookCollection.Owned(10, null, "Dune"), BookCollection.Owned(11, 4f, "God Emperor of Dune")), dune)
        assertEquals(
            listOf(
                BookRow(1, "Dune", 10), MissingBooksRow(2, 2, "Dune Messiah"), MissingBooksRow(3, 3, "Children of Dune"),
                BookRow(4, "God Emperor of Dune", 11), MissingBooksRow(5, 5, "Heretics of Dune"), MissingBooksRow(6, 6, "Chapterhouse: Dune"),
            ),
            c.rows,
        )
        assertTrue(c.isBooks)
        assertEquals(2, c.books)
        assertEquals(6, c.seriesBooks)
    }

    @Test
    fun withoutAListTheGapsAreRanges() {
        val c = BookCollection.build(
            listOf(BookCollection.Owned(1, 3f, "Three"), BookCollection.Owned(2, 7f, "Seven"), BookCollection.Owned(3, null, "Companion")),
            null,
        )
        assertEquals(
            listOf(MissingBooksRow(1, 2, null), BookRow(3, "Three", 1), MissingBooksRow(4, 6, null), BookRow(7, "Seven", 2), BookRow(null, "Companion", 3)),
            c.rows,
        )
        assertNull(c.seriesBooks)
        // A list with a hole: a known number in between keeps its title, unknown ones are a range.
        val holey = BookSeriesList(listOf(BookSeriesList.Entry(1, "One"), BookSeriesList.Entry(4, "Four")))
        assertEquals(
            listOf(BookRow(1, "One", 9), MissingBooksRow(2, 3, null), MissingBooksRow(4, 4, "Four")),
            BookCollection.build(listOf(BookCollection.Owned(9, 1f, "One")), holey).rows,
        )
    }

    private var id = 0L
    private fun book(title: String, series: String?, number: Float? = null, format: String = "EPUB") =
        Book(id = ++id, uri = "u$id", fileName = "$title.epub", format = format, sizeBytes = 1, title = title, series = series, seriesNumber = number)

    @Test
    fun twoBooksOfASeriesAreOneTileInReadingOrder() {
        val messiah = book("Dune Messiah", "Dune", 2f)
        val other = book("Project Hail Mary", null)
        val dune1 = book("Dune", "Dune", 1f)
        val hp = book("Harry Potter and the Chamber of Secrets", "Harry Potter", 2f) // alone: stays a cover
        val items = MangaShelf.group(listOf(messiah, other, dune1, hp))
        assertEquals(3, items.size)
        val tile = items[0] as MangaShelf.Item.Series
        assertEquals("Dune", tile.name)
        assertEquals(listOf(dune1, messiah), tile.books)
        assertEquals(MangaShelf.Item.One(other), items[1])
        assertEquals(MangaShelf.Item.One(hp), items[2])
        assertEquals(false, MangaShelf.isManga("Dune", listOf(messiah, dune1)))
        assertEquals(2, MangaShelf.ownedBooks("dune", listOf(messiah, other, dune1)).size)
        assertEquals(mapOf("dune" to "Dune", "harry potter" to "Harry Potter").keys.size, MangaShelf.bookSeriesIn(listOf(messiah, dune1, hp)).size)
        // A comic's series isn't a book series.
        assertNull(MangaShelf.bookSeriesOf(book("Saga #1", "Saga", 1f, format = "CBZ")))
    }
}
