package dev.thorreader.app

import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.importer.FilenameParser
import dev.thorreader.app.importer.isbnOf
import dev.thorreader.app.metadata.AniList
import dev.thorreader.app.metadata.Candidate
import dev.thorreader.app.metadata.ComicVine
import dev.thorreader.app.metadata.Genres
import dev.thorreader.app.metadata.Match
import dev.thorreader.app.metadata.MetaApply
import dev.thorreader.app.metadata.OpenLibrary
import dev.thorreader.app.metadata.Query
import dev.thorreader.app.ui.LibraryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 13: metadata rules, filename parsing, matching and the online sources' answers. */
class MetadataTest {

    private fun book(
        format: BookFormat, title: String = "T", fileName: String = "T.x", rtl: Boolean = false, isManga: Boolean = false,
        typeOverride: String? = null, author: String? = null, series: String? = null, edited: String? = null,
        description: String? = null, rtlOverride: Boolean? = null,
    ) = Book(
        uri = "u:$fileName:${format.name}", fileName = fileName, format = format.name, sizeBytes = 1, title = title,
        mangaRtl = rtl, isManga = isManga, typeOverride = typeOverride, author = author, series = series,
        editedFields = edited, description = description, rtlOverride = rtlOverride,
    )

    // ---- Type: every format lands in exactly one type --------------------------------------

    @Test
    fun everyFormatHasExactlyOneType() {
        for (f in BookFormat.entries) {
            val plain = LibraryLogic.typeOf(book(f))
            val expected = if (f.comic) LibraryLogic.COMICS else LibraryLogic.BOOKS
            assertEquals("$f", expected, plain)
            assertEquals("$f manga flag", LibraryLogic.MANGA, LibraryLogic.typeOf(book(f, isManga = true)))
            assertEquals("$f right-to-left", LibraryLogic.MANGA, LibraryLogic.typeOf(book(f, rtl = true)))
            assertEquals("$f hand-set", LibraryLogic.BOOKS, LibraryLogic.typeOf(book(f, isManga = true, typeOverride = "Books")))
            // Exactly one category in the Type view.
            assertEquals(1, LibraryLogic.categoriesOf(book(f), LibraryView.TYPE).size)
        }
        assertEquals(LibraryLogic.BOOKS, LibraryLogic.typeOf(book(BookFormat.PDF)))
    }

    // ---- Genres ----------------------------------------------------------------------------

    @Test
    fun genresAreCleanTagsAndDescriptionsAreNot() {
        val hp = "When a letter arrives for unhappy but ordinary Harry Potter, a decade-old secret is revealed to him."
        assertTrue(Genres.isDescription(hp))
        assertEquals(emptyList<String>(), Genres.normalize(listOf(hp)))
        assertEquals(hp, Genres.descriptionFrom(listOf("Fantasy", hp)))
        assertEquals(listOf("Fantasy"), Genres.normalize(listOf("Fiction / Fantasy / General")))
        assertEquals(listOf("Science Fiction"), Genres.normalize(listOf("Fiction / Science Fiction / Space Opera")))
        assertEquals(listOf("Young Adult", "Fantasy"), Genres.normalize(listOf("Juvenile Fiction -- Magic")))
        assertEquals(listOf("Superhero"), Genres.normalize(listOf("Super-heroes")))
        assertEquals(listOf("Slice of Life", "Comedy"), Genres.normalize(listOf("Slice of Life", "Humor")))
        // AniList's own genres are curated: kept even when not in our list.
        assertEquals(listOf("Mahou Shoujo".let { "Magical Girl" }, "Ecchi", "Gourmet"), Genres.normalize(listOf("Mahou Shoujo", "Ecchi", "Gourmet"), trusted = true))
        assertFalse(Genres.isTag("A story. About things"))
        assertTrue(Genres.isTag("Science Fiction"))
    }

    // ---- File names --------------------------------------------------------------------------

