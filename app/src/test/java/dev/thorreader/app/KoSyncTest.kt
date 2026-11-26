package dev.thorreader.app

import dev.thorreader.app.engine.KoSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** KOReader / CrossPoint progress sync: document ids and positions. */
class KoSyncTest {

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun reader(data: ByteArray): (Long, Int) -> ByteArray = { off, len ->
        val end = minOf(data.size.toLong(), off + len).toInt()
        if (off >= data.size) ByteArray(0) else data.copyOfRange(off.toInt(), end)
    }

    @Test
    fun smallFileIdIsTheMd5OfTheWholeFile() {
        val data = "hello".toByteArray()
        assertEquals("5d41402abc4b2a76b9719d911017c592", KoSync.partialMd5(data.size.toLong(), reader(data)))
    }

    @Test
    fun partialMd5SamplesKoreadersOffsets() {
        // 300 000 bytes: samples at 0, 1024, 4096, 16384, 65536 and 262144 (1024 bytes each).
        val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val seen = ArrayList<Long>()
        KoSync.partialMd5(data.size.toLong()) { o, l -> seen += o; reader(data)(o, l) }
        assertEquals(listOf(0L, 1024L, 4096L, 16384L, 65536L, 262144L), seen)
        val expected = MessageDigest.getInstance("MD5").apply {
            for (off in listOf(0, 1024, 4096, 16384, 65536, 262144)) update(data, off, minOf(1024, data.size - off))
        }.digest().joinToString("") { "%02x".format(it) }
        assertEquals(expected, KoSync.partialMd5(data.size.toLong(), reader(data)))
        // Changing a byte that isn't sampled leaves the id alone; a sampled one changes it.
        val other = data.copyOf().also { it[3000] = (it[3000] + 1).toByte() } // between the samples at 1024 and 4096
        assertEquals(expected, KoSync.partialMd5(other.size.toLong(), reader(other)))
        val sampled = data.copyOf().also { it[4100] = (it[4100] + 1).toByte() }
        assertTrue(expected != KoSync.partialMd5(sampled.size.toLong(), reader(sampled)))
    }

    @Test
    fun filenameIdAndPasswordKey() {
        assertEquals(md5("Dune.epub".toByteArray()), KoSync.filenameMd5("books/Dune.epub"))
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", KoSync.md5("password"))
    }

    private val html = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title><style>p { margin: 0 }</style></head>
        <body>
          <h1>Chapter</h1>
          <div>
            <p>Hello world.</p>
            <p>Second <b>bold</b> para.</p>
            <p><img src="a.jpg"/></p>
          </div>
          <!-- a note -->
          <p>Last one here.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun sectionScanFindsTextBlocksWithKoreaderPaths() {
        val s = KoSync.scan(html)
        // Inline elements that start with text count too (<b>bold</b>: a finer position).
        assertEquals(
            listOf(listOf("h1[1]"), listOf("div[1]", "p[1]"), listOf("div[1]", "p[2]"), listOf("div[1]", "p[2]", "b[1]"), listOf("p[1]")),
            s.blocks.map { it.steps },
        )
        // Visible text: Chapter(7) Hello world.(11) Second bold para.(15) Last one here.(12) = 45
        assertEquals(45, s.visibleTotal)
        assertEquals(18f / 45f, s.fractionOf(s.blocks[2]), 1e-4f)
    }

    @Test
    fun xpointerRoundTrip() {
        val s = KoSync.scan(html)
        val (xp, raw) = KoSync.xpointerFor(s, spine = 4, fraction = 0.41f)
        assertEquals("/body/DocFragment[5]/body/div[1]/p[2]/text().0", xp)
        assertTrue(raw in 0f..1f)
        assertEquals(18f / 45f, KoSync.fractionFor(s, xp)!!, 1e-4f)
        assertEquals(4, KoSync.docFragment(xp))
        // The start of the section.
        assertEquals("/body/DocFragment[1]/body/h1[1]/text().0", KoSync.xpointerFor(s, 0, 0f).first)
    }

    @Test
    fun readsCrossPointAndKoreaderForms() {
        val s = KoSync.scan(html)
        // CrossPoint writes text()[n].offset; KOReader points at images too: both land on the element.
        assertEquals(18f / 45f, KoSync.fractionFor(s, "/body/DocFragment[3]/body/div/p[2]/text()[1].15")!!, 1e-4f)
        // Into the bold word: after "Chapter", "Hello world." and "Second" (24 of 45).
        assertEquals(24f / 45f, KoSync.fractionFor(s, "/body/DocFragment[3]/body/div[1]/p[2]/b/text().0")!!, 1e-4f)
        // p[3] holds only an image: no text block there, so its parent (div[1]) is used.
        assertEquals(KoSync.fractionFor(s, "/body/DocFragment[3]/body/div[1]")!!, KoSync.fractionFor(s, "/body/DocFragment[3]/body/div[1]/p[3]/img.0")!!, 1e-4f)
        // No element path at all.
        assertNull(KoSync.fractionFor(s, "/body/DocFragment[3]/body"))
        assertNull(KoSync.docFragment("12"))
    }

    @Test
    fun bookInfoSentLikeCrossPoint() {
        // CrossPoint's sync site shows "Untitled book" without these.
        val m = KoSync.metadata("Books/Dune.epub", "Dune", "Frank Herbert", "Dune", 1f, extended = false)
        assertEquals("Dune.epub", m.getString("filename"))
        assertEquals("Dune", m.getString("title"))
        assertEquals("Frank Herbert", m.getString("authors"))
        assertTrue(!m.has("series")) // series only to CrossPoint's own server
        val x = KoSync.metadata("Dune.epub", "Dune", null, "Dune", 1f, extended = true)
        assertEquals("Dune", x.getString("series"))
        assertEquals(1.0, x.getDouble("series_index"), 0.0)
        assertTrue(!x.has("authors")) // no author: left out, not blank
    }

    @Test
    fun bookPercentageAndBackByBytes() {
        val sizes = listOf(1000L, 3000L, 6000L)
        assertEquals(0.25f, KoSync.bookFraction(sizes, 1, 0.5f), 1e-6f) // (1000 + 1500) / 10000
        val (spine, inSection) = KoSync.sectionAt(sizes, 0.25f)
        assertEquals(1, spine)
        assertEquals(0.5f, inSection, 1e-6f)
        assertEquals(2 to 1f, KoSync.sectionAt(sizes, 1f))
        assertEquals(0 to 0f, KoSync.sectionAt(sizes, 0f))
    }
}
