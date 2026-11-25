package dev.thorreader.app

import dev.thorreader.app.engine.Curl
import dev.thorreader.app.engine.PageTurnMath
import dev.thorreader.app.engine.RegionRole
import dev.thorreader.app.engine.Side
import dev.thorreader.app.engine.TurnCause
import dev.thorreader.app.engine.TurnSpeed
import dev.thorreader.app.engine.TurnStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** Step 22: page-turn geometry and timing. */
class PageTurnTest {
    private val w = 1080f
    private val h = 1920f
    private val r = 100f

    // ---- curl geometry ----

    @Test
    fun flatPartStaysWhereItIs() {
        val c = Curl(a = 600f, theta = 0f, r = r)
        val p = PageTurnMath.project(300f, 500f, c, h)
        assertEquals(300f, p.x, 0.01f)
        assertEquals(500f, p.y, 0.01f)
        assertEquals(0f, p.z, 0.01f)
    }

    @Test
    fun pointsWrapAroundTheCylinderAndFoldBack() {
        val c = Curl(a = 600f, theta = 0f, r = r)
        // A quarter of the way round: on top of the axis + r, at height r.
        val quarter = PageTurnMath.project(600f + (PI * r / 2).toFloat(), 900f, c, h)
        assertEquals(600f + r, quarter.x, 0.5f)
        assertEquals(r, quarter.z, 0.5f)
        // Half way round: back over the axis, at the top of the cylinder (2r).
        val half = PageTurnMath.project(600f + (PI * r).toFloat(), 900f, c, h)
        assertEquals(600f, half.x, 0.5f)
        assertEquals(2 * r, half.z, 0.5f)
        // Beyond: the folded flap lies flat on top, travelling back toward the spine.
        val flap = PageTurnMath.project(600f + (PI * r).toFloat() + 200f, 900f, c, h)
        assertEquals(400f, flap.x, 0.5f)
        assertEquals(2 * r, flap.z, 0.5f)
    }

    @Test
    fun theGrabbedCornerFollowsTheFinger() {
        // Straight drag from the middle of the free edge, and a diagonal one from the bottom corner.
        for ((corner, finger) in listOf((w to 960f) to (500f to 960f), (w to h) to (560f to 1650f), (w to 0f) to (700f to 200f))) {
            val c = PageTurnMath.curlFromDrag(corner.first, corner.second, finger.first, finger.second, r, h)
            assertNotNull(c)
            val p = PageTurnMath.project(corner.first, corner.second, c!!, h)
            assertEquals("corner x for $corner -> $finger", finger.first, p.x, 1.5f)
            assertEquals("corner y for $corner -> $finger", finger.second, p.y, 1.5f)
        }
        // Bottom corner lifts first: the axis tilts toward it.
        val diag = PageTurnMath.curlFromDrag(w, h, 560f, 1650f, r, h)!!
        assertTrue(diag.theta > 0f)
        // A very steep drag is capped (the page never folds absurdly).
        assertEquals(PageTurnMath.MAX_THETA, PageTurnMath.curlFromDrag(w, h, 900f, 600f, r, h)!!.theta, 0.0001f)
        // Top / middle / bottom thirds choose the corner.
        assertEquals(0f, PageTurnMath.grabbedCornerY(100f, h), 0f)
        assertEquals(h, PageTurnMath.grabbedCornerY(1800f, h), 0f)
        assertEquals(960f, PageTurnMath.grabbedCornerY(960f, h), 0f)
    }

    @Test
    fun aSinglePageIsFlatAtTheStartAndGoneAtTheEnd() {
        val start = Curl(PageTurnMath.axisAt(RegionRole.SINGLE, 0f, w, r), 0f, r)
        for (x in listOf(0f, 500f, w)) assertEquals(x, PageTurnMath.project(x, 10f, start, h).x, 0.01f)
        val end = Curl(PageTurnMath.axisAt(RegionRole.SINGLE, 1f, w, r), 0f, r)
        for (x in listOf(0f, 1f, 300f, 700f, w)) assertTrue("x=$x still visible", PageTurnMath.project(x, 10f, end, h).x < 0f)
        // Backward plays the same motion in reverse.
        assertEquals(PageTurnMath.axisAt(RegionRole.SINGLE, 0.3f, w, r, true), PageTurnMath.axisAt(RegionRole.SINGLE, 0.7f, w, r, false), 0.01f)
    }

