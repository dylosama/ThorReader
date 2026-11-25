package dev.thorreader.app

import dev.thorreader.app.engine.PageTurnMath
import dev.thorreader.app.engine.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** v0.3.0 fix: the page a turn lands on is full quality and lines up with the page at rest. */
class LandingTest {

    @Test
    fun landingPageIsAlwaysFullQuality() {
        // Books: both pages full size. Comics / PDF: only the old page (moving away) is lighter.
        assertEquals(1f to 1f, PageTurnMath.textureScales(reflow = true))
        val (old, new) = PageTurnMath.textureScales(reflow = false)
        assertEquals(1f, new)
        assertTrue(old in 0.25f..1f)
    }

    @Test
    fun fitRectCentresAPageThatFits() {
        // A very tall page on the big screen's page slot (1080 x 1920): fits by height.
        assertEquals(PixelRect(140, 0, 800, 1920), PageTurnMath.fitRect(1000f, 2400f, 1080f, 1920f, overflow = false))
        // A comic page there: fits by width, centred vertically.
        val r = PageTurnMath.fitRect(1988f, 3056f, 1080f, 1920f, overflow = false)
        assertEquals(PixelRect(0, ((1920 - 3056f * 1080f / 1988f) / 2f).roundToInt(), 1080, (3056f * 1080f / 1988f).roundToInt()), r)
    }

    @Test
    fun fitRectFillsWidthForOverflowingPages() {
        val r = PageTurnMath.fitRect(1080f, 3000f, 1080f, 1240f, overflow = true, scroll = 200f)
        assertEquals(PixelRect(0, -200, 1080, 3000), r)
    }

    @Test
    fun screenSizedBookPageMapsOneToOne() {
        // Book pages are rendered at the slot size: drawn 1:1, no resampling at all.
        assertEquals(PixelRect(0, 0, 1080, 1240), PageTurnMath.fitRect(1080f, 1240f, 1080f, 1240f, overflow = false))
        assertEquals(PixelRect(0, 0, 1080, 1920), PageTurnMath.fitRect(1080f, 1920f, 1080f, 1920f, overflow = false))
    }

    @Test
    fun textureSetupAtTheStartIsNotASlowCurl() {
        // A Chainsaw Man turn on the Thor: one 36 ms setup frame, then smooth 60 Hz.
        val turn = listOf(36.4f) + List(19) { 16.7f }
        assertEquals(false, PageTurnMath.curlTurnSlow(turn, PageTurnMath.SETUP_FRAMES))
        // Counted from the first frame it would look slow - that switched the curl off before.
        assertTrue(PageTurnMath.percentile(turn, 95f) > PageTurnMath.SLOW_P95_MS)
        // Really slow frames while the page is moving still count.
        val slow = listOf(16.7f, 16.7f) + List(18) { 40f }
        assertEquals(true, PageTurnMath.curlTurnSlow(slow, PageTurnMath.SETUP_FRAMES))
        // Too few frames to tell.
        assertEquals(null, PageTurnMath.curlTurnSlow(List(6) { 50f }, PageTurnMath.SETUP_FRAMES))
    }

    @Test
    fun fitRectIsWholePixelsAndInsideTheArea() {
        for ((bw, bh) in listOf(1000f to 1500f, 1333f to 2001f, 2400f to 1700f, 777f to 1240f)) {
            for ((w, h) in listOf(1080f to 1920f, 1080f to 1240f, 960f to 1080f, 1920f to 1080f)) {
                val r = PageTurnMath.fitRect(bw, bh, w, h, overflow = false)
                assertTrue("$bw x $bh in $w x $h: $r", r.x >= 0 && r.y >= 0)
                assertTrue("$bw x $bh in $w x $h: $r", r.x + r.w <= w.roundToInt() + 1 && r.y + r.h <= h.roundToInt() + 1)
            }
        }
    }
}