    @Test
    fun filenamePatterns() {
        FilenameParser.parse("Blue Period v01 (2020) (Digital) (1r0n).cbz", comic = true).let {
            assertEquals("Blue Period", it.series); assertEquals(1, it.volume); assertNull(it.issue); assertEquals(2020, it.year); assertEquals(1f, it.number)
        }
        FilenameParser.parse("Spectacular Spider-Man v1 001.cbr", comic = true).let {
            assertEquals("Spectacular Spider-Man", it.series); assertEquals(1, it.volume); assertEquals(1f, it.issue)
        }
        FilenameParser.parse("Saga 054.cbr", comic = true).let { assertEquals("Saga", it.series); assertEquals(54f, it.issue) }
        FilenameParser.parse("Berserk Vol. 3.cbz", comic = true).let { assertEquals("Berserk", it.series); assertEquals(3, it.volume) }
        FilenameParser.parse("Invincible #12 [Image].cbz", comic = true).let { assertEquals("Invincible", it.series); assertEquals(12f, it.issue) }
        FilenameParser.parse("Batman (2016) 001 (Digital).cbz", comic = true).let { assertEquals("Batman", it.series); assertEquals(1f, it.issue); assertEquals(2016, it.year) }
        assertEquals("9780747532699", isbnOf("urn:isbn:978-0-7475-3269-9"))
        assertNull(isbnOf("uuid:1234"))
    }

    // ---- Matching ----------------------------------------------------------------------------

    @Test
    fun titleSimilarity() {
        assertEquals(1f, Match.similarity("The Spectacular Spider-Man", "Spectacular Spider-Man"), 0.001f)
        assertTrue(Match.similarity("Blue Period", "BLUE PERIOD") > 0.99f)
        assertTrue(Match.similarity("Spectacular Spider-Man", "Spider-Man: Fake Red") < Match.STRICT_THRESHOLD)
        assertTrue(Match.similarity("Harry Potter and the Philosopher's Stone", "Harry Potter and the Philosophers Stone") > 0.9f)
    }

    private val ANILIST = """{"data":{"Page":{"media":[
      {"id":1,"title":{"romaji":"Blue Period","english":"Blue Period","native":"ブルーピリオド"},"synonyms":[],"format":"MANGA",
       "countryOfOrigin":"JP","volumes":15,"startDate":{"year":2017},"genres":["Drama","Slice of Life"],
       "tags":[{"name":"Art","rank":95,"isMediaSpoiler":false}],"description":"Yatora is a <i>popular</i> student.<br>He finds art.",
       "coverImage":{"large":"https://x/c.jpg"},
       "staff":{"edges":[{"role":"Story & Art","node":{"name":{"full":"Tsubasa Yamaguchi"}}},{"role":"Editing","node":{"name":{"full":"Someone"}}}]}},
      {"id":2,"title":{"romaji":"Ao no Hako","english":"Blue Box","native":null},"synonyms":[],"format":"MANGA",
       "countryOfOrigin":"JP","volumes":null,"startDate":{"year":2021},"genres":["Sports"],"tags":[],"description":null,
       "coverImage":{"large":null},"staff":{"edges":[]}}
    ]}}}"""

    @Test
    fun aniListAnswer() {
        val q = Query("Blue Period v01", "Blue Period", 1, null, 2020, null, null, null)
        val c = AniList.parse(ANILIST, q)
        assertEquals("Blue Period", c[0].series)
        assertTrue(c[0].score >= Match.THRESHOLD)
        assertTrue(c[0].isManga == true)
        assertEquals("JP", c[0].country)
        assertEquals(listOf("Tsubasa Yamaguchi"), c[0].authors)
        assertEquals(listOf("Drama", "Slice of Life", "Art"), c[0].genres)
        assertEquals("Yatora is a popular student.\nHe finds art.", c[0].description)
        assertTrue(c[1].score < Match.THRESHOLD)
    }

    @Test
    fun aniListRealTitleBeatsAnAlternativeName() {
        // AniList's top hit for "Blue Period" is "A Bitter Fruit", which lists it as a synonym.
        val json = """{"data":{"Page":{"media":[
            {"id":1,"title":{"romaji":"Nigai Kajitsu","english":"A Bitter Fruit"},"synonyms":["Blue Period"],"format":"MANGA","countryOfOrigin":"JP","volumes":1},
            {"id":2,"title":{"romaji":"Blue Period","english":"Blue Period"},"synonyms":[],"format":"MANGA","countryOfOrigin":"JP","volumes":null}]}}}"""
        val c = AniList.parse(json, Query("Blue Period - c047", "Blue Period", null, 47f, null, null, null, null))
        assertTrue(c.first { it.id == "2" }.score > c.first { it.id == "1" }.score)
    }

