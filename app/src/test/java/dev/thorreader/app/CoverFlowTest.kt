package dev.thorreader.app

import dev.thorreader.app.engine.FlowGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** The library's cover flow: the view cycle and the turned books' sizes. */
class CoverFlowTest {
    @Test
    fun theViewButtonStepsListGridCoverFlow() {
        assertEquals(LibraryLayout.GRID, LibraryLayout.LIST.next)
        assertEquals(LibraryLayout.FLOW, LibraryLayout.GRID.next)
        assertEquals(LibraryLayout.LIST, LibraryLayout.FLOW.next)
    }

    @Test
    fun spinesAreSeenButNeverHideTheCover() {
        assertEquals(0.06f, FlowGeometry.spineShare(0.008f), 0f) // a thin comic still shows a spine
        assertEquals(0.13f, FlowGeometry.spineShare(0.13f), 0f) // a real novel's thickness
        assertEquals(0.22f, FlowGeometry.spineShare(0.6f), 0f) // a huge book: capped
    }

    @Test
    fun aTurnedBookTakesTheWidthItLooks() {
        // No perspective: plain foreshortening.
        assertEquals(300f * cos(Math.toRadians(34.0)).toFloat(), FlowGeometry.projected(300f, 34f), 0.01f)
        assertEquals(0f, FlowGeometry.projected(300f, 90f), 0.01f)
        assertEquals(300f, FlowGeometry.projected(300f, 0f), 0.01f)
        // With the camera, the far edge comes in. Checked on the Thor: Dune's 332 px cover turned
        // 34 deg is drawn about 210 px wide.
        assertEquals(210f, FlowGeometry.projected(332f, 34f, FlowGeometry.CAMERA_PX), 6f)
        val flat = FlowGeometry.footprint(332f, 70f)
        val seen = FlowGeometry.footprint(332f, 70f, cameraPx = FlowGeometry.CAMERA_PX)
        assertTrue(seen < flat)
        // Cover plus the visible part of the spine.
        assertEquals(FlowGeometry.projected(332f, 34f) + FlowGeometry.projected(70f, 56f), flat, 0.01f)
    }
}
