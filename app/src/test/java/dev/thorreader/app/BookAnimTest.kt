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
    fun aTiltedCoverStartsTiltedAtItsRealSize() {
        // A 200 x 300 cover turned 30 degrees clockwise around (500, 400).
        val a = Math.toRadians(30.0)
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        val local = listOf(-100f to -150f, 100f to -150f, 100f to 150f, -100f to 150f) // TL, TR, BR, BL
        val xs = FloatArray(4) { 500f + local[it].first * c - local[it].second * s }
        val ys = FloatArray(4) { 400f + local[it].first * s + local[it].second * c }
        val box = BookAnimMath.boxFromQuad(xs, ys)
        assertEquals(500f, box.cx, 0.01f)
        assertEquals(400f, box.cy, 0.01f)
        assertEquals(200f, box.w, 0.01f)
        assertEquals(300f, box.h, 0.01f)
        assertEquals(30f, box.rot, 0.01f)
        // Upright: no turn.
        assertEquals(0f, BookAnimMath.boxFromQuad(floatArrayOf(0f, 10f, 10f, 0f), floatArrayOf(0f, 0f, 20f, 20f)).rot, 0.01f)
    }

    @Test
    fun aBookOnTheBottomScreenStartsBelowTheTopOne() {
        // Inspect's 3D book in a series' collection (bottom screen): the open animation lifts it
        // from there - below the top screen in the shared space, centred like the bottom screen.
        val v = BookAnimMath.bottomToVirtual(500f, 300f, 740f, 700f, wt, wb, ht)
        assertEquals(500f + (wt - wb) / 2f, v[0], 0.01f)
        assertEquals(300f + ht, v[1], 0.01f)
        assertEquals(740f + (wt - wb) / 2f, v[2], 0.01f)
        assertEquals(700f + ht, v[3], 0.01f)
        assertTrue(v[1] >= ht) // entirely below the top screen: it rises up across the hinge
    }

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

    @Test
    fun hingeStyleCentresTurnsTravelsToTheHingeAndOpensWhileArriving() {
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.BOOK_REVERSED)) {
            val p = BookAnimMath.place(mode, false, wt, ht, wb, hb, aspect)
            val from = CoverBox(120f, 360f, 130f, 184f, 0f)
            fun box(s: Float) = BookAnimMath.hingeBoxAt(s, from, p.box, wt, ht)
            // 1) at the centre of the top screen, upright;
            val c = box(BookAnimMath.H_GLIDE_END)
            assertEquals(wt / 2f, c.cx, 0.5f)
            assertEquals(ht / 2f, c.cy, 0.5f)
            assertEquals(0f, c.rot, 0.001f)
            // 2) turned sideways for this mode, still at the centre;
            val r = box(BookAnimMath.H_ROTATE_END)
            assertEquals(p.box.rot, r.rot, 0.001f)
            assertEquals(ht / 2f, r.cy, 0.5f)
            // 3) then the spine lands on the hinge (the top screen's bottom edge).
            val t = box(BookAnimMath.H_TRAVEL_END)
            assertEquals(ht, BookAnimMath.spineOf(t, p), 0.5f)
            // Travelling down, spine side leading: the spine moves toward the hinge all the way.
            var last = BookAnimMath.spineOf(r, p)
            for (i in 1..20) {
                val sp = BookAnimMath.spineOf(box(BookAnimMath.H_ROTATE_END + i * (BookAnimMath.H_TRAVEL_END - BookAnimMath.H_ROTATE_END) / 20f), p)
                assertTrue(sp >= last - 0.01f)
                last = sp
            }
        }
        // 4) opening starts 80 % into the travel (overlapping it), pages never before.
        assertEquals(0.712f, BookAnimMath.H_OPEN_START, 0.001f)
        assertEquals(0f, BookAnimMath.swingDegFor(BookAnimMath.H_OPEN_START, hinge = true), 0f)
        assertTrue(BookAnimMath.swingDegFor(BookAnimMath.H_TRAVEL_END, hinge = true) > 0f)
        assertEquals(180f, BookAnimMath.swingDegFor(1f, hinge = true), 0.01f)
        assertEquals(0f, BookAnimMath.revealFor(BookAnimMath.H_OPEN_START, hinge = true), 0f)
        assertEquals(BookAnimMath.H_OPEN_START, BookAnimMath.holdPoint(true), 0f)
        assertEquals(BookAnimMath.HOLD, BookAnimMath.holdPoint(false), 0f)
        // No visible stops: the box changes continuously across the phase boundaries.
        val p = BookAnimMath.place(ReadingMode.BOOK, false, wt, ht, wb, hb, aspect)
        val from = CoverBox(120f, 360f, 130f, 184f, 0f)
        for (edge in listOf(BookAnimMath.H_GLIDE_END, BookAnimMath.H_ROTATE_END)) {
            val a = BookAnimMath.hingeBoxAt(edge - 0.0005f, from, p.box, wt, ht)
            val b = BookAnimMath.hingeBoxAt(edge + 0.0005f, from, p.box, wt, ht)
            assertEquals(a.cx, b.cx, 1f)
            assertEquals(a.cy, b.cy, 1f)
            assertEquals(a.h, b.h, 1f)
        }
    }
}