    @Test
    fun comicVineAnswers() {
        val q = Query("Spectacular Spider-Man v1 001", "Spectacular Spider-Man", 1, 1f, null, null, null, null)
        val vols = ComicVine.parseVolumes(
            """{"status_code":1,"results":[
              {"id":2788,"name":"The Spectacular Spider-Man","start_year":"1976","count_of_issues":263,"publisher":{"name":"Marvel"},"image":{"small_url":"https://x/s.jpg"}},
              {"id":9,"name":"Spider-Man Unlimited","start_year":"1993","count_of_issues":22,"publisher":{"name":"Marvel"}}]}""",
            q,
        )
        assertEquals("2788", vols[0].id)
        assertTrue(vols[0].score >= Match.THRESHOLD)
        assertEquals(listOf("Superhero"), vols[0].genres)
        val full = ComicVine.withDetails(
            vols[0],
            """{"status_code":1,"results":{"person_credits":[{"name":"Gerry Conway","role":"writer"},{"name":"Sal Buscema","role":"penciler, cover"},
               {"name":"Joe Letterer","role":"letterer"}],"story_arc_credits":[{"name":"The Big One"}],"concept_credits":[],
               "description":"<p>Spidey meets <b>Tarantula</b>.</p>","cover_date":"1976-12-01"}}""",
        )
        assertEquals(listOf("Gerry Conway", "Sal Buscema"), full.authors)
        assertEquals("The Big One", full.storyArc)
        assertEquals("Spidey meets Tarantula.", full.description)
        assertEquals(1976, full.year)
        try {
            ComicVine.parseVolumes("""{"status_code":100,"error":"Invalid API Key","results":[]}""", q)
            throw AssertionError("bad key not detected")
        } catch (e: dev.thorreader.app.metadata.BadKeyException) {
            // expected
        }
    }

    @Test
    fun openLibraryAnswer() {
        val q = Query("Harry Potter and the Philosopher's Stone", null, null, null, null, "J. K. Rowling", null, null)
        val c = OpenLibrary.parse(
            """{"docs":[{"key":"/works/OL82563W","title":"Harry Potter and the Philosopher's Stone","author_name":["J. K. Rowling"],
               "first_publish_year":1997,"subject":["Fiction","Juvenile fiction","Magic","Wizards","Schools, fiction"],"cover_i":10521270}]}""",
            q,
        )
        assertTrue(c[0].score >= Match.THRESHOLD)
        assertEquals(1997, c[0].year)
        assertTrue(c[0].genres.contains("Fantasy"))
        assertEquals("The boy who lived.", OpenLibrary.description("""{"description":{"type":"/type/text","value":"The boy who lived."}}"""))
    }

    // ---- Applying a match ----------------------------------------------------------------------

    @Test
    fun applyRespectsHandEditsAndSetsDirection() {
        val jp = Candidate("AniList", "1", "Blue Period", series = "Blue Period", authors = listOf("Tsubasa Yamaguchi"),
            genres = listOf("Drama"), description = "D", isManga = true, country = "JP", score = 1f)
        val b = book(BookFormat.CBZ, title = "Blue Period v01", fileName = "Blue Period v01.cbz")
        val a = MetaApply.apply(b, jp)
        assertEquals("Blue Period, Vol. 1", a.title)
        assertEquals("Tsubasa Yamaguchi", a.author)
        assertTrue(a.isManga)
        assertTrue("Japanese manga reads right-to-left", a.rtl)
        assertEquals(LibraryLogic.MANGA, LibraryLogic.typeOf(a))
        // A direction set by hand is never changed.
        assertFalse(MetaApply.apply(b.copy(rtlOverride = false), jp).rtl)
        // Korean manhwa stays left-to-right, but is still manga.
        val kr = MetaApply.apply(b, jp.copy(country = "KR"))
        assertFalse(kr.rtl)
        assertEquals(LibraryLogic.MANGA, LibraryLogic.typeOf(kr))
        // Hand-edited fields stay.
        val e = MetaApply.apply(b.copy(title = "My title", author = "Me", editedFields = "title,authors"), jp)
        assertEquals("My title", e.title)
        assertEquals("Me", e.author)
        // An existing description is kept.
        assertEquals("Mine", MetaApply.apply(b.copy(description = "Mine"), jp).description)
    }

    @Test
    fun singleBookSeriesIsListed() {
        val b = book(BookFormat.CBR, series = "Spectacular Spider-Man")
        assertEquals(listOf("Spectacular Spider-Man" to 1), LibraryLogic.categories(listOf(b), LibraryView.SERIES))
    }
}