    @Test
    fun dragProgressIsTheInverseOfTheAnimation() {
        for (t in listOf(0f, 0.1f, 0.25f, 0.4f, 0.5f)) {
            val a = PageTurnMath.axisAt(RegionRole.SOURCE, t, w, r)
            assertEquals(t, PageTurnMath.progressForAxis(RegionRole.SOURCE, a, w, r), 0.001f)
        }
        for (t in listOf(0f, 0.3f, 0.8f, 1f)) {
            val a = PageTurnMath.axisAt(RegionRole.SINGLE, t, w, r)
            assertEquals(t, PageTurnMath.progressForAxis(RegionRole.SINGLE, a, w, r), 0.001f)
        }
    }

    // ---- the hinge handoff (book modes: one sheet across both screens) ----

    @Test
    fun hingeHandoffMatchesInPositionAndSpeed() {
        val wTop = 1080f
        val wBottom = 1080f
        val hand = PageTurnMath.HANDOFF
        // Both screens have the fold exactly at the hinge at the handoff.
        assertEquals(0f, PageTurnMath.axisAt(RegionRole.SOURCE, hand, wTop, r), 0.001f)
        assertEquals(0f, PageTurnMath.axisAt(RegionRole.DEST, hand, wBottom, r), 0.001f)
        // Flat at both ends.
        assertEquals(wTop, PageTurnMath.axisAt(RegionRole.SOURCE, 0f, wTop, r), 0.001f)
        assertEquals(wBottom, PageTurnMath.axisAt(RegionRole.DEST, 1f, wBottom, r), 0.001f)
        // Phase 1 the destination's sheet is still beyond the hinge; phase 2 the source's is gone past it.
        assertTrue(PageTurnMath.axisAt(RegionRole.DEST, 0.2f, wBottom, r) < 0f)
        assertTrue(PageTurnMath.axisAt(RegionRole.SOURCE, 0.8f, wTop, r) < 0f)
        // Same speed toward / away from the hinge on either side of the handoff.
        val e = 0.001f
        val before = (PageTurnMath.axisAt(RegionRole.SOURCE, hand - e, wTop, r) - 0f) / e
        val after = (PageTurnMath.axisAt(RegionRole.DEST, hand + e, wBottom, r) - 0f) / e
        assertEquals(before, after, 1f)
        // Local progress: phase 1 then phase 2.
        assertEquals(1f, PageTurnMath.localProgress(RegionRole.SOURCE, hand), 0f)
        assertEquals(0f, PageTurnMath.localProgress(RegionRole.DEST, hand), 0f)
        assertEquals(0.5f, PageTurnMath.localProgress(RegionRole.DEST, 0.75f), 0.001f)
    }

    @Test
    fun easingIsSmoothAndEndsExactly() {
        assertEquals(0f, PageTurnMath.easeInOut(0f), 0f)
        assertEquals(1f, PageTurnMath.easeInOut(1f), 0f)
        assertEquals(0.5f, PageTurnMath.easeInOut(0.5f), 0.001f)
        var last = 0f
        for (i in 1..100) {
            val v = PageTurnMath.easeInOut(i / 100f)
            assertTrue(v >= last)
            last = v
        }
        // The two halves meet without a jump (one easing over the whole turn).
        assertTrue(abs(PageTurnMath.easeInOut(0.4999f) - PageTurnMath.easeInOut(0.5001f)) < 0.001f)
    }

    // ---- right-to-left mirroring ----

