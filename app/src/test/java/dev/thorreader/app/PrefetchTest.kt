package dev.thorreader.app

import dev.thorreader.app.engine.BlankUnit
import dev.thorreader.app.engine.LayoutConfig
import dev.thorreader.app.engine.Pagination
import dev.thorreader.app.engine.ScreenMetrics
import dev.thorreader.app.engine.SlotSize
import dev.thorreader.app.engine.paginateFixed
import dev.thorreader.app.engine.prefetchOrder
import dev.thorreader.app.engine.prefetchRequests
import dev.thorreader.app.engine.screenPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.1: the pages pre-rendered for a view must be exactly what each screen will draw (same
 * page, same slot size), in right-to-left as well as left-to-right, so a forward turn never
 * decodes on demand (the small screen went black in RTL manga).
 */
class PrefetchTest {
    private val top = SlotSize(1080, 1920)
    private val bottom = SlotSize(1080, 1240)

    private fun comic(mode: ReadingMode, rtl: Boolean, spreads: Set<Int> = emptySet(), two: Boolean = false): Pagination {
        val cfg = LayoutConfig.build(mode, rtl, ScreenMetrics.DEFAULT, 10f, 1, two)
        return Pagination(cfg, paginateFixed(20, { it in spreads }, { null }, cfg), 1)
    }

    @Test
    fun nextViewComesRightAfterTheCurrentOne() {
        assertEquals(listOf(4, 5, 3, 6, 2), prefetchOrder(4, 20))
        assertEquals(listOf(0, 1, 2), prefetchOrder(0, 20))
        assertEquals(listOf(9, 8, 7), prefetchOrder(9, 10))
        // Views are stored in reading order, so RTL needs no special case: +1 is the next view.
        val p = comic(ReadingMode.BOOK, rtl = true)
        val first = (p.views[0].units + p.views[1].units).filterIsInstance<dev.thorreader.app.engine.FixedUnit>().map { it.page }
        assertEquals(listOf(0, 1, 2, 3), first)
    }

    @Test
    fun everyPageAScreenShowsIsPreRenderedAtThatSize() {
        for (mode in ReadingMode.entries) for (rtl in listOf(false, true)) for (two in listOf(false, true)) {
            for (spreads in listOf(emptySet(), setOf(5, 9))) {
                val p = comic(mode, rtl, spreads, two)
                for (i in p.views.indices) {
                    val planned = prefetchRequests(p, i).toSet()
                    for (screen in Screen.entries) {
                        for ((u, slot) in screenPages(p, i, mode, screen)) {
                            if (u is BlankUnit) continue
                            assertTrue("$mode rtl=$rtl two=$two spreads=$spreads view $i $screen: $u @ $slot", (u to slot) in planned)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rightToLeftBookModeNextViewSmallScreen() {
        // The reported case: RTL manga in Mode 1, going forward. The bottom (small) screen's page
        // of the NEXT view is planned at the bottom screen's own size.
        val p = comic(ReadingMode.BOOK, rtl = true)
        val (unit, slot) = screenPages(p, 3, ReadingMode.BOOK, Screen.BOTTOM).single()
        assertEquals(bottom, slot)
        assertTrue((unit to bottom) in prefetchRequests(p, 3))
        assertEquals(top, screenPages(p, 3, ReadingMode.BOOK, Screen.TOP).single().second)
        // Mode 2 mirrors it.
        val p2 = comic(ReadingMode.BOOK_REVERSED, rtl = true)
        assertEquals(bottom, screenPages(p2, 3, ReadingMode.BOOK_REVERSED, Screen.BOTTOM).single().second)
    }
}
