package dev.thorreader.app

import dev.thorreader.app.engine.CoverColors
import dev.thorreader.app.engine.PixelRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Night keeps the front cover's colours; Inspect colours the spine / back from the cover. */
class CoverColorsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val ink = 0xFF2A2A2A.toInt()
    private val orange = 0xFFD9772B.toInt()
    private val black = 0xFF101010.toInt()

    private fun page(w: Int, h: Int, at: (Int, Int) -> Int) = IntArray(w * h) { at(it % w, it / w) }

    @Test
    fun aCoverPictureIsFoundWithoutItsWhiteMargins() {
        // 40 x 60 page: the picture fills rows 10..49, white bars above and below.
        val grid = page(40, 60) { _, y -> if (y in 10 until 50) orange else white }
        assertArrayEquals(intArrayOf(0, 10, 40, 50), CoverColors.pictureCells(grid, 40, 60))
    }

    @Test
    fun aPageOfTextIsNotACover() {
        // Lines of grey text on white: about a fifth of the samples inked, none coloured.
        val grid = page(40, 60) { x, y -> if (y % 5 == 0 && x in 3 until 37) ink else white }
        assertNull(CoverColors.pictureCells(grid, 40, 60))
    }

    @Test
    fun aSmallOrnamentIsNotACover() {
        // A title page with a small coloured logo in the middle.
        val grid = page(40, 60) { x, y -> if (x in 15 until 25 && y in 25 until 35) orange else white }
        assertNull(CoverColors.pictureCells(grid, 40, 60))
    }

    @Test
    fun aLightCoverWithColourfulArtCounts() {
        // Mostly white, but a coloured illustration over the top half and a title.
        val grid = page(40, 60) { x, y -> if (y < 30 && (x + y) % 3 == 0) orange else white }
        val cells = CoverColors.pictureCells(grid, 40, 60)!!
        assertEquals(0, cells[1])
    }

    @Test
    fun partOfThePageMapsOntoTheSamePixelsAsTheWholePage() {
        // A 1080 x 1920 page drawn 1:1: the picture lands exactly where it is in the bitmap.
        assertEquals(PixelRect(0, 200, 1080, 1520), CoverColors.mapPart(PixelRect(0, 0, 1080, 1920), 1080, 1920, 0, 200, 1080, 1720))
        // Scaled by half and offset.
        assertEquals(PixelRect(110, 100, 540, 760), CoverColors.mapPart(PixelRect(110, 0, 540, 960), 1080, 1920, 0, 200, 1080, 1720))
    }

    @Test
    fun dominantColourPrefersTheCoversColourOverBlackLettering() {
        // Dune-like: 40 % orange sky, 45 % black lettering / ground, 15 % white.
        val px = IntArray(100) { i -> when { i < 40 -> orange; i < 85 -> black; else -> white } }
        val c = CoverColors.dominantColor(px)
        assertEquals(0xD9, c shr 16 and 0xFF)
        assertEquals(0x77, c shr 8 and 0xFF)
        assertEquals(0x2B, c and 0xFF)
    }

    @Test
    fun dominantColourOfAPlainCoverIsItsColour() {
        assertEquals(black, CoverColors.dominantColor(IntArray(50) { black }))
    }
}
