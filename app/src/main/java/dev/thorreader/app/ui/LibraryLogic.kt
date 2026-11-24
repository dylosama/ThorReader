package dev.thorreader.app.ui

import dev.thorreader.app.LibraryView
import dev.thorreader.app.Shelf
import dev.thorreader.app.SortOrder
import dev.thorreader.app.data.Book
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.io.NaturalOrder

/** Library sorting, grouping and row text: plain functions, unit-tested. */
object LibraryLogic {

    /** Book types for the "Type" view. */
    const val BOOKS = "Books"
    const val MANGA = "Manga"
    const val COMICS = "Comics"

    /**
     * Manga = right-to-left reading on (or ComicInfo says manga); Comics = other comic formats;
     * Books = everything else, PDF included.
     */
    fun typeOf(b: Book): String {
        b.typeOverride?.takeIf { it in TYPES }?.let { return it } // chosen by hand
        val comic = runCatching { BookFormat.valueOf(b.format).comic }.getOrDefault(false)
        return when {
            // Right-to-left, ComicInfo Manga=Yes/YesAndRightToLeft, or the online lookup said so.
            b.rtl || b.mangaRtl || b.isManga -> MANGA
            comic -> COMICS
            else -> BOOKS
        }
    }

    val TYPES = listOf(BOOKS, MANGA, COMICS)

    fun sortsFor(shelf: Shelf): List<SortOrder> =
        SortOrder.entries.filter { it != SortOrder.DATE_COMPLETED || shelf == Shelf.COMPLETED }

    private val byTitle = Comparator<Book> { a, b -> NaturalOrder.compare(a.title, b.title) }

    /**
     * Sort [books] by [order]. [ascending] = ↑ (A→Z, oldest / lowest first). Books without a
     * value for this order (never opened, no year, no author, not completed) always come last.
     */
    fun sort(books: List<Book>, order: SortOrder, ascending: Boolean = order.defaultAscending): List<Book> {
        val (has, none) = books.partition { hasValue(it, order) }
        val asc: Comparator<Book> = when (order) {
            SortOrder.RECENTLY_READ -> compareBy { it.lastOpenedAt }
            SortOrder.RECENTLY_ADDED -> compareBy { it.addedAt }
            SortOrder.TITLE -> byTitle
            SortOrder.AUTHOR -> Comparator<Book> { a, b -> (a.author ?: "").compareTo(b.author ?: "", ignoreCase = true) }.then(seriesOrder)
            SortOrder.PROGRESS -> compareBy { if (it.completed) 1f else it.progress }
            SortOrder.PUBLISHED -> compareBy { it.year ?: 0 }
            SortOrder.DATE_COMPLETED -> compareBy { it.completedAt ?: 0L }
        }
        val rest = if (order == SortOrder.RECENTLY_READ) compareByDescending<Book> { it.addedAt }.then(byTitle) else byTitle
        return has.sortedWith((if (ascending) asc else asc.reversed()).then(byTitle)) + none.sortedWith(rest)
    }

    private fun hasValue(b: Book, order: SortOrder) = when (order) {
        SortOrder.RECENTLY_READ -> b.lastOpenedAt > 0
        SortOrder.AUTHOR -> !b.author.isNullOrBlank()
        SortOrder.PUBLISHED -> b.year != null
        SortOrder.DATE_COMPLETED -> b.completedAt != null
        else -> true
    }

    private val seriesOrder = Comparator<Book> { a, b ->
        val s = (seriesKey(a.series) ?: "").compareTo(seriesKey(b.series) ?: "")
        if (s != 0) s else (a.seriesNumber ?: Float.MAX_VALUE).compareTo(b.seriesNumber ?: Float.MAX_VALUE)
    }

    /**
     * Several authors are stored joined with ", ". Split only when every piece looks like a full
     * name (has a space), so "Tolkien, J. R. R." stays one author.
     */
    fun authorsOf(author: String?): List<String> {
        val a = author?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val parts = a.split(Regex("\\s*(?:;|&| and )\\s*")).flatMap { p ->
            val commas = p.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
            if (commas.size > 1 && commas.all { it.contains(' ') }) commas else listOf(p.trim())
        }.filter { it.isNotEmpty() }
        return parts.distinct()
    }

    /** The categories [b] belongs to in [view] (display names). */
    fun categoriesOf(b: Book, view: LibraryView): List<String> = when (view) {
        LibraryView.ALL -> emptyList()
        LibraryView.AUTHORS -> authorsOf(b.author).ifEmpty { listOf(UNKNOWN_AUTHOR) }
        LibraryView.SERIES -> listOfNotNull(b.series?.trim()?.takeIf { it.isNotEmpty() })
        LibraryView.GENRE -> b.genres?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        LibraryView.TYPE -> listOf(typeOf(b))
    }

