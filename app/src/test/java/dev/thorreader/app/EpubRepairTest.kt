package dev.thorreader.app

import dev.thorreader.app.convert.EpubRepair
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB conversion damage: "fief-?complete" (Dune) read as "fief-complete". */
class EpubRepairTest {
    @Test
    fun aQuestionMarkInsideAHyphenatedWordIsDropped() {
        assertEquals("fief-complete — the drip-drip-drip of water", EpubRepair.fixText("fief-?complete — the drip-?drip-?drip of water"))
        assertEquals("will-o’-the-sand", EpubRepair.fixText("will-?o’-the-?sand"))
        assertEquals("twenty-six", EpubRepair.fixText("twenty-?six"))
        // Real question marks stay.
        for (s in listOf("Why?", "Is it - ?", "Who -? No.", "a-?", "-?b", "What? Then-", "x?y", "1-?2")) assertEquals(s, EpubRepair.fixText(s))
        assertEquals(3, EpubRepair.countIn("drip-?drip-?drip and fief-?complete"))
    }

    private fun epub(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun zip(bytes: ByteArray) = ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get()

    @Test
    fun theCopyHasFixedChaptersAndEverythingElseAsItWas() {
        val css = "p::after { content: 'a-?b'; }" // not a chapter: left alone
        val src = epub(
            "META-INF/container.xml" to "<container/>",
            "mimetype" to "application/epub+zip",
            "OEBPS/ch1.xhtml" to "<p>the drip-?drip-?drip</p>",
            "OEBPS/style.css" to css,
            "OEBPS/ch2.html" to "<p>Why?</p>",
        )
        assertEquals(2, zip(src).use { EpubRepair.damage(it) })
        val out = ByteArrayOutputStream()
        zip(src).use { EpubRepair.write(it, out) }
        zip(out.toByteArray()).use { z ->
            val names = z.entriesInPhysicalOrder.toList().map { it.name }
            assertEquals("mimetype", names.first()) // first and stored, as EPUB readers expect
            assertEquals(ZipEntry.STORED, z.getEntry("mimetype").method)
            assertEquals(5, names.size)
            fun text(n: String) = z.getInputStream(z.getEntry(n)).use { String(it.readBytes()) }
            assertEquals("<p>the drip-drip-drip</p>", text("OEBPS/ch1.xhtml"))
            assertEquals("<p>Why?</p>", text("OEBPS/ch2.html"))
            assertArrayEquals(css.toByteArray(), z.getInputStream(z.getEntry("OEBPS/style.css")).use { it.readBytes() })
            assertEquals(0, EpubRepair.damage(z))
        }
    }
}
