package dev.thorreader.app

import dev.thorreader.app.engine.Opds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPDS catalogs: navigation, books, formats, pages, search. */
class OpdsTest {
    private val base = "https://example.org/opds/root.xml"

    private val navigation = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
          <id>root</id><title>My Library</title>
          <link rel="self" href="root.xml" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          <link rel="search" href="/opds/search.xml" type="application/opensearchdescription+xml"/>
          <entry>
            <title>By Author</title><id>a</id>
            <link rel="subsection" href="authors.xml" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
          <entry>
            <title>New</title><id>n</id><content type="text">Recently added</content>
            <link href="new?page=1" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
          </entry>
        </feed>
    """.trimIndent().toByteArray()

    private val books = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>New</title><id>new</id>
          <link rel="next" href="new?page=2" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
          <link rel="search" href="/opds/search?q={searchTerms}" type="application/atom+xml"/>
          <entry>
            <title>Dune</title><id>urn:1</id>
            <author><name>Frank Herbert</name></author>
            <summary type="html">&lt;p&gt;A desert &amp;amp; spice.&lt;/p&gt;</summary>
            <link rel="http://opds-spec.org/image/thumbnail" href="/covers/1-thumb.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition" href="/get/1.mobi" type="application/x-mobipocket-ebook"/>
            <link rel="http://opds-spec.org/acquisition/open-access" href="/get/1.epub" type="application/epub+zip"/>
            <link rel="http://opds-spec.org/acquisition/buy" href="https://shop/1" type="text/html"/>
          </entry>
          <entry>
            <title>Locked</title><id>urn:2</id>
            <link rel="http://opds-spec.org/acquisition" href="/get/2.acsm" type="application/vnd.adobe.adept+xml"/>
          </entry>
        </feed>
    """.trimIndent().toByteArray()

    @Test
    fun navigationFeed() {
        val f = Opds.parse(navigation, base)
        assertEquals("My Library", f.title)
        assertEquals(2, f.entries.size)
        assertEquals("https://example.org/opds/authors.xml", f.entries[0].navigation?.href)
        assertEquals("https://example.org/opds/new?page=1", f.entries[1].navigation?.href)
        assertEquals("Recently added", f.entries[1].summary)
        assertFalse(f.entries[0].isBook)
        assertEquals("https://example.org/opds/search.xml", f.searchDescription)
        assertNull(f.next)
    }

    @Test
    fun acquisitionFeedPicksEpubAndSkipsBuyingAndDrm() {
        val f = Opds.parse(books, base)
        val dune = f.entries[0]
        assertTrue(dune.isBook)
        assertEquals(listOf("Frank Herbert"), dune.authors)
        assertEquals("A desert & spice.", dune.summary)
        assertEquals("https://example.org/get/1.epub", dune.best?.href) // EPUB over MOBI; "buy" never offered
        assertEquals(2, dune.downloads.size)
        assertEquals("https://example.org/covers/1-thumb.jpg", dune.thumbnail)
        assertFalse(f.entries[1].isBook) // Adobe DRM only
        assertEquals("https://example.org/opds/new?page=2", f.next)
        assertEquals("https://example.org/opds/search?q={searchTerms}", f.searchTemplate)
        assertEquals("Dune - Frank Herbert.epub", Opds.fileName(dune, dune.best!!))
    }

