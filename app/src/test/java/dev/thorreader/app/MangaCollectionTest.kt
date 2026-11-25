package dev.thorreader.app

import dev.thorreader.app.engine.MangaCollection
import dev.thorreader.app.engine.MangaCollection.ChapterRow
import dev.thorreader.app.engine.MangaCollection.GapRow
import dev.thorreader.app.engine.MangaCollection.Owned
import dev.thorreader.app.engine.MangaCollection.VolumeRow
import dev.thorreader.app.engine.MangaSeriesMap
import dev.thorreader.app.engine.MangaUnits
import dev.thorreader.app.engine.MangaUnits.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The collection list: owned volumes / chapters, the gaps, the rest of the series. */
class MangaCollectionTest {
    private fun vol(id: Long, v: Int) = Owned(id, MangaUnits.Unit("Blue Period", Kind.VOLUME, v, null))
    private fun ch(id: Long, c: Float) = Owned(id, MangaUnits.Unit("Blue Period", Kind.CHAPTER, null, c))
    private fun f(a: Int, b: Int) = (a..b).map { it.toFloat() }

    /** Blue Period as Wikipedia has it: 4 chapters a volume up to volume 19 (… simplified), loose 86–92. */
    private val bluePeriod = MangaSeriesMap(
        volumes = (1..19).associateWith { v -> f(4 * v - 3, 4 * v) } + (20 to emptyList()),
        loose = f(77, 92).filter { it > 76f },
        status = MangaSeriesMap.Status.ONGOING, totalChapters = null, source = "Wikipedia + AniList",
    )

    @Test
    fun volumesOneAndTwoThenChapter47() {
        val c = MangaCollection.build(listOf(vol(1, 1), vol(2, 2), ch(3, 47f)), bluePeriod)
        assertEquals(
            listOf(
                VolumeRow(1, f(1, 4), 1),
                VolumeRow(2, f(5, 8), 2),
                GapRow(9f, 46f, 38, 3..11, tail = false), // volumes 3–11 wholly missing (12 holds 45–48)
                ChapterRow(47f, 12, 3),
                GapRow(48f, 92f, 45, 13..19, tail = true), // the rest: 48–76 in volumes, then 77–92
            ),
            c.rows,
        )
        assertTrue(c.mixed)
        assertEquals(92f, c.lastChapter)
        assertEquals(MangaSeriesMap.Status.ONGOING, c.status)
    }

    @Test
    fun aVolumeOverridesItsChapters() {
        // Chapter 2 is inside volume 1, which the user has: only the volume shows.
        val c = MangaCollection.build(listOf(vol(1, 1), ch(9, 2f)), bluePeriod)
        assertEquals(VolumeRow(1, f(1, 4), 1), c.rows.first())
        assertTrue(c.rows.none { it is ChapterRow })
    }

    @Test
    fun completedSeriesRunsToItsLastChapter() {
        val done = MangaSeriesMap(mapOf(1 to f(1, 5), 2 to f(6, 10)), emptyList(), MangaSeriesMap.Status.COMPLETED, 12, "x")
        val c = MangaCollection.build(listOf(vol(1, 1)), done)
        assertEquals(listOf(VolumeRow(1, f(1, 5), 1), GapRow(6f, 12f, 7, 2..2, tail = true)), c.rows)
        assertEquals(12f, c.lastChapter)
    }

    @Test
    fun withoutAMapChaptersAndGapsByNumber() {
        val c = MangaCollection.build(listOf(ch(1, 45f), ch(2, 47f), ch(3, 48f)), null)
        assertEquals(
            listOf(GapRow(1f, 44f, 44, null, false), ChapterRow(45f, null, 1), GapRow(46f, 46f, 1, null, false), ChapterRow(47f, null, 2), ChapterRow(48f, null, 3)),
            c.rows,
        )
        // With volumes but no map: which chapters they hold is unknown, so "the chapters before".
        val v = MangaCollection.build(listOf(vol(1, 1), vol(2, 2), ch(3, 47f)), null)
        assertEquals(listOf(VolumeRow(1, null, 1), VolumeRow(2, null, 2), GapRow(null, 46f, null, null, false), ChapterRow(47f, null, 3)), v.rows)
    }

    @Test
    fun ownedVolumeNotYetListed() {
        // Volume 20 is out (the user has it) but Wikipedia lists no chapters for it yet.
        val c = MangaCollection.build(listOf(vol(1, 19), vol(2, 20)), bluePeriod)
        val vIdx = c.rows.indexOfFirst { it is VolumeRow && it.volume == 20 }
        assertTrue(c.rows[vIdx - 1] is VolumeRow && (c.rows[vIdx - 1] as VolumeRow).volume == 19)
    }
}
