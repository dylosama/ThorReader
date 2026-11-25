package dev.thorreader.app

import dev.thorreader.app.engine.MangaUnits
import dev.thorreader.app.engine.MangaUnits.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Manga volumes vs chapters from file names (the names on the Thor and common release names). */
class MangaUnitsTest {
    private fun u(name: String) = MangaUnits.fromFileName(name)

    @Test
    fun volumes() {
        assertEquals(MangaUnits.Unit("Blue Period", Kind.VOLUME, 1, null), u("Blue Period v01 (2020) (Digital) (1r0n).cbz"))
        assertEquals(MangaUnits.Unit("Blue Period", Kind.VOLUME, 2, null), u("Blue Period v02 (2021) (Digital) (1r0n).cbz"))
        assertEquals(MangaUnits.Unit("Chainsaw Man", Kind.VOLUME, 1, null), u("Chainsaw Man, Vol. 1.cbz"))
        assertEquals(MangaUnits.Unit("The Eminence in Shadow", Kind.VOLUME, 1, null), u("The Eminence in Shadow, Vol. 1.cbz"))
        assertEquals(MangaUnits.Unit("Berserk", Kind.VOLUME, 41, null), u("Berserk Volume 41.cbz"))
        assertEquals(MangaUnits.Unit("Dandadan", Kind.VOLUME, 3, null), u("Dandadan 03.cbz"))
    }

    @Test
    fun chapters() {
        assertEquals(MangaUnits.Unit("Chainsaw Man", Kind.CHAPTER, null, 1f), u("Chainsaw Man, Ch. 1 A Dog and a Chainsaw.cbz"))
        assertEquals(MangaUnits.Unit("Blue Period", Kind.CHAPTER, null, 47f), u("Blue Period - c047 [group].cbz"))
        assertEquals(MangaUnits.Unit("Blue Period", Kind.CHAPTER, null, 47f), u("Blue_Period_Chapter_47.cbz"))
        assertEquals(MangaUnits.Unit("Kagurabachi", Kind.CHAPTER, 2, 12.5f), u("Kagurabachi Vol.02 Ch.012.5.cbz"))
        assertEquals(MangaUnits.Unit("One Piece", Kind.CHAPTER, null, 1045f), u("One Piece 1045.cbz"))
        assertEquals(MangaUnits.Unit("Blue Period", Kind.CHAPTER, null, 47f), u("Blue Period 047.cbz"))
        assertEquals(MangaUnits.Unit("呪術廻戦", Kind.CHAPTER, null, 47f), u("呪術廻戦 第47話.cbz"))
    }

    @Test
    fun notManga() {
        assertNull(u("Dune.epub"))
        assertNull(u("harry-potter-and-the-philosophers-stone-by-jk-rowling.pdf"))
        assertEquals("Vol. 1", MangaUnits.Unit(null, Kind.VOLUME, 1, null).label)
        assertEquals("Ch. 12.5", MangaUnits.Unit(null, Kind.CHAPTER, null, 12.5f).label)
    }
}
