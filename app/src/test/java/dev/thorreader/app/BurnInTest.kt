package dev.thorreader.app

import dev.thorreader.app.engine.BurnIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Burn-in protection: the pixel shift stays tiny, moves in small steps and visits every place. */
class BurnInTest {

    @Test
    fun shiftIsNeverMoreThanTwoPixels() {
        BurnIn.SHIFTS.forEach { (x, y) -> assertTrue("$x,$y", abs(x) <= 2 && abs(y) <= 2) }
    }

    @Test
    fun eachStepIsSmallSoNothingJumps() {
        val n = BurnIn.SHIFTS.size
        for (i in 0 until n) {
            val (x0, y0) = BurnIn.shiftAt(i)
            val (x1, y1) = BurnIn.shiftAt(i + 1) // the last step closes the loop
            assertTrue("step $i", abs(x1 - x0) <= BurnIn.UNIT_PX * 2 && abs(y1 - y0) <= BurnIn.UNIT_PX)
        }
    }

    @Test
    fun theLoopVisitsNineDifferentPlacesAndRepeats() {
        assertEquals(9, BurnIn.SHIFTS.toSet().size)
        assertEquals(BurnIn.shiftAt(0), BurnIn.shiftAt(BurnIn.SHIFTS.size))
        assertEquals(BurnIn.shiftAt(BurnIn.SHIFTS.size - 1), BurnIn.shiftAt(-1))
    }

    @Test
    fun aFullLoopTakesUnderAnHalfHour() {
        // Every place gets the same share of the time, so no pixel edge is favoured for hours.
        assertTrue(BurnIn.SHIFTS.size * BurnIn.SHIFT_EVERY_MS <= 30 * 60_000L)
    }
}
