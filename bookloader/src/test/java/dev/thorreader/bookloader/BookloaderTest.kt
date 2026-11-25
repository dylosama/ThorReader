package dev.thorreader.bookloader

import dev.thorreader.bookloader.source.BookMeta
import dev.thorreader.bookloader.source.CatalogItem
import dev.thorreader.bookloader.source.ContentType
import dev.thorreader.bookloader.source.Entry
import dev.thorreader.bookloader.source.GutendexSource
import dev.thorreader.bookloader.source.MangaDexSource
import dev.thorreader.bookloader.source.Http
import dev.thorreader.bookloader.source.NetConfig
import dev.thorreader.bookloader.source.NetMode
import dev.thorreader.bookloader.source.ProxyKind
import dev.thorreader.bookloader.source.OpdsFeed
import dev.thorreader.bookloader.source.OpdsParser
import dev.thorreader.bookloader.source.SourceRegistry
import dev.thorreader.bookloader.ui.MetaOverride
import dev.thorreader.bookloader.work.Cbz
import dev.thorreader.bookloader.work.JobAction
import dev.thorreader.bookloader.work.JobSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Thor Bookloader: catalogue parsers (shapes taken from the live APIs), CBZ packing, metadata, sources. */
class BookloaderTest {

    // ---- Gutendex ---------------------------------------------------------------------------

    private val gutendex = """
        {"count":1,"next":null,"results":[{"id":84,"title":"Frankenstein; or, The modern Prometheus",
         "authors":[{"name":"Shelley, Mary Wollstonecraft","birth_year":1797}],
         "summaries":["A Gothic novel."],
         "subjects":["Monsters -- Fiction","Science fiction","Frankenstein's monster (Fictitious character) -- Fiction"],
         "bookshelves":["Category: Novels","Gothic Fiction"],"languages":["en"],"copyright":false,
         "formats":{"text/html":"https://www.gutenberg.org/ebooks/84.html.images",
                    "application/epub+zip":"https://www.gutenberg.org/ebooks/84.epub3.images",
                    "image/jpeg":"https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg"}}]}
    """.trimIndent()

    @Test
    fun gutendexSearchAndDetails() {
        val items = GutendexSource.parseSearch(gutendex)
        assertEquals(1, items.size)
        val b = items[0]
        assertEquals("84", b.id)
        assertEquals(listOf("Mary Wollstonecraft Shelley"), b.authors)
        assertEquals("https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg", b.coverUrl)
        assertEquals("A Gothic novel.", b.summary)
        val d = GutendexSource.parseDetails(b)
        assertEquals(listOf("EPUB"), d.entries.map { it.format })
        assertEquals("https://www.gutenberg.org/ebooks/84.epub3.images", d.entries[0].url)
        assertTrue(d.tags.containsAll(listOf("Monsters", "Science fiction", "Novels", "Gothic Fiction")))
        assertFalse("no '(Fictitious character)' tags", d.tags.any { it.contains("(") })
        // Survives a round trip through a queued job.
        assertEquals(b, CatalogItem.fromJson(b.toJson()))
    }

    // ---- MangaDex ---------------------------------------------------------------------------

    private val mdSearch = """
        {"result":"ok","data":[{"id":"58be6aa6-06cb-4ca5-bd20-f1392ce451fb","type":"manga",
          "attributes":{"title":{"ja-ro":"Yotsuba to!"},"altTitles":[{"ja":"よつばと！"},{"en":"Yotsuba&!"}],
            "description":{"en":"Yotsuba is a strange little girl.\n\n---\n- [Official](https://x)"},
            "originalLanguage":"ja","year":2003,"status":"ongoing",
            "tags":[{"attributes":{"name":{"en":"Comedy"}}},{"attributes":{"name":{"en":"Slice of Life"}}}]},
          "relationships":[{"id":"a1","type":"author","attributes":{"name":"Azuma Kiyohiko"}},
                           {"id":"a1","type":"artist","attributes":{"name":"Azuma Kiyohiko"}},
                           {"id":"c1","type":"cover_art","attributes":{"fileName":"ff8d.jpg","volume":"16"}}]}]}
    """.trimIndent()