    @Test
    fun rightToLeftMirrorsEverything() {
        // Forward: left-to-right starts on the right, right-to-left on the left; backward the opposite.
        assertEquals(Side.RIGHT, PageTurnMath.startSide(forward = true, rtl = false))
        assertEquals(Side.LEFT, PageTurnMath.startSide(forward = true, rtl = true))
        assertEquals(Side.LEFT, PageTurnMath.startSide(forward = false, rtl = false))
        assertEquals(Side.RIGHT, PageTurnMath.startSide(forward = false, rtl = true))
        // Mode 1 forward: LTR the right screen is the source; RTL the left screen is.
        val ltr = PageTurnMath.startSide(true, false)
        val rtl = PageTurnMath.startSide(true, true)
        assertEquals(RegionRole.SOURCE, PageTurnMath.roleOf(Side.RIGHT, ltr))
        assertEquals(RegionRole.DEST, PageTurnMath.roleOf(Side.LEFT, ltr))
        assertEquals(RegionRole.SOURCE, PageTurnMath.roleOf(Side.LEFT, rtl))
        assertEquals(RegionRole.DEST, PageTurnMath.roleOf(Side.RIGHT, rtl))
        // The free (lifting) edge is the outer edge; a single page lifts on the next-page side.
        assertTrue(PageTurnMath.freeEdgeLeft(RegionRole.SOURCE, Side.LEFT, rtl = true))
        assertFalse(PageTurnMath.freeEdgeLeft(RegionRole.SOURCE, Side.RIGHT, rtl = false))
        assertTrue(PageTurnMath.freeEdgeLeft(RegionRole.DEST, Side.LEFT, rtl = false))
        assertTrue(PageTurnMath.freeEdgeLeft(RegionRole.SINGLE, null, rtl = true))
        assertFalse(PageTurnMath.freeEdgeLeft(RegionRole.SINGLE, null, rtl = false))
        // Which page is the moving sheet.
        assertTrue(PageTurnMath.sheetIsOld(RegionRole.SOURCE, true))
        assertFalse(PageTurnMath.sheetIsOld(RegionRole.DEST, true))
        assertFalse(PageTurnMath.sheetIsOld(RegionRole.SINGLE, forward = false))
    }

    // ---- completing a drag ----

    @Test
    fun completionThresholdAndFlick() {
        assertFalse(PageTurnMath.shouldComplete(0.34f, 0f))
        assertTrue(PageTurnMath.shouldComplete(0.36f, 0f))
        assertTrue(PageTurnMath.shouldComplete(0.1f, 1500f)) // a quick flick
        assertFalse(PageTurnMath.shouldComplete(0.6f, -1500f)) // flicked back
        assertFalse(PageTurnMath.shouldComplete(0.9f, -Float.MAX_VALUE)) // cancelled (second finger)
    }

    // ---- which style is used ----

    @Test
    fun styleChoice() {
        fun pick(cause: TurnCause, steps: Int = 1, pref: TurnStyle = TurnStyle.CURL, low: Boolean = false, curl: Boolean = true) =
            PageTurnMath.chooseStyle(cause, steps, pref, TurnSpeed.NORMAL, low, curl)
        assertEquals(TurnStyle.CURL to 350, pick(TurnCause.NORMAL))
        assertEquals(TurnStyle.SLIDE to 120, pick(TurnCause.REPEAT))
        assertEquals(TurnStyle.SLIDE to 120, pick(TurnCause.JUMP, steps = -1))
        assertEquals(TurnStyle.NONE to 0, pick(TurnCause.JUMP, steps = 12))
        assertEquals(TurnStyle.FADE to 350, pick(TurnCause.NORMAL, low = true))
        assertEquals(TurnStyle.SLIDE to 350, pick(TurnCause.NORMAL, curl = false))
        assertEquals(TurnStyle.NONE to 0, pick(TurnCause.NORMAL, pref = TurnStyle.NONE))
        assertEquals(TurnStyle.NONE to 0, pick(TurnCause.REPEAT, pref = TurnStyle.NONE))
        assertEquals(TurnStyle.FLIP to 600, PageTurnMath.chooseStyle(TurnCause.NORMAL, 1, TurnStyle.FLIP, TurnSpeed.SLOW, false, true))
    }

    @Test
    fun spreadsFoldAtTheCentreOnlyWhenBothViewsAreTwoPages() {
        assertTrue(PageTurnMath.hasHinge(bookMode = true, oldTwoPages = false, newTwoPages = false))
        assertTrue(PageTurnMath.hasHinge(bookMode = false, oldTwoPages = true, newTwoPages = true))
        assertFalse(PageTurnMath.hasHinge(bookMode = false, oldTwoPages = true, newTwoPages = false))
    }

    @Test
    fun radiusIsTheSamePhysicalSizeOnBothScreens() {
        // Top screen ~ 367 dpi, bottom ~ 420 dpi: same inches -> different pixels.
        val top = PageTurnMath.radiusPx(367f, 1080f)
        val bottom = PageTurnMath.radiusPx(420f, 1080f)
        assertEquals(top / 367f, bottom / 420f, 0.001f)
    }
}
