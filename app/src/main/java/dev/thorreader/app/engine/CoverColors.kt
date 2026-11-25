package dev.thorreader.app.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel maths for book covers (plain ARGB ints, no Android types, so it is unit-tested):
 * telling a cover picture from a page of text, and a cover's dominant colour.
 */
object CoverColors {

    /** Near-white: page paper (and white areas of a picture). */
    private fun white(c: Int) = min(c shr 16 and 0xFF, min(c shr 8 and 0xFF, c and 0xFF)) >= 232

    /** Clearly coloured (text is black / grey, so a page of text has none). */
    private fun saturated(c: Int): Boolean {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        val hi = max(r, max(g, b))
        return hi >= 40 && hi - min(r, min(g, b)) >= 40
    }

    /**
     * Is this rendered page ([gw] x [gh] grid of samples, row by row) a picture rather than text
     * on paper? Then: the picture's bounds in grid cells [left, top, right, bottom) (white margins
     * left out). Null for text, a title page or a small picture (a logo / ornament).
     */
    fun pictureCells(grid: IntArray, gw: Int, gh: Int): IntArray? {
        if (gw <= 0 || gh <= 0 || grid.size < gw * gh) return null
        var inked = 0
        var coloured = 0
        var l = gw
        var t = gh
        var r = -1
        var b = -1
        for (y in 0 until gh) for (x in 0 until gw) {
            val c = grid[y * gw + x]
            if (white(c)) continue
            inked++
            if (saturated(c)) coloured++
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        val n = gw * gh
        // Text inks well under a third of a page and is never coloured; a cover is mostly picture.
        val picture = inked > n * PICTURE_INK || coloured > n * PICTURE_COLOUR
        if (!picture || r < l || b < t) return null
        val cells = intArrayOf(l, t, r + 1, b + 1)
        val area = (cells[2] - cells[0]) * (cells[3] - cells[1])
        return if (area >= n * MIN_PICTURE_AREA) cells else null
    }

    /** A page counts as a picture with this much non-white... */
    const val PICTURE_INK = 0.35f

    /** ...or this much clearly coloured (a light cover with colourful art). */
    const val PICTURE_COLOUR = 0.06f

    /** The picture must cover at least this much of the page (not a small logo). */
    const val MIN_PICTURE_AREA = 0.2f

    /**
     * Where part [l, t, r, b) of a [bw] x [bh] bitmap lands when the whole bitmap is drawn into
     * [whole] (whole pixels): the same maths for the page view and the turn pictures.
     */
    fun mapPart(whole: PixelRect, bw: Int, bh: Int, l: Int, t: Int, r: Int, b: Int): PixelRect {
        val x0 = whole.x + (l.toFloat() * whole.w / bw).roundToInt()
        val y0 = whole.y + (t.toFloat() * whole.h / bh).roundToInt()
        val x1 = whole.x + (r.toFloat() * whole.w / bw).roundToInt()
        val y1 = whole.y + (b.toFloat() * whole.h / bh).roundToInt()
        return PixelRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * A cover's dominant colour: colours are grouped (8 levels per channel) and the biggest
     * group wins, with clearly coloured groups favoured over greys, black and white (so the
     * orange of a desert sky beats the black of its lettering). The group's average colour.
     */
    fun dominantColor(px: IntArray): Int {
        if (px.isEmpty()) return 0xFF808080.toInt()
        val count = IntArray(512)
        val sr = LongArray(512)
        val sg = LongArray(512)
        val sb = LongArray(512)
        for (c in px) {
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            val k = (r shr 5 shl 6) or (g shr 5 shl 3) or (b shr 5)
            count[k]++
            sr[k] += r.toLong(); sg[k] += g.toLong(); sb[k] += b.toLong()
        }
        var best = -1
        var bestScore = -1f
        for (k in 0 until 512) {
            val n = count[k]
            if (n == 0) continue
            val r = (sr[k] / n).toInt()
            val g = (sg[k] / n).toInt()
            val b = (sb[k] / n).toInt()
            val sat = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
            val score = n * (GREY_WEIGHT + sat)
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
        val n = count[best]
        return (0xFF shl 24) or ((sr[best] / n).toInt() shl 16) or ((sg[best] / n).toInt() shl 8) or (sb[best] / n).toInt()
    }

    /** How much a grey group counts against a fully saturated one (which counts 1 + this). */
    private const val GREY_WEIGHT = 0.35f
}