    private val mdFeed = """
        {"result":"ok","total":4,"data":[
         {"id":"c1","type":"chapter","attributes":{"volume":"1","chapter":"1","title":"Yotsuba & Moving","externalUrl":null,"isUnavailable":false,"pages":51},
          "relationships":[{"id":"g","type":"scanlation_group","attributes":{"name":"Some Group"}}]},
         {"id":"c1b","type":"chapter","attributes":{"volume":"1","chapter":"1","title":"Same chapter, other group","externalUrl":null,"pages":50},"relationships":[]},
         {"id":"c2","type":"chapter","attributes":{"volume":"1","chapter":"2","title":null,"externalUrl":"https://elsewhere.example/2","pages":0},"relationships":[]},
         {"id":"c3","type":"chapter","attributes":{"volume":"1","chapter":"3","title":null,"externalUrl":null,"pages":30},"relationships":[]}]}
    """.trimIndent()

    @Test
    fun mangaDexSearchFeedAndPages() {
        val m = MangaDexSource.parseSearch(mdSearch).single()
        assertEquals("Yotsuba&!", m.title) // the English alternative title
        assertEquals(listOf("Azuma Kiyohiko"), m.authors)
        assertEquals("https://uploads.mangadex.org/covers/58be6aa6-06cb-4ca5-bd20-f1392ce451fb/ff8d.jpg.256.jpg", m.coverUrl)
        assertEquals("Yotsuba is a strange little girl.", m.summary) // link lists after "---" removed
        assertEquals(ContentType.MANGA, m.type)

        val (chapters, total) = MangaDexSource.parseFeed(mdFeed)
        assertEquals(4, total)
        assertEquals("external chapter left out", listOf("c1", "c1b", "c3"), chapters.map { it.id })
        val d = MangaDexSource.detailsFrom(m, MangaDexSource.dedupe(chapters))
        assertEquals("same chapter from two groups kept once", listOf("c1", "c3"), d.entries.map { it.id })
        assertEquals("Ch. 1: Yotsuba & Moving", d.entries[0].title)
        assertEquals(1f, d.entries[0].number)
        assertTrue("Japanese original = right to left", d.rtl)
        assertEquals(listOf("Comedy", "Slice of Life"), d.tags)

        val pages = MangaDexSource.parseAtHome(
            """{"result":"ok","baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"h1","data":["1-a.png","2-b.png"],"dataSaver":["1-a.jpg","2-b.jpg"]}}""",
        )
        assertEquals("https://uploads.mangadex.org/data/h1/1-a.png" to "https://uploads.mangadex.org/data-saver/h1/1-a.jpg", pages[0])
        assertEquals(2, pages.size)
    }

    // ---- OPDS -------------------------------------------------------------------------------

    private val feed = OpdsFeed("f1", "Test", "https://comics.example/opds", ContentType.COMIC)

