package dev.thorreader.app.engine

import dev.thorreader.app.engine.MangaCollection.BookRow
import dev.thorreader.app.engine.MangaCollection.MissingBooksRow

/**
 * A book series as a collection (Dune: 1 Dune, 2 Dune Messiah, ...): the books the user has and
 * the ones they don't, by number, with titles when the series' list is known. Pure (unit-tested).
 */
object BookCollection {
    /** A book of the series in the library: its number as the library knows it (null = none). */
    data class Owned(val bookId: Long, val number: Float?, val title: String)

    fun build(owned: List<Owned>, list: BookSeriesList?): MangaCollection.Collection {
        // Its number: the library's (whole numbers), else its title on the series' list.
        val numbered = HashMap<Int, Owned>()
        val loose = ArrayList<Owned>()
        for (o in owned) {
            val n = o.number?.takeIf { it >= 1f && it == kotlin.math.floor(it) }?.toInt() ?: list?.numberOfTitle(o.title)
            if (n != null && n !in numbered) numbered[n] = o else loose += o
        }
        val last = maxOf(list?.last ?: 0, numbered.keys.maxOrNull() ?: 0)
        val rows = ArrayList<MangaCollection.Row>()
        var gapFrom: Int? = null
        fun flush(upTo: Int) {
            val from = gapFrom ?: return
            if (list != null) {
                // Known books one by one (their titles); unknown numbers as a range.
                var n = from
                while (n <= upTo) {
                    val t = list.titleOf(n)
                    if (t != null) { rows += MissingBooksRow(n, n, t); n++; continue }
                    var end = n
                    while (end + 1 <= upTo && list.titleOf(end + 1) == null) end++
                    rows += MissingBooksRow(n, end, null)
                    n = end + 1
                }
            } else {
                rows += MissingBooksRow(from, upTo, null)
            }
            gapFrom = null
        }
        for (n in 1..last) {
            val o = numbered[n]
            if (o == null) {
                if (gapFrom == null) gapFrom = n
            } else {
                flush(n - 1)
                rows += BookRow(n, list?.titleOf(n) ?: o.title, o.bookId)
            }
        }
        flush(last)
        loose.sortedWith(compareBy(dev.thorreader.app.io.NaturalOrder) { it.title }).forEach { rows += BookRow(null, it.title, it.bookId) }
        return MangaCollection.Collection(
            rows, volumes = 0, chapters = 0, lastChapter = null, status = null,
            isBooks = true, books = owned.size, seriesBooks = list?.last,
        )
    }
}
