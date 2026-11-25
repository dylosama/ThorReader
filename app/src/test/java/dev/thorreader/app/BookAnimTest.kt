package dev.thorreader.app

import dev.thorreader.app.engine.BookAnimMath
import dev.thorreader.app.engine.CoverBox
import dev.thorreader.app.engine.SwingAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The book open / close animation: where the cover goes, and when the pages may show. */
class BookAnimTest {
    private val wt = 1920f
    private val ht = 1080f
    private val wb = 1240f
    private val hb = 1080f
    private val aspect = 2f / 3f

    @Test
    fun mode3CoverLiesOnTheFirstPageWithItsSpineOnTheCentre() {
        val ltr = BookAnimMath.place(ReadingMode.TOP, false, wt, ht, wb, hb, aspect)
        assertEquals(SwingAxis.VERTICAL, ltr.axis)
        assertEquals(wt / 2f, ltr.spine, 0.01f)
        assertEquals(ltr.spine, ltr.box.left, 0.01f) // right half, opens to the left
        assertFalse(ltr.onBottom)
        val rtl = BookAnimMath.place(ReadingMode.TOP, true, wt, ht, wb, hb, aspect)
        assertEquals(rtl.spine, rtl.box.right, 0.01f) // right-to-left: left half, opens the other way
        assertTrue(rtl.freeEdgeNegative != ltr.freeEdgeNegative)
        assertTrue(ltr.box.top >= 0f && ltr.box.bottom <= ht && ltr.box.right <= wt)
    }

    @Test
    fun mode4CoverEndsOnTheBottomScreen() {
        val p = BookAnimMath.place(ReadingMode.BOTTOM, false, wt, ht, wb, hb, aspect)
        val (ox, oy) = BookAnimMath.bottomOffset(wt, wb, ht)
        assertTrue(p.onBottom)
        assertEquals(ox + wb / 2f, p.spine, 0.01f)
        assertTrue(p.box.top >= oy && p.box.bottom <= oy + hb)
        assertTrue(p.box.left >= ox && p.box.right <= ox + wb)
        // On the way it crosses the hinge: mid-move the cover is between the screens.
        val from = CoverBox(150f, 400f, 56f * 2.3f, 80f * 2.3f, 0f)
        val mid = BookAnimMath.boxAt((BookAnimMath.LIFT_END + BookAnimMath.HOLD) / 2f, from, p.box)
        assertTrue(mid.cy > from.cy && mid.cy < p.box.cy)
    }

    @Test
    fun modes1And2TurnTheCoverAndHingeItBetweenTheScreens() {
        val m1 = BookAnimMath.place(ReadingMode.BOOK, false, wt, ht, wb, hb, aspect)
        val m2 = BookAnimMath.place(ReadingMode.BOOK_REVERSED, false, wt, ht, wb, hb, aspect)
        assertEquals(90f, m1.box.rot, 0f)
        assertEquals(-90f, m2.box.rot, 0f) // the opposite way for Mode 2
        assertEquals(SwingAxis.HORIZONTAL, m1.axis)
        assertEquals(ht, m1.spine, 0.01f)
        // Turned 90°: its width runs up and down; the spine edge sits on the hinge.
        assertEquals(ht, m1.box.cy + m1.box.w / 2f, 0.5f)
        assertFalse(m1.onBottom)
        assertTrue(m1.box.cx + m1.box.h / 2f <= wt && m1.box.cy - m1.box.w / 2f >= 0f)
        // Right-to-left: the cover sits on the bottom screen and swings up.
        val r1 = BookAnimMath.place(ReadingMode.BOOK, true, wt, ht, wb, hb, aspect)
        assertTrue(r1.onBottom)
        assertEquals(ht, r1.box.cy - r1.box.w / 2f, 0.5f)
        assertTrue(r1.freeEdgeNegative != m1.freeEdgeNegative)
    }

    @Test
    fun timelineLiftMoveHoldSwingReveal() {
        val from = CoverBox(100f, 300f, 130f, 184f, 0f)
        val target = BookAnimMath.place(ReadingMode.BOOK, false, wt, ht, wb, hb, aspect).box
        // Lift: in place, a little bigger.
        val lifted = BookAnimMath.boxAt(BookAnimMath.LIFT_END, from, target)
        assertEquals(from.cx, lifted.cx, 0.01f)
        assertEquals(from.w * BookAnimMath.LIFT_SCALE, lifted.w, 0.01f)
        // Arrives (turned) exactly at the hold point, still closed.
        assertEquals(target, BookAnimMath.boxAt(BookAnimMath.HOLD, from, target))
        assertEquals(0f, BookAnimMath.swingDeg(BookAnimMath.HOLD), 0f)
        assertEquals(180f, BookAnimMath.swingDeg(1f), 0.01f)
        // Pages never show before the cover starts opening; fully at the end.
        assertEquals(0f, BookAnimMath.revealAt(BookAnimMath.HOLD), 0f)
        assertEquals(1f, BookAnimMath.revealAt(1f), 0f)
        // The library dims at the start.
        assertEquals(0f, BookAnimMath.dimAt(0f), 0f)
        assertEquals(BookAnimMath.DIM, BookAnimMath.dimAt(0.4f), 0.001f)
        // Closing is the same motion in reverse.
        assertEquals(1f, BookAnimMath.openState(false, 0f), 0f)
        assertEquals(0.3f, BookAnimMath.openState(false, 0.7f), 0.0001f)
        // Opening waits at the hold point for the first pages; closing never waits.
        assertFalse(BookAnimMath.canPassHold(open = true, pagesReady = false))
        assertTrue(BookAnimMath.canPassHold(open = true, pagesReady = true))
        assertTrue(BookAnimMath.canPassHold(open = false, pagesReady = false))
    }
}