    private val opds1 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE feed [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
          <title>Library</title>
          <link rel="search" type="application/atom+xml" href="/opds/search?q={searchTerms}"/>
          <link rel="next" type="application/atom+xml;profile=opds-catalog" href="?page=2"/>
          <entry>
            <title>Series: Saga</title>
            <id>urn:series:1</id>
            <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=navigation" href="/opds/series/1"/>
          </entry>
          <entry>
            <title>Saga #1</title>
            <id>urn:book:1</id>
            <author><name>Brian K. Vaughan</name></author>
            <author><name>Fiona Staples</name></author>
            <summary type="html">&lt;p&gt;Space &amp;amp; war.&lt;/p&gt;</summary>
            <category term="scifi" label="Science Fiction"/>
            <link rel="http://opds-spec.org/image/thumbnail" type="image/jpeg" href="/thumb/1.jpg"/>
            <link rel="http://opds-spec.org/acquisition" type="application/vnd.comicbook+zip" href="/download/1.cbz"/>
            <link rel="http://opds-spec.org/acquisition/buy" type="application/pdf" href="/buy/1"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun opds1FoldersPublicationsSearchAndNoPaidLinks() {
        val p = OpdsParser.parse(opds1, "https://comics.example/opds", feed)
        assertEquals(2, p.items.size)
        val folder = p.items[0]
        assertEquals("https://comics.example/opds/series/1", folder.folderUrl)
        val book = p.items[1]
        assertNull(book.folderUrl)
        assertEquals(listOf("Brian K. Vaughan", "Fiona Staples"), book.authors)
        assertEquals("https://comics.example/thumb/1.jpg", book.coverUrl)
        assertTrue(book.summary!!.startsWith("Space"))
        assertEquals("https://comics.example/opds/search?q={searchTerms}", p.searchTemplate)
        assertEquals("https://comics.example/opds?page=2", p.next)
        // Only the open acquisition link; the "buy" link is left out.
        val acq = org.json.JSONObject(book.extra).getJSONArray("acq")
        assertEquals(1, acq.length())
        assertEquals("https://comics.example/download/1.cbz", acq.getJSONObject(0).getString("href"))
        assertEquals("CBZ", OpdsParser.formatOf(acq.getJSONObject(0).getString("type")))
    }

    private val opds2 = """
        {"metadata":{"title":"OPDS 2.0 Test Catalog"},
         "links":[{"rel":"self","href":"https://test.opds.io/2.0/home.json"},
                  {"rel":"search","href":"https://test.opds.io/2.0/search{?query}","type":"application/opds+json","templated":true}],
         "navigation":[{"href":"https://test.opds.io/2.0/publications.json","title":"French Books","type":"application/opds+json"},
                       {"href":"https://test.opds.io/2.0/home.json","title":"Back home"}],
         "publications":[{"metadata":{"title":"Voyage au centre de la terre","identifier":"urn:isbn:1","author":{"name":"Jules Verne"},
                            "subject":[{"name":"Adventure"}],"description":"<p>Un classique.</p>"},
                          "links":[{"rel":"http://opds-spec.org/acquisition/open-access","href":"/assets/centredelaterre/file.epub","type":"application/epub+zip"},
                                   {"rel":"http://opds-spec.org/acquisition/borrow","href":"/borrow","type":"application/epub+zip"}],
                          "images":[{"href":"/assets/centredelaterre/small.jpg","height":300}]}]}
    """.trimIndent()

    @Test
    fun opds2NavigationPublicationsAndTemplatedSearch() {
        val p = OpdsParser.parse(opds2, "https://test.opds.io/2.0/home.json", feed.copy(type = ContentType.BOOK))
        assertEquals("link back to this page left out", 2, p.items.size)
        assertEquals("https://test.opds.io/2.0/publications.json", p.items[0].folderUrl)
        val pub = p.items[1]
        assertEquals("Voyage au centre de la terre", pub.title)
        assertEquals(listOf("Jules Verne"), pub.authors)
        assertEquals("https://test.opds.io/assets/centredelaterre/small.jpg", pub.coverUrl)
        assertEquals("Un classique.", pub.summary)
        assertEquals("https://test.opds.io/2.0/search?query={searchTerms}", p.searchTemplate)
        val acq = org.json.JSONObject(pub.extra).getJSONArray("acq")
        assertEquals("borrow link left out", 1, acq.length())
        assertEquals("https://test.opds.io/assets/centredelaterre/file.epub", acq.getJSONObject(0).getString("href"))
    }

    @Test
    fun openSearchDescriptionGivesTheTemplate() {
        val xml = """<?xml version="1.0"?><OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <Url type="text/html" template="https://x.example/html?q={searchTerms}"/>
            <Url type="application/atom+xml" template="/opds/search/{searchTerms}"/></OpenSearchDescription>"""
        assertEquals("https://x.example/opds/search/{searchTerms}", OpdsParser.openSearchTemplate(xml, "https://x.example/opds/os.xml"))
        assertEquals("https://x/s?q={searchTerms}&page=1", OpdsParser.expandUriTemplate("https://x/s?q={searchTerms}&page=1"))
        assertEquals("https://x/s?title={searchTerms}", OpdsParser.expandUriTemplate("https://x/s{?title,author}"))
    }

    // ---- sources ----------------------------------------------------------------------------

    @Test
    fun sourceListsOnlyAddHttpsOpdsFeeds() {
        val (feeds, skipped) = SourceRegistry.parseSourceList(
            """{"sources":[
                {"type":"opds","name":"My Komga","url":"https://komga.example/opds/v1.2/catalog","contentType":"comics"},
                {"type":"opds","name":"Plain http","url":"http://insecure.example/opds"},
                {"type":"script","name":"Scraper","url":"https://bad.example/x.js"},
                {"name":"Manga feed","url":"https://kavita.example/api/opds/key","contentType":"manga"}]}""",
        )
        assertEquals(listOf("My Komga", "Manga feed"), feeds.map { it.name })
        assertEquals(listOf(ContentType.COMIC, ContentType.MANGA), feeds.map { it.type })
        assertEquals(2, skipped)
        assertNotNull(SourceRegistry.checkFeedUrl("ftp://x"))
        assertNull(SourceRegistry.checkFeedUrl("https://ok.example/opds"))
        assertEquals(feeds[0], OpdsFeed.fromJson(feeds[0].toJson()))
    }

    // ---- CBZ + ComicInfo --------------------------------------------------------------------

    @Test
    fun chaptersArePackedIntoAValidCbzWithComicInfo() {
        val dir = createTempDir("cbz")
        try {
            val pages = (1..12).map { i -> File(dir, "p$i.png").apply { writeBytes(byteArrayOf(i.toByte())) } }
            val meta = BookMeta(title = "Yotsuba&!, Ch. 1", authors = listOf("Azuma Kiyohiko"), series = "Yotsuba&!", seriesNumber = 1f, type = ContentType.MANGA, genres = listOf("Comedy"), rtl = true)
            val info = Cbz.comicInfo(meta, 12, summary = "A <girl> & her dad", web = "https://mangadex.org/chapter/c1", translator = "Some Group", language = "en", volume = "1")
            val out = File(dir, "out.cbz")
            Cbz.write(out, pages, info)
            ZipFile(out).use { z ->
                val names = z.entries().toList().map { it.name }
                assertEquals((1..12).map { "%03d.png".format(it) } + "ComicInfo.xml", names)
                val xml = z.getInputStream(z.getEntry("ComicInfo.xml")).readBytes().toString(Charsets.UTF_8)
                assertTrue(xml.contains("<Series>Yotsuba&amp;!</Series>"))
                assertTrue(xml.contains("<Number>1</Number>"))
                assertTrue(xml.contains("<Manga>YesAndRightToLeft</Manga>"))
                assertTrue(xml.contains("<Summary>A &lt;girl&gt; &amp; her dad</Summary>"))
                assertTrue(xml.contains("<Translator>Some Group</Translator>"))
                // Well-formed XML.
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- metadata -----------------------------------------------------------------------------

    @Test
    fun chaptersAreNumberedAndOverridesApply() {
        val item = CatalogItem("mangadex", "m1", "Yotsuba&!", listOf("Azuma"), type = ContentType.MANGA)
        val e3 = Entry("c3", "Ch. 3", 3f, "CBZ")
        val eX = Entry("cx", "Oneshot", null, "CBZ")
        val d = MangaDexSource.detailsFrom(item, listOf(Entry("c1", "Ch. 1", 1f, "CBZ"), e3, eX))
        val m3 = BookMeta.defaults(d, e3, 0)
        assertEquals("Yotsuba&!, Ch. 3", m3.title)
        assertEquals("Yotsuba&!", m3.series)
        assertEquals(3f, m3.seriesNumber)
        assertEquals("no number: position in the selection", 2f, BookMeta.defaults(d, eX, 1).seriesNumber)

        val o = MetaOverride(series = "Yotsuba", startNumber = "10", rtl = false, mode = 2, title = "ignored for many")
        val a = o.apply(m3, 0, single = false)
        val b = o.apply(m3, 1, single = false)
        assertEquals(listOf(10f, 11f), listOf(a.seriesNumber, b.seriesNumber))
        assertEquals("Yotsuba", a.series)
        assertEquals("Yotsuba&!, Ch. 3", a.title)
        assertEquals(false, a.rtl)
        assertEquals(2, a.mode)
        assertEquals("single download: title applies", "My title", MetaOverride(title = "My title").apply(m3, 0, single = true).title)

        // A single book keeps its own title and no series.
        val book = CatalogItem("gutendex", "84", "Frankenstein", type = ContentType.BOOK)
        val bm = BookMeta.defaults(GutendexSource.parseDetails(book.copy(extra = "{}")), Entry("epub", "EPUB", null, "EPUB"), 0)
        assertEquals("Frankenstein", bm.title)
        assertNull(bm.series)
        assertNull(bm.rtl)

        val spec = JobSpec(JobAction.IMPORT, "mangadex", item, e3, a)
        assertEquals(spec, JobSpec.fromJson(spec.toJson()))
    }

    // ---- the contract with Thor Reader ----------------------------------------------------------

    @Test
    fun importContractMatchesThorReader() {
        fun consts(f: File) = Regex("const val (\\w+) = (\"[^\"]*\"|\\d+)").findAll(f.readText().substringAfter("object ").substringBefore("\n}\n")).associate { it.groupValues[1] to it.groupValues[2] }
        val rel = "src/main/java/dev/thorreader/"
        fun find(p: String) = listOf(File("../app/$rel$p"), File("app/$rel$p"), File("../bookloader/$rel$p"), File("bookloader/$rel$p")).first { it.exists() }
        val thor = consts(find("app/importer/ImportContract.kt"))
        val ours = consts(listOf(File("${rel}bookloader/ThorImportContract.kt"), File("bookloader/${rel}bookloader/ThorImportContract.kt")).first { it.exists() })
        assertTrue(thor.size >= 20)
        for ((k, v) in thor) assertEquals("ImportContract.$k", v, ours[k])
    }
}

/** Settings → Connection: the route every request takes, and that it never falls back to direct. */
class ConnectionTest {
    @Test
    fun routesMapToTheRightProxies() {
        assertNull(NetConfig().proxy())
        val tor = NetConfig(NetMode.TOR).proxy()!!
        assertEquals(java.net.Proxy.Type.SOCKS, tor.type())
        assertEquals(9050, (tor.address() as java.net.InetSocketAddress).port)
        assertEquals("127.0.0.1", (tor.address() as java.net.InetSocketAddress).hostString)
        assertEquals(java.net.Proxy.Type.HTTP, NetConfig(NetMode.PROXY, ProxyKind.HTTP, "proxy.example", 8080).proxy()!!.type())
        assertNotNull(NetConfig(NetMode.PROXY, host = "", port = 1080).problem())
        assertNotNull(NetConfig(NetMode.PROXY, host = "user@proxy", port = 1080).problem())
        assertNotNull(NetConfig(NetMode.PROXY, host = "proxy.example", port = 70000).problem())
        assertNull(NetConfig(NetMode.PROXY, host = "10.0.0.2", port = 1080).problem())
        assertEquals("Tor", NetConfig(NetMode.TOR).badge)
        assertNull(NetConfig().badge)
    }

    @Test
    fun aDeadProtectedRouteStopsTheRequestInsteadOfGoingDirect() {
        // A "Tor" that isn't running: a local port nobody listens on.
        val free = java.net.ServerSocket(0).use { it.localPort }
        val before = Http.route
        Http.route = NetConfig(NetMode.PROXY, ProxyKind.SOCKS, "127.0.0.1", free)
        try {
            Http.text("https://gutendex.com/books/")
            org.junit.Assert.fail("the request must not go through")
        } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("nothing was sent"))
        } finally {
            Http.route = before
        }
    }
}
