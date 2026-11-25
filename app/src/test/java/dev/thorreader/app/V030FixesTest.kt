package dev.thorreader.app

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.thorreader.app.ui.BesideAnchor
import dev.thorreader.app.ui.INSPECT_HINT
import dev.thorreader.app.ui.InspectGestures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.3.0 fixes: Inspect's tap to read, and the book popup that always fits the screen. */
class V030FixesTest {

    @Test
    fun aQuickStillTapReadsAnythingElseSpins() {
        val slop = 18f
        assertTrue(InspectGestures.isTap(movedPx = 0f, heldMs = 80, slop = slop))
        assertTrue(InspectGestures.isTap(movedPx = 10f, heldMs = 300, slop = slop))
        assertFalse("moved: a spin", InspectGestures.isTap(movedPx = 40f, heldMs = 80, slop = slop))
        assertFalse("held: not a tap", InspectGestures.isTap(movedPx = 2f, heldMs = 600, slop = slop))
        assertTrue(INSPECT_HINT.startsWith("Tap the book to read · Drag to spin"))
        assertFalse(INSPECT_HINT.contains("double-tap", ignoreCase = true))
    }

    @Test
    fun thePopupStaysOnScreenNearTheEdges() {
        val window = IntSize(1920, 1080)
        val popup = IntSize(575, 900)
        fun place(anchor: IntRect) = BesideAnchor.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)
        fun onScreen(p: androidx.compose.ui.unit.IntOffset) =
            p.x >= 0 && p.y >= 0 && p.x + popup.width <= window.width && p.y + popup.height <= window.height
        // The last list row's mode button (bottom right): opens to the left and upwards.
        val lastRow = place(IntRect(1745, 940, 1865, 1060))
        assertTrue("$lastRow", onScreen(lastRow))
        assertTrue("left of the button", lastRow.x + popup.width <= 1745)
        // The last grid row's cover (bottom left).
        assertTrue(onScreen(place(IntRect(48, 900, 276, 1270))))
        // Near the top: level with the button.
        val top = place(IntRect(1745, 300, 1865, 420))
        assertTrue(onScreen(top))
        assertTrue(top.y == 16 || top.y == 300 || top.y <= 300)
    }

    @Test
    fun thePopupScrollsKeepsRemovePinnedAndHasNoMangaToggle() {
        val f = listOf(File("src/main/java/dev/thorreader/app/ui/Library.kt"), File("app/src/main/java/dev/thorreader/app/ui/Library.kt")).first { it.exists() }
        val popup = f.readText().replace("\r", "").substringAfter("internal fun BookPopup(").substringBefore("\n}\n")
        assertFalse("manga", popup.contains("Manga (right-to-left)\""))
        assertFalse("direction", popup.contains("setReadingDirection"))
        assertTrue("a height limit", popup.contains("heightIn(max = maxHeight)"))
        val scroll = popup.indexOf("verticalScroll(")
        val remove = popup.lastIndexOf("\"Remove from library\"") // the button (a comment mentions it earlier)
        val pinned = popup.indexOf("// Pinned:")
        assertTrue("scroll=$scroll pinned=$pinned remove=$remove len=${popup.length}", scroll in 0 until pinned)
        assertTrue("Remove comes after the scrolling part", pinned < remove)
    }
}