    @Test
    fun openSearchTemplateAndQuery() {
        val desc = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
              <Url type="text/html" template="https://example.org/search?q={searchTerms}"/>
              <Url type="application/atom+xml;profile=opds-catalog" template="/opds/search/{searchTerms}?p={startPage?}"/>
            </OpenSearchDescription>
        """.trimIndent().toByteArray()
        val t = Opds.searchTemplate(desc, "https://example.org/opds/search.xml")!!
        assertEquals("https://example.org/opds/search/{searchTerms}?p={startPage?}", t)
        assertEquals("https://example.org/opds/search/frank%20herbert?p=", Opds.searchUrl(t, " frank herbert "))
    }

    @Test
    fun extensionsAndSafeNames() {
        assertEquals("cbz", Opds.extensionFor(Opds.Link("https://x/a?id=3", null, "application/vnd.comicbook+zip", null)))
        assertEquals("epub", Opds.extensionFor(Opds.Link("https://x/files/b.epub?dl=1", null, null, null)))
        val e = Opds.Entry("1", "A/B: C?", listOf("D"), null, emptyList())
        assertEquals("A B C - D.pdf", Opds.fileName(e, Opds.Link("https://x/1", null, "application/pdf", null)))
    }

    @Test
    fun opds2JsonLikeTheInternetArchive() {
        val json = """
            {"metadata":{"title":"Archive.org"},
             "links":[{"href":"https://archive.org/services/opds/catalog{?query}&type=search","type":"application/opds+json","rel":"search","templated":true},
                      {"href":"/services/opds/catalog?page=2","type":"application/opds+json","rel":"next"}],
             "navigation":[{"href":"https://archive.org/services/opds/catalog?nav_key=page_ebooks","title":"eBooks","type":"application/opds+json","rel":"collection"}],
             "groups":[{"metadata":{"title":"Open Access"},"links":[{"href":"https://archive.org/services/opds/catalog?section=open","type":"application/opds+json","rel":"self"}],
                        "publications":[]}],
             "publications":[
               {"metadata":{"title":"Camilla","identifier":"urn:x","author":[{"name":"Fanny Burney"}],"description":"A <b>novel</b>."},
                "links":[{"href":"https://archive.org/details/x","type":"text/html","rel":"http://opds-spec.org/acquisition/sample"},
                         {"href":"https://archive.org/services/opds/book/x?glob_pattern=*pdf","type":"application/pdf","rel":"http://opds-spec.org/acquisition/open-access"},
                         {"href":"https://archive.org/services/opds/audiobooks/x","type":"application/audiobook+json","rel":"http://opds-spec.org/acquisition/open-access"}],
                "images":[{"href":"https://archive.org/download/x/cover.jpg","type":"image/jpeg","rel":"cover"},{"href":"https://archive.org/download/x/thumb.jpg","type":"image/jpeg"}]}
             ]}
        """.trimIndent().toByteArray()
        val f = Opds.parse(json, "https://archive.org/services/opds")
        assertEquals("Archive.org", f.title)
        assertEquals(listOf("eBooks", "Camilla", "Open Access"), f.entries.map { it.title })
        assertEquals("https://archive.org/services/opds/catalog?nav_key=page_ebooks", f.entries[0].navigation?.href)
        assertEquals("https://archive.org/services/opds/catalog?section=open", f.entries[2].navigation?.href)
        val book = f.entries[1]
        assertEquals(listOf("Fanny Burney"), book.authors)
        assertEquals("A novel .", book.summary)
        assertEquals(1, book.downloads.size) // the PDF; not the sample web page, not the audiobook
        assertEquals("https://archive.org/download/x/thumb.jpg", book.thumbnail)
        assertEquals("https://archive.org/services/opds/catalog?page=2", f.next)
        assertEquals("https://archive.org/services/opds/catalog?query=jane%20austen&type=search", Opds.searchUrl(f.searchTemplate!!, "jane austen"))
    }

    @Test
    fun lenientAddresses() {
        // Unescaped characters a strict URI rejects still resolve (redirects to file downloads).
        assertEquals("https://ia1.example.org/7/items/x/Book%20[1].pdf", Opds.resolve("https://archive.org/download/x", "https://ia1.example.org/7/items/x/Book [1].pdf"))
        assertEquals("https://archive.org/7/items/x/a|b.pdf", Opds.resolve("https://archive.org/download/x", "/7/items/x/a|b.pdf"))
        assertEquals("https://cdn.example.org/f.epub", Opds.resolve("https://example.org/get/1", "//cdn.example.org/f.epub"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun notAFeed() {
        Opds.parse("<html><body>Login</body></html>".toByteArray(), base)
    }
}
