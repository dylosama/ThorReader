package dev.thorreader.app

import dev.thorreader.app.engine.FixedUnit
import dev.thorreader.app.engine.LayoutConfig
import dev.thorreader.app.engine.Pagination
import dev.thorreader.app.engine.Part
import dev.thorreader.app.engine.ScreenMetrics
import dev.thorreader.app.engine.SlotSize
import dev.thorreader.app.engine.SplitWide
import dev.thorreader.app.engine.isSplitSpread
import dev.thorreader.app.engine.paginateFixed
import dev.thorreader.app.engine.screenPages
import dev.thorreader.app.engine.spreadDecision
import dev.thorreader.app.engine.typicalRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.2.1: which pages are spreads, and swapping a split spread shows it whole. */
class SpreadTest {

    @Test
    fun onlyClearDoublePagesAreSplitOnAuto() {
        val manga = typicalRatio(listOf(0.7f, 0.7f, 0.71f, 1.42f, 0.69f))
        assertEquals(0.7f, manga, 0.01f)
        assertTrue("a real double page", spreadDecision(1.42f, manga, SplitWide.AUTO))
        assertFalse("a single page with wide margins", spreadDecision(0.9f, manga, SplitWide.AUTO))
        assertFalse("a slightly wide page/panel (1.15)", spreadDecision(1.15f, manga, SplitWide.AUTO))
        // A landscape book (every page wide): nothing is split on Auto.
        val landscape = typicalRatio(listOf(1.33f, 1.33f, 1.4f, 1.33f))
        assertFalse(spreadDecision(1.4f, landscape, SplitWide.AUTO))
        // Always / Never.
        assertTrue(spreadDecision(1.1f, manga, SplitWide.ALWAYS))
        assertTrue(spreadDecision(1.4f, landscape, SplitWide.ALWAYS))
        assertFalse(spreadDecision(0.8f, manga, SplitWide.ALWAYS))
        assertFalse(spreadDecision(2.0f, manga, SplitWide.NEVER))
    }

    @Test
    fun swappedSpreadIsWholeOnBothScreens() {
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.BOOK_REVERSED)) for (rtl in listOf(false, true)) {
            val cfg = LayoutConfig.build(mode, rtl, ScreenMetrics.DEFAULT, 10f, 1, false)
            val p = Pagination(cfg, paginateFixed(6, { it == 2 }, { null }, cfg), 1)
            val i = p.views.indexOfFirst { isSplitSpread(it) }
            assertTrue("$mode rtl=$rtl has a split spread", i >= 0)
            // Normal: each screen shows one half.
            val halves = Screen.entries.map { (screenPages(p, i, mode, it).single().first as FixedUnit).part }.toSet()
            assertEquals(setOf(Part.LEFT, Part.RIGHT), halves)
            // Swapped: the whole page, each screen at its own size (big screen = 1080x1920).
            val top = screenPages(p, i, mode, Screen.TOP, swapped = true).single()
            val bottom = screenPages(p, i, mode, Screen.BOTTOM, swapped = true).single()
            assertEquals(FixedUnit(2, Part.WHOLE), top.first.let { FixedUnit((it as FixedUnit).page, it.part) })
            assertEquals(Part.WHOLE, (bottom.first as FixedUnit).part)
            assertEquals(SlotSize(1080, 1920), top.second)
            assertEquals(SlotSize(1080, 1240), bottom.second)
        }
    }
}
