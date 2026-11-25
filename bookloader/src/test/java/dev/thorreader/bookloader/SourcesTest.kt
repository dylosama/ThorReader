package dev.thorreader.bookloader

import dev.thorreader.bookloader.source.BookMeta
import dev.thorreader.bookloader.source.CatalogItem
import dev.thorreader.bookloader.source.ContentType
import dev.thorreader.bookloader.source.Entry
import dev.thorreader.bookloader.source.GutendexSource
import dev.thorreader.bookloader.source.MangaDexSource
import dev.thorreader.bookloader.source.PepperCarrotSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manga by volume, the Pepper&Carrot comics source, and the book-cover fallback. */
class SourcesTest {

    private fun ch(id: String, n: Float?, vol: String?) =
        Entry(id, "Ch. ${n ?: "?"}", n, "CBZ", extra = JSONObject().put("volume", vol).put("group", "G").toString())

    @Test
    fun mangaChaptersAreGroupedIntoVolumes() {
        val chapters = listOf(ch("a", 1f, "1"), ch("b", 2f, "1"), ch("c", 3f, "2"), ch("d", 10f, "10"), ch("e", 97f, null), ch("f", 98f, null))
        val vols = MangaDexSource.volumesOf(chapters)
        assertEquals(listOf("vol:1", "vol:2", "vol:10", "vol:none"), vols.map { it.id }) // 10 after 2, not after 1
        assertEquals("Vol. 1 (Ch. 1–2)", vols[0].title)
        assertEquals("Not in a volume yet (Ch. 97–98)", vols[3].title)
        assertEquals(1f, vols[0].number)
        val inside = JSONObject(vols[0].extra).getJSONArray("chapters")
        assertEquals(listOf("a", "b"), (0 until inside.length()).map { inside.getJSONObject(it).getString("id") })

        val item = CatalogItem("mangadex", "m", "Chainsaw Man", type = ContentType.MANGA)
        val d = MangaDexSource.detailsFrom(item, chapters)
        assertEquals(4, d.volumes.size)
        val meta = BookMeta.defaults(d, d.volumes[1], 0)
        assertEquals("Chainsaw Man, Vol. 2", meta.title)
        assertEquals("Chainsaw Man", meta.series)
        assertEquals(2f, meta.seriesNumber)

        // A series without any volume numbers offers no volume view.
        assertTrue(MangaDexSource.detailsFrom(item, listOf(ch("x", 1f, null))).volumes.isEmpty())
    }

    @Test
    fun pepperAndCarrotEpisodes() {
        val json = """[
            {"name":"ep01_Potion-of-Flight","pages":{"1":"P_E01P01.jpg","2":"P_E01P02.jpg","10":"P_E01P10.jpg","cover":"P_E01.jpg","credits":"P_E01P11.jpg","title":"P_E01P00.jpg"},"total_pages":4,"translated_languages":["de","en","fr"]},
            {"name":"ep02_Rainbow-potions","pages":{"1":"x.jpg"},"translated_languages":["fr"]}]"""
        val eps = PepperCarrotSource.parseEpisodes(json)
        assertEquals("not translated to English: left out", 1, eps.size)
        assertEquals("Ep. 1: Potion of Flight", eps[0].title)
        assertEquals(1f, eps[0].number)
        val pages = JSONObject(eps[0].extra).getJSONArray("pages")
        assertEquals(listOf("P_E01P00.jpg", "P_E01P01.jpg", "P_E01P02.jpg", "P_E01P10.jpg", "P_E01P11.jpg"), (0 until pages.length()).map { pages.getString(it) })

        val src = PepperCarrotSource()
        assertEquals(1, src.search("", null).size)
        assertEquals(1, src.search("carrot", ContentType.COMIC).size)
        assertTrue(src.search("batman", null).isEmpty())
        assertTrue(src.search("", ContentType.MANGA).isEmpty())
    }

    @Test
    fun gutenbergBooksFallBackToTheOfficialMirror() {
        val b = GutendexSource.parseSearch(
            """{"results":[{"id":84,"title":"Frankenstein; or, The modern Prometheus","authors":[{"name":"Shelley, Mary Wollstonecraft"}],"formats":{}}]}""",
        ).single()
        assertEquals("https://gutenberg.pglaf.org/cache/epub/84/pg84.cover.medium.jpg", b.altCoverUrl)
        assertEquals(
            listOf("https://gutenberg.pglaf.org/cache/epub/84/pg84-images-3.epub", "https://aleph.pglaf.org/cache/epub/84/pg84-images-3.epub"),
            GutendexSource.mirrors("84", "EPUB"),
        )
        assertTrue(GutendexSource.mirrors("84", "PDF").isEmpty())
        assertEquals(b, CatalogItem.fromJson(b.toJson()))
    }
}
