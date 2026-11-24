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

    fun sort(books: List<Book>, order: SortOrder): List<Book> = when (order) {
        // Books never opened go after the ones you've read, newest additions first.
        SortOrder.RECENTLY_READ -> books.sortedWith(compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt }.then(byTitle))
        SortOrder.RECENTLY_ADDED -> books.sortedWith(compareByDescending<Book> { it.addedAt }.then(byTitle))
        SortOrder.TITLE -> books.sortedWith(byTitle)
        SortOrder.AUTHOR -> books.sortedWith(
            Comparator<Book> { a, b ->
                when {
                    a.author == null && b.author == null -> 0
                    a.author == null -> 1
                    b.author == null -> -1
                    else -> a.author.compareTo(b.author, ignoreCase = true)
                }
            }.then(seriesOrder).then(byTitle),
        )
        SortOrder.PROGRESS -> books.sortedWith(compareByDescending<Book> { it.progress }.then(byTitle))
        SortOrder.DATE_COMPLETED -> books.sortedWith(compareByDescending<Book> { it.completedAt ?: 0L }.then(byTitle))
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

    /** Categories with book counts. Names are matched ignoring case/spaces; shown as first seen. */
    fun categories(books: List<Book>, view: LibraryView): List<Pair<String, Int>> {
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
            list.sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }
        }
    }

    /** Books in category [name]; a series is listed in volume order. */
    fun booksIn(books: List<Book>, view: LibraryView, name: String, order: SortOrder): List<Book> {
        val k = key(name)
        val inCat = books.filter { b -> categoriesOf(b, view).any { key(it) == k } }
        return if (view == LibraryView.SERIES) inCat.sortedWith(seriesOrder.then(byTitle)) else sort(inCat, order)
    }

    private fun key(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Row line under the author, in two parts: [current] (the page you're on, shown in the
     * bookmark colour) and the [rest]: "277" + " / 1794 · EPUB", or "Not started · EPUB".
     */
    data class PageLine(val current: String?, val rest: String, val completed: Boolean = false) {
        override fun toString() = (current ?: "") + rest
    }

    /**
     * Current page and total come from the book's last-used layout; the total falls back to the
     * source's page count, and a current page not saved yet (books read before v0.2.0) is
     * estimated from the reading progress.
     */
    fun detailLine(b: Book): PageLine {
        val format = runCatching { BookFormat.valueOf(b.format).label }.getOrDefault(b.format)
        val total = b.displayPages
        // Completed: "22 / 22" (shown in green), even if the last view started on page 21 or
        // the book was marked completed without being read to the end.
        if (b.completed) {
            return if (total > 0) PageLine("$total", " / $total · $format", completed = true)
            else PageLine(null, "Completed · $format", completed = true)
        }
        if (b.lastOpenedAt == 0L) return PageLine(null, "Not started · $format")
        val current = when {
            b.lastPage > 0 -> if (total > 0) b.lastPage.coerceAtMost(total) else b.lastPage
            total > 0 -> Math.round(b.progress * total).coerceIn(1, total)
            else -> null
        }
        return when {
            current != null && total > 0 -> PageLine("$current", " / $total · $format")
            current != null -> PageLine("$current", " · $format")
            else -> PageLine(null, format)
        }
    }
}
