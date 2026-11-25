package dev.thorreader.app

import dev.thorreader.app.engine.PageTurnMath
import dev.thorreader.app.engine.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swiping a page in Flip (or Slide / Fade) responds the same near a corner as in the middle. */
class DragStartTest {
    // The big screen's page in book mode: 1080 x 1920 px, free edge at x = 1080.
    private val w = 1080f
    private val h = 1920f
    private val r = PageTurnMath.radiusPx(420f, w)

    private fun progress(cornerCurl: Boolean, touchY: Float, travel: Float, drift: Float = 0f): Float {
        val c = PageTurnMath.dragFold(cornerCurl, w, h, touchY, w - 40f - travel, touchY + drift, r) ?: return 0f
        return PageTurnMath.progressForAxis(RegionRole.SOURCE, c.a, w, r)
    }

    @Test
    fun flipStrokeNearACornerTurnsAsReadilyAsInTheMiddle() {
        for (travel in listOf(60f, 150f, 300f, 600f)) {
            val middle = progress(false, h / 2f, travel)
            assertTrue("moves at $travel px", middle > 0f)
            // Top and bottom thirds (the corners), even with the finger drifting sideways.
            assertEquals(middle, progress(false, 120f, travel, drift = 80f), 1e-4f)
            assertEquals(middle, progress(false, h - 120f, travel, drift = -80f), 1e-4f)
        }
    }

    @Test
    fun curlStillPeelsTheGrabbedCorner() {
        // The curl keeps its corner peel (the fold is tilted), unchanged.
        val c = PageTurnMath.dragFold(true, w, h, 120f, w - 300f, 300f, r)!!
        assertTrue(c.theta != 0f)
        // Before the fix Flip used this: from a corner the page barely moved for a long stroke.
        assertTrue(PageTurnMath.progressForAxis(RegionRole.SOURCE, c.a, w, r) < progress(false, 120f, 260f))
    }
}
