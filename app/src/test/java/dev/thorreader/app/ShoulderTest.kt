package dev.thorreader.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step 18: what L1/R1 do. */
class ShoulderTest {
    private fun next(right: Boolean, mode: ReadingMode, rtl: Boolean, swap: Boolean) =
        ShoulderButtons.meaning(ShoulderButtons.turn(right, mode, rtl, swap), rtl)

    @Test
    fun mangaSwapOn() {
        // Mode 1: R1 next. Mode 2: L1 next. Modes 3/4: R1 next.
        assertEquals(Turn.NEXT, next(true, ReadingMode.BOOK, rtl = true, swap = true))
        assertEquals(Turn.PREVIOUS, next(false, ReadingMode.BOOK, rtl = true, swap = true))
        assertEquals(Turn.NEXT, next(false, ReadingMode.BOOK_REVERSED, rtl = true, swap = true))
        assertEquals(Turn.PREVIOUS, next(true, ReadingMode.BOOK_REVERSED, rtl = true, swap = true))
        for (m in listOf(ReadingMode.TOP, ReadingMode.BOTTOM)) {
            assertEquals(Turn.NEXT, next(true, m, rtl = true, swap = true))
            assertEquals(Turn.PREVIOUS, next(false, m, rtl = true, swap = true))
        }
    }

    @Test
    fun mangaSwapOffFollowsTheScreen() {
        // Right-to-left: the page on the right is the previous one, so R1 goes back.
        for (m in ReadingMode.entries) {
            assertEquals(Turn.PAGE_RIGHT, ShoulderButtons.turn(true, m, rtl = true, mangaSwap = false))
            assertEquals(Turn.PREVIOUS, next(true, m, rtl = true, swap = false))
            assertEquals(Turn.NEXT, next(false, m, rtl = true, swap = false))
        }
    }

    @Test
    fun leftToRightUnchanged() {
        // LTR: R1 = next, L1 = previous in all four modes, whatever the toggle says.
        for (m in ReadingMode.entries) for (swap in listOf(true, false)) {
            assertEquals(Turn.NEXT, next(true, m, rtl = false, swap = swap))
            assertEquals(Turn.PREVIOUS, next(false, m, rtl = false, swap = swap))
        }
    }
}
