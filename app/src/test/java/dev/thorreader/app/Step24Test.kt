package dev.thorreader.app

import dev.thorreader.app.engine.Book3D
import dev.thorreader.app.engine.BookFace
import dev.thorreader.app.engine.Rot
import dev.thorreader.app.engine.V3
import dev.thorreader.app.io.BackCovers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Step 24: theme colour contrast, the 3D book and back-cover detection. */
class Step24Test {

    // ---- theme colour ----

    @Test
    fun textOnTheAccentIsAlwaysReadable() {
        assertEquals(8, AccentMath.PRESETS.size)
        assertEquals(0xFFE0A050.toInt(), AccentMath.DEFAULT) // orange stays the default
        // Every preset, and custom hues all round the wheel: the chosen text colour reaches 4.5:1.
        val colours = AccentMath.PRESETS + (0 until 360 step 15).map { AccentMath.fromHue(it.toFloat()) }
        for (c in colours) {
            val text = if (AccentMath.prefersDarkText(c)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            assertTrue("contrast for ${Integer.toHexString(c)}", AccentMath.contrast(c, text) >= 4.5)
        }
        assertTrue(AccentMath.prefersDarkText(0xFFE8C14A.toInt())) // gold: dark text
        assertFalse(AccentMath.prefersDarkText(0xFF202060.toInt())) // dark blue: light text
    }

    // ---- the 3D book ----

    private val dune = Book3D(0.66f, Book3D.thicknessRatio(700, comic = false), rtl = false)

    @Test
    fun atRestOnlyTheFrontCoverShows() {
        val shown = BookFace.entries.filter { dune.visible(it, Rot.IDENTITY) }
        assertEquals(listOf(BookFace.FRONT), shown)
        // The front cover projects to the requested height.
        val top = dune.project(V3(0f, -0.5f, -dune.d / 2f), Rot.IDENTITY, 600f, 0f, 0f).second
        val bottom = dune.project(V3(0f, 0.5f, -dune.d / 2f), Rot.IDENTITY, 600f, 0f, 0f).second
        assertEquals(600f, bottom - top, 0.5f)
    }

    @Test
    fun turningShowsTheSpineAndTheBack() {
        // Turn the left side toward the camera: the spine (left in left-to-right books) shows.
        val r = Rot.axisAngle(V3(0f, 1f, 0f), (-PI / 2.2).toFloat())
        assertTrue(dune.visible(BookFace.SPINE, r))
        assertFalse(dune.visible(BookFace.FORE_EDGE, r))
        val half = Rot.axisAngle(V3(0f, 1f, 0f), PI.toFloat())
        assertTrue(dune.visible(BookFace.BACK, half))
        assertFalse(dune.visible(BookFace.FRONT, half))
        // Right-to-left books have the spine on the right.
        val manga = Book3D(0.7f, 0.02f, rtl = true)
        assertTrue(manga.visible(BookFace.SPINE, Rot.axisAngle(V3(0f, 1f, 0f), (PI / 2.2).toFloat())))
        // The front is lit better than the back side at rest-ish angles.
        assertTrue(dune.brightness(BookFace.FRONT, Rot.IDENTITY) > dune.brightness(BookFace.BOTTOM, Rot.IDENTITY))
    }

    @Test
    fun thicknessFollowsTheSourcePageCount() {
        val comic = Book3D.thicknessRatio(22, comic = true)
        val book = Book3D.thicknessRatio(700, comic = false)
        assertTrue("a 22-page comic is thin: $comic", comic < 0.015f)
        assertTrue("Dune is thick: $book", book > 0.3f)
        assertTrue(Book3D.thicknessRatio(200, false) < book)
        // EPUB counts come from a small reference page: ~3.4 of them make one printed page.
        assertEquals(527, Book3D.printedPages(1794, reflowable = true))
        assertEquals(22, Book3D.printedPages(22, reflowable = false))
        val duneReal = Book3D.thicknessRatio(Book3D.printedPages(1794, true), comic = false)
        assertTrue("Dune from its EPUB count: $duneReal", duneReal in 0.25f..0.35f)
    }

    @Test
    fun dragsSpinAndTheBookReturnsToTheFront() {
        // Dragging right turns the front to the right (around +y).
        val r = Book3D.dragRotation(100f, 0f, 200f)!!
        val (axis, angle) = r.toAxisAngle()
        assertEquals(0.5f, angle, 0.001f)
        assertEquals(-1f, axis.y, 0.001f)
        assertNull(Book3D.dragRotation(0f, 0f, 200f))
        // Any rotation can be undone smoothly (closing turns back to the front).
        val any = Rot.axisAngle(V3(0.3f, 1f, -0.2f), 2.2f)
        val (ax, an) = any.toAxisAngle()
        val back = Rot.axisAngle(ax, an)
        for (i in 0 until 9) assertEquals(any.m[i], back.m[i], 0.001f)
        assertEquals(0f, Rot.axisAngle(ax, 0f).toAxisAngle().second, 0.001f)
    }

    // ---- back covers ----

    @Test
    fun backCoverNamesAndComicInfo() {
        assertTrue(BackCovers.looksLikeBackName("OEBPS/images/back.jpg"))
        assertTrue(BackCovers.looksLikeBackName("Cover_Back.png"))
        assertTrue(BackCovers.looksLikeBackName("Spidey 001 - bc.jpg"))
        assertFalse(BackCovers.looksLikeBackName("images/background.png"))
        assertFalse(BackCovers.looksLikeBackName("Spidey 001 - 22.jpg"))
        val xml = """<ComicInfo><Pages><Page Image="0" Type="FrontCover"/><Page Image="1"/><Page Image="23" Type="BackCover"/></Pages></ComicInfo>"""
        assertEquals(23, BackCovers.comicInfoBackIndex(xml))
        assertEquals(5, BackCovers.comicInfoBackIndex("""<Page Type="BackCover" Image="5" />"""))
        assertNull(BackCovers.comicInfoBackIndex("""<Page Image="0" Type="FrontCover"/>"""))
        assertTrue(BackCovers.looksLikeCoverArt(0.04f))
        assertFalse(BackCovers.looksLikeCoverArt(0.15f))
    }
}
