package dev.thorreader.app

import dev.thorreader.app.engine.LinePaginator
import dev.thorreader.app.io.ImageHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PaginationTest {

    /** Lines of a paragraph whose boxes overlap slightly (box 14pt tall, line spacing 12pt). */
    private fun overlappingLines(count: Int, start: Float = 0f, spacing: Float = 12f, box: Float = 14f) =
        List(count) { i -> floatArrayOf(start + i * spacing, start + i * spacing + box) }

    @Test
    fun overlappingLinesStayIndividualRows() {
        // The old code merged these into one block and then cut it at a fixed height.
        val rows = LinePaginator.rows(overlappingLines(50))
        assertEquals(50, rows.size)
    }

    @Test
    fun noLineIsEverCut_equalAndUnequalPageHeights() {
        val lines = overlappingLines(400, start = 5f) + overlappingLines(300, start = 5000f, spacing = 13f, box = 12f)
        val rows = LinePaginator.rows(lines)
        for (heights in listOf(floatArrayOf(200f), floatArrayOf(360f, 230f), floatArrayOf(230f, 360f))) {
            val spans = LinePaginator.pages(rows, 0) { k -> heights[k % heights.size] }
            // Pages are contiguous and in order.
            for (i in 1 until spans.size) assertTrue(spans[i].y0 >= spans[i - 1].y1 - 0.01f)
            spans.forEachIndexed { k, s ->
                assertTrue("page $k too tall", s.y1 - s.y0 <= heights[k % heights.size] + 0.01f)
                assertEquals(0f, s.overflow)
                // Every row is either wholly before the cut or after it (allowing the tiny
                // overlap between neighbouring line boxes, split at its midpoint).
                for (r in 0 until rows.size) {
                    val t = rows.tops[r]
                    val b = rows.bottoms[r]
                    val cut = s.y1
                    val straddles = t < cut - 1.01f && b > cut + 1.01f
                    assertTrue("row $r ($t..$b) cut at $cut on page $k", !straddles)
                }
            }
            // Every row ends up on some page.
            assertTrue(spans.last().y1 >= rows.bottoms.last() - 0.01f)
        }
    }

    @Test
    fun sideBySideItemsAreOneRow() {
        // An image 0..100 with three text lines next to it, then a normal line.
        val items = listOf(floatArrayOf(0f, 100f), floatArrayOf(2f, 16f), floatArrayOf(30f, 44f), floatArrayOf(60f, 74f), floatArrayOf(110f, 124f))
        val rows = LinePaginator.rows(items)
        assertEquals(2, rows.size)
        assertEquals(100f, rows.bottoms[0])
    }

    @Test
    fun rowTallerThanPageOverflowsInsteadOfBeingCut() {
        val items = listOf(floatArrayOf(0f, 14f), floatArrayOf(20f, 520f), floatArrayOf(530f, 544f))
        val spans = LinePaginator.pages(LinePaginator.rows(items), 0) { 300f }
        assertEquals(3, spans.size)
        assertEquals(0f, spans[0].overflow)
        assertEquals(20f, spans[1].y0)
        assertEquals(520f, spans[1].y1)
        assertEquals(200f, spans[1].overflow)
        assertEquals(0f, spans[2].overflow)
    }

    @Test
    fun imageHeaders() {
        val png = ByteArray(33).also {
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10).copyInto(it)
            // IHDR width 1600, height 2400 at offsets 16 / 20
            byteArrayOf(0, 0, 0x06, 0x40, 0, 0, 0x09, 0x60).copyInto(it, 16)
        }
        assertEquals(1600 to 2400, ImageHeader.size(ByteArrayInputStream(png)))

        val gif = "GIF89a".toByteArray() + byteArrayOf(0x20, 0x03, 0x58, 0x02) + ByteArray(20)
        assertEquals(800 to 600, ImageHeader.size(ByteArrayInputStream(gif)))

        // JPEG: SOI, an APP1 segment of 100 bytes, then SOF0 with height 1500, width 1000.
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 102) + ByteArray(100)
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, 0x05, 0xDC.toByte(), 0x03, 0xE8.toByte()) + ByteArray(12)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + sof
        assertEquals(1000 to 1500, ImageHeader.size(ByteArrayInputStream(jpeg)))
    }
}
