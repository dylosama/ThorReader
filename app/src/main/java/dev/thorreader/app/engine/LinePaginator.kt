package dev.thorreader.app.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Splits laid-out content into pages WITHOUT EVER CUTTING A LINE.
 *
 * Input: the vertical extent (top, bottom in points) of every text line and image on a tall
 * MuPDF page. Text line boxes include the font's full ascent/descent, so consecutive lines in a
 * paragraph often overlap slightly; they are still separate lines. Boxes that sit side by side
 * (overlapping by more than half the smaller one's height: e.g. an image with text next to it,
 * or table cells) form one "row", which is never split.
 *
 * A page is a run of whole rows. If a single row is taller than the page (a huge image, a big
 * table), it gets a page of its own that OVERFLOWS; the reader lets that page scroll.
 */
object LinePaginator {

    class Rows(val tops: FloatArray, val bottoms: FloatArray) {
        val size get() = tops.size
    }

    /** A page: points [y0, y1) of the tall page. [overflow] > 0 when it doesn't fit its height. */
    data class Span(val y0: Float, val y1: Float, val overflow: Float)

    fun rows(items: List<FloatArray>): Rows {
        val sorted = items.filter { it[1] > it[0] }.sortedBy { it[0] }
        val tops = ArrayList<Float>()
        val bottoms = ArrayList<Float>()
        for (it in sorted) {
            val t = it[0]
            val b = it[1]
            if (tops.isNotEmpty()) {
                val i = tops.lastIndex
                val overlap = min(bottoms[i], b) - max(tops[i], t)
                val smaller = min(bottoms[i] - tops[i], b - t)
                if (overlap > 0.5f * smaller) {
                    bottoms[i] = max(bottoms[i], b) // same row: keep together
                    continue
                }
            }
            tops += t
            bottoms += b
        }
        return Rows(tops.toFloatArray(), bottoms.toFloatArray())
    }

    /** Where a page may end after row [i]: below it, or mid-way through a slight overlap. */
    fun cutAfter(r: Rows, i: Int): Float =
        if (i >= r.size - 1 || r.bottoms[i] <= r.tops[i + 1]) r.bottoms[i]
        else (r.bottoms[i] + r.tops[i + 1]) / 2f

    /**
     * Fill pages with whole rows. [heightFor] gives the content height (pt) of page number k
     * (counted from [firstPage]); pages can have different heights.
     */
    fun pages(r: Rows, firstPage: Int, heightFor: (Int) -> Float): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        var k = firstPage
        while (i < r.size) {
            // The first page of a tall page starts at its top (keeps chapter-heading spacing);
            // later pages start at their first line (no stray gap at the top).
            val y0 = if (i == 0) 0f else max(cutAfter(r, i - 1), r.tops[i])
            val h = heightFor(k)
            var j = i
            while (j + 1 < r.size && cutAfter(r, j + 1) - y0 <= h + 0.01f) j++
            val y1 = cutAfter(r, j)
            out += Span(y0, y1, max(0f, y1 - y0 - h))
            i = j + 1
            k++
        }
        return out
    }
}