    const val UNKNOWN_AUTHOR = "Unknown author"

    /**
     * Categories with book counts. Names are matched ignoring case/spaces; shown as first seen.
     * Order: Type is always Books, Manga, Comics; with Title or Author sorting, by name (A→Z or
     * Z→A); otherwise by each category's best book in that sort (e.g. the author you read most
     * recently first).
     */
    fun categories(
        books: List<Book>,
        view: LibraryView,
        order: SortOrder = SortOrder.TITLE,
        ascending: Boolean = order.defaultAscending,
    ): List<Pair<String, Int>> {
        val names = LinkedHashMap<String, String>()
        val counts = HashMap<String, Int>()
        for (b in books) for (c in categoriesOf(b, view).distinctBy(::key)) {
            val k = key(c)
            names.putIfAbsent(k, c)
            counts[k] = (counts[k] ?: 0) + 1
        }
        val list = names.map { (k, n) -> n to (counts[k] ?: 0) }
        return if (view == LibraryView.TYPE) {
            listOf(BOOKS, MANGA, COMICS).mapNotNull { t -> list.firstOrNull { it.first == t } }
        } else {
            when (order) {
                SortOrder.TITLE, SortOrder.AUTHOR -> {
                    val byName = list.sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }
                    if (ascending) byName else byName.reversed()
                }
                else -> {
                    val rank = HashMap<String, Int>()
                    sort(books, order, ascending).forEachIndexed { i, b -> categoriesOf(b, view).forEach { rank.putIfAbsent(key(it), i) } }
                    list.sortedWith(compareBy<Pair<String, Int>> { rank[key(it.first)] ?: Int.MAX_VALUE }.thenComparator { a, b -> NaturalOrder.compare(a.first, b.first) })
                }
            }
        }
    }

    /**
     * Books in category [name], in the chosen sort. Inside a series, "Title" sorting means
     * volume / issue order (↑ first volume first).
     */
    fun booksIn(books: List<Book>, view: LibraryView, name: String, order: SortOrder, ascending: Boolean = order.defaultAscending): List<Book> {
        val k = key(name)
        val inCat = books.filter { b -> categoriesOf(b, view).any { key(it) == k } }
        if (view == LibraryView.SERIES && order == SortOrder.TITLE) {
            val byNumber = inCat.sortedWith(seriesOrder.then(byTitle))
            return if (ascending) byNumber else byNumber.reversed()
        }
        return sort(inCat, order, ascending)
    }

    private fun key(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * The row's third line in three parts: [prefix], [current] (the page you're on, shown in the
     * bookmark colour, or green when completed) and the [rest]:
     * "277" + " / 1794 Pages · Published: 1965 · EPUB", or "Not started / 1794 Pages · ...".
     */
    data class PageLine(val current: String?, val rest: String, val completed: Boolean = false, val prefix: String = "") {
        override fun toString() = prefix + (current ?: "") + rest
    }

    /**
     * Current page and total come from the book's last-used layout; the total falls back to the
     * source's page count, and a current page not saved yet (books read before v0.2.0) is
     * estimated from the reading progress. "Published" is left out when the year is unknown.
     */
    fun detailLine(b: Book): PageLine {
        val format = runCatching { BookFormat.valueOf(b.format).label }.getOrDefault(b.format)
        val tail = listOfNotNull(b.year?.let { "Published: $it" }, format).joinToString(" · ", prefix = " · ")
        val total = b.displayPages
        val pages = if (total > 0) " / $total Pages" else ""
        // Completed: "1794 / 1794 Pages" (green), even if the last view started on page 1793 or
        // the book was marked completed without being read to the end.
        if (b.completed) {
            return if (total > 0) PageLine("$total", pages + tail, completed = true)
            else PageLine(null, "Completed$tail", completed = true)
        }
        if (b.lastOpenedAt == 0L) return PageLine(null, "Not started$pages$tail")
        val current = when {
            b.lastPage > 0 -> if (total > 0) b.lastPage.coerceAtMost(total) else b.lastPage
            total > 0 -> Math.round(b.progress * total).coerceIn(1, total)
            else -> null
        }
        return when {
            current != null && total > 0 -> PageLine("$current", pages + tail)
            current != null -> PageLine("$current", tail, prefix = "Page ")
            else -> PageLine(null, tail.removePrefix(" · "))
        }
    }
}
