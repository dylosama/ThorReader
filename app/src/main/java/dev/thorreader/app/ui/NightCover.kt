package dev.thorreader.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import dev.thorreader.app.PageTheme
import dev.thorreader.app.engine.CoverColors
import dev.thorreader.app.engine.PageUnit
import dev.thorreader.app.engine.SliceUnit
import java.util.WeakHashMap

/**
 * Night keeps a book's front cover in its own colours: the book's very first page, when it is a
 * picture (not text), is drawn as is - only the picture, its white margins left as the black
 * Night page - instead of through the Night colour transform.
 */
object NightCover {
    private val NOT_A_COVER = Rect()

    /** Per rendered bitmap (dropped with it): the picture's bounds, or [NOT_A_COVER]. */
    private val found = WeakHashMap<Bitmap, Rect>()

    /** The cover picture's bounds in [bmp] pixels, or null: draw the page through the theme. */
    fun bounds(unit: PageUnit, bmp: Bitmap, theme: PageTheme): Rect? {
        if (theme != PageTheme.NIGHT || !isFirstPage(unit)) return null
        if (bmp.isRecycled || bmp.config == Bitmap.Config.HARDWARE) return null
        val r = synchronized(found) { found[bmp] } ?: detect(bmp).also { synchronized(found) { found[bmp] = it } }
        return r.takeIf { it !== NOT_A_COVER }
    }

    /** The first page of a reflowable book: the start of its first section. */
    private fun isFirstPage(unit: PageUnit) = unit is SliceUnit && unit.chapter == 0 && unit.fracStart == 0f

    private fun detect(bmp: Bitmap): Rect {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return NOT_A_COVER
        // A grid of samples, about every 4 px (a 1080 x 1920 page: 270 x 480).
        val step = 4
        val gw = w / step
        val gh = h / step
        val grid = IntArray(gw * gh)
        val row = IntArray(w)
        for (gy in 0 until gh) {
            bmp.getPixels(row, 0, w, 0, gy * step + step / 2, w, 1)
            for (gx in 0 until gw) grid[gy * gw + gx] = row[gx * step + step / 2]
        }
        val c = CoverColors.pictureCells(grid, gw, gh) ?: return NOT_A_COVER
        return Rect(c[0] * step, c[1] * step, minOf(w, c[2] * step), minOf(h, c[3] * step))
    }
}
