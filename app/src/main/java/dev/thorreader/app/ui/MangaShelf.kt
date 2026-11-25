package dev.thorreader.app.ui

import dev.thorreader.app.data.Book
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.engine.MangaCollection
import dev.thorreader.app.engine.MangaUnits

/**
 * Manga in the library: which books are volumes / chapters of which series, and the library's
 * series tiles (a series with 2+ books shows as one stacked tile that opens its collection).
 */
object MangaShelf {
    /**
     * The volume / chapter this manga book is (from its file name, else its title), or null.
     * [mangaSeries]: series keys already known to be manga in the library - a comic file of one
     * of those counts too (a chapter just imported, before any lookup marked it as manga).
     */
    fun unitOf(b: Book, mangaSeries: Set<String> = emptySet()): MangaUnits.Unit? {
        val u = MangaUnits.fromFileName(b.fileName) ?: MangaUnits.fromFileName(b.title + ".x") ?: return null
        // The series name the library knows (lookup / ComicInfo / edited) wins over the file name's.
        val unit = u.copy(series = b.series?.takeIf { it.isNotBlank() } ?: u.series)
        val manga = LibraryLogic.typeOf(b) == LibraryLogic.MANGA ||
            (isComic(b) && LibraryLogic.typeOf(b) != LibraryLogic.BOOKS && seriesKey(unit.series) in mangaSeries)
        return unit.takeIf { manga }
    }

    private fun isComic(b: Book) = runCatching { dev.thorreader.app.importer.BookFormat.valueOf(b.format).comic }.getOrDefault(false)

    fun seriesOf(b: Book): String? = unitOf(b)?.series

    /** The series of a book (not manga or comics) - Dune, Harry Potter - or null. */
    fun bookSeriesOf(b: Book): String? =
        b.series?.trim()?.takeIf { it.isNotEmpty() && LibraryLogic.typeOf(b) == LibraryLogic.BOOKS && unitOf(b) == null }

    /** Whether [series] is a manga series in [books] (else a book series). */
    fun isManga(series: String, books: List<Book>): Boolean = seriesKey(series) in mangaSeriesIn(books)

    /** The owned books of the book series [series] in [books], for [BookCollection.build]. */
    fun ownedBooks(series: String, books: List<Book>): List<dev.thorreader.app.engine.BookCollection.Owned> {
        val key = seriesKey(series)
        return books.filter { b -> bookSeriesOf(b)?.let(::seriesKey) == key }
            .map { dev.thorreader.app.engine.BookCollection.Owned(it.id, it.seriesNumber, it.title) }
    }

    /** Book series keys in [books] -> their names (for looking their lists up). */
    fun bookSeriesIn(books: List<Book>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        books.forEach { b -> bookSeriesOf(b)?.let { s -> seriesKey(s)?.let { out.putIfAbsent(it, s) } } }
        return out
    }

    /** Series keys that are manga in [books] (by their manga books). */
    fun mangaSeriesIn(books: List<Book>): Set<String> = books.mapNotNullTo(HashSet()) { b -> unitOf(b)?.series?.let(::seriesKey) }

    /** The owned parts of [series] in [books], for [MangaCollection.build]. */
    fun owned(series: String, books: List<Book>): List<MangaCollection.Owned> {
        val key = seriesKey(series)
        val known = mangaSeriesIn(books)
        return books.mapNotNull { b -> unitOf(b, known)?.takeIf { seriesKey(it.series) == key }?.let { MangaCollection.Owned(b.id, it) } }
    }

    sealed interface Item {
        data class One(val book: Book) : Item
        /** A manga or book series with 2+ books: [books] in reading order (volumes, then chapters; books by number). */
        data class Series(val name: String, val books: List<Book>) : Item
    }

    /**
     * The library's items in [books]' order: a manga series with 2+ books becomes one
     * [Item.Series] where its first book (in the current sort) would be.
     */
    fun group(books: List<Book>): List<Item> {
        val members = HashMap<String, MutableList<Book>>()
        val names = HashMap<String, String>()
        val known = mangaSeriesIn(books)
        fun seriesOf(b: Book) = unitOf(b, known)?.series ?: bookSeriesOf(b)
        for (b in books) {
            val s = seriesOf(b) ?: continue
            val k = seriesKey(s) ?: continue
            members.getOrPut(k) { ArrayList() } += b
            names.putIfAbsent(k, s)
        }
        val out = ArrayList<Item>()
        val placed = HashSet<String>()
        for (b in books) {
            val k = seriesOf(b)?.let(::seriesKey)
            val group = k?.let { members[it] }
            if (k == null || group == null || group.size < 2) {
                out += Item.One(b)
            } else if (placed.add(k)) {
                out += Item.Series(names.getValue(k), group.sortedWith(readingOrder))
            }
        }
        return out
    }

    /** Volumes by number, then chapters by number; books by their number in the series, then title. */
    val readingOrder: Comparator<Book> = compareBy<Book>(
        { if (unitOf(it)?.kind == MangaUnits.Kind.VOLUME) 0 else 1 },
        { unitOf(it)?.let { u -> u.volume?.toFloat() ?: u.chapter } ?: it.seriesNumber ?: Float.MAX_VALUE },
    ).thenComparator { a, b -> dev.thorreader.app.io.NaturalOrder.compare(a.title, b.title) }
}
