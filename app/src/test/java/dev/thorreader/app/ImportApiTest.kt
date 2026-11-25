package dev.thorreader.app

import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.ArchiveSafety
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.importer.ImportContract
import dev.thorreader.app.importer.IncomingImporter
import dev.thorreader.app.importer.IncomingMeta
import dev.thorreader.app.importer.SharedLinks
import dev.thorreader.app.importer.Sniff
import dev.thorreader.app.importer.UnsafeArchiveException
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Step 25 part A: files from other apps - sniffing, archive safety, duplicates, metadata, manifest. */
class ImportApiTest {

    // ---- magic bytes ------------------------------------------------------------------------

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun zipHead(first: String, content: String = "") = tempZip(listOf(first to content)).readBytes().copyOf(4096)

    @Test
    fun typeComesFromTheBytesNotTheName() {
        assertEquals(BookFormat.PDF, Sniff.detect("%PDF-1.7\n...".toByteArray(), "x.epub"))
        assertEquals(BookFormat.EPUB, Sniff.detect(zipHead("mimetype", "application/epub+zip"), "book.zip"))
        assertEquals("a .cbr that is really a zip", BookFormat.CBZ, Sniff.detect(zipHead("001.jpg", "x"), "comic.cbr"))
        assertEquals(BookFormat.DOCX, Sniff.detect(zipHead("[Content_Types].xml", "x"), "letter.docx"))
        assertEquals(BookFormat.CBR, Sniff.detect("Rar!\u001A\u0007\u0000".toByteArray(Charsets.ISO_8859_1), "comic.cbz"))
        assertEquals(BookFormat.CB7, Sniff.detect(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0, 4), null))
        assertEquals(BookFormat.CBT, Sniff.detect(ByteArray(300).also { "ustar".toByteArray().copyInto(it, 257) }, "x.cbt"))
        assertEquals(BookFormat.AZW3, Sniff.detect(ByteArray(80).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }, "k.azw3"))
        assertEquals(BookFormat.MOBI, Sniff.detect(ByteArray(80).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }, "k.pdf"))
        assertEquals(BookFormat.RTF, Sniff.detect("{\\rtf1\\ansi".toByteArray(), "x.rtf"))
        assertEquals(BookFormat.IMAGE, Sniff.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10), "cover.pdf"))
        assertEquals("HTML named .pdf", BookFormat.HTML, Sniff.detect("<!DOCTYPE html><html><body>hi".toByteArray(), "fake.pdf"))
        assertEquals(BookFormat.FB2, Sniff.detect("<?xml version=\"1.0\"?><FictionBook xmlns=\"x\">".toByteArray(), "b.txt"))
        assertEquals(BookFormat.MARKDOWN, Sniff.detect("# Title\n\nText".toByteArray(), "notes.md"))
        assertEquals(BookFormat.TXT, Sniff.detect("Just some words.\n".toByteArray(), null))
    }

    @Test
    fun programsAndRandomBinariesAreRefused() {
        assertNull("ELF executable", Sniff.detect(bytes(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0, 0, 0), "book.epub"))
        assertNull("Windows .exe", Sniff.detect(bytes(0x4D, 0x5A, 0x90, 0, 3, 0, 0, 0), "comic.cbz"))
        assertNull("empty file", Sniff.detect(ByteArray(0), "x.txt"))
        assertFalse(Sniff.looksLikeText(bytes(1, 2, 3, 0, 5)))
    }

    // ---- archive safety ---------------------------------------------------------------------

    private fun tempZip(entries: List<Pair<String, String>>, big: Pair<String, Int>? = null): File {
        val f = File.createTempFile("thor", ".zip").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (n, c) ->
                val e = ZipEntry(n)
                if (n == "mimetype") { // stored, like in a real EPUB
                    val data = c.toByteArray()
                    e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e); z.write(c.toByteArray()); z.closeEntry()
            }
            big?.let { (n, size) -> z.putNextEntry(ZipEntry(n)); z.write(ByteArray(size)); z.closeEntry() }
        }
        return f
    }

    private fun assertUnsafe(f: File, format: BookFormat, caps: ArchiveSafety.Caps = ArchiveSafety.Caps()) {
        try {
            ArchiveSafety.check(f, format, caps)
            fail("expected the archive to be refused")
        } catch (e: UnsafeArchiveException) {
            // expected
        }
    }

    @Test
    fun zipSlipNamesAreRefused() {
        assertUnsafe(tempZip(listOf("../../evil.so" to "x")), BookFormat.CBZ)
        assertUnsafe(tempZip(listOf("/data/data/dev.thorreader.app/x" to "x")), BookFormat.EPUB)
        assertUnsafe(tempZip(listOf("pages\\..\\..\\x.jpg" to "x")), BookFormat.CBZ)
        assertUnsafe(tempZip(listOf("C:/Windows/x" to "x")), BookFormat.CBZ)
        assertTrue(ArchiveSafety.safeEntryName("Chapter 1/001.jpg"))
        assertTrue(ArchiveSafety.safeEntryName("..hidden.jpg")) // dots in a name are fine, ".." as a folder is not
        // A normal comic passes.
        ArchiveSafety.check(tempZip(listOf("001.jpg" to "a", "002.jpg" to "b", "ComicInfo.xml" to "<ComicInfo/>")), BookFormat.CBZ)
    }

    @Test
    fun decompressionBombsAndHugeArchivesAreRefused() {
        // 3 MB of zeros packs to a few KB: over a 1 MB per-entry cap.
        assertUnsafe(tempZip(emptyList(), "zeros.jpg" to (3 shl 20)), BookFormat.CBZ, ArchiveSafety.Caps(maxEntry = 1L shl 20))
        assertUnsafe(tempZip(listOf("a" to "1", "b" to "2", "c" to "3")), BookFormat.CBZ, ArchiveSafety.Caps(maxEntries = 2))
        assertUnsafe(tempZip(emptyList(), "z" to (3 shl 20)), BookFormat.CBZ, ArchiveSafety.Caps(maxTotal = 2L shl 20))
        // A broken file is refused, not crashed on.
        assertUnsafe(File.createTempFile("thor", ".zip").apply { writeBytes("PK\u0003\u0004garbage".toByteArray()); deleteOnExit() }, BookFormat.CBZ)
    }

    @Test
    fun tarWithAbsolutePathsOrLinksIsRefused() {
        val f = File.createTempFile("thor", ".cbt").apply { deleteOnExit() }
        TarArchiveOutputStream(f.outputStream()).use { t ->
            val e = TarArchiveEntry("../../etc/passwd").apply { size = 1 }
            t.putArchiveEntry(e); t.write(byteArrayOf(1)); t.closeArchiveEntry()
        }
        assertUnsafe(f, BookFormat.CBT)
    }

    // ---- SHA-256 duplicates -----------------------------------------------------------------

    @Test
    fun sha256OfTheCopyIdentifiesTheContent() {
        val out = File.createTempFile("thor", ".bin").apply { deleteOnExit() }
        val abc = IncomingImporter.copyHashing("abc".byteInputStream(), out)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc)
        assertEquals("abc", out.readText())
        assertEquals(abc, IncomingImporter.sha256("abc".byteInputStream()))
        assertNotEquals(abc, IncomingImporter.sha256("abd".byteInputStream()))
        try {
            IncomingImporter.copyHashing(ByteArray(2000).inputStream(), out, max = 1000)
            fail("over the size cap")
        } catch (e: java.io.IOException) {
            // expected
        }
    }

    @Test
    fun duplicatesAreFoundByHashAndOldBooksAreHashedOnlyWhenNeeded() = runBlocking {
        val stored = HashMap<Long, String>()
        // Known hash: no file is read at all.
        var reads = 0
        assertEquals(7L, IncomingImporter.duplicateOf("h1", known = 7L, candidates = listOf(1L to { reads++; "h1" }), store = { i, h -> stored[i] = h }))
        assertEquals(0, reads)
        // Not known: same-size books are hashed (and remembered) until one matches.
        val hit = IncomingImporter.duplicateOf("h2", null, listOf(3L to { "x" }, 4L to { null }, 5L to { "h2" }, 6L to { "h2" }), { i, h -> stored[i] = h })
        assertEquals(5L, hit)
        assertEquals(mapOf(3L to "x", 5L to "h2"), stored)
        // New content.
        assertNull(IncomingImporter.duplicateOf("new", null, listOf(8L to { "old" }), { _, _ -> }))
    }

    // ---- metadata extras --------------------------------------------------------------------

    private val book = Book(uri = "content://x", fileName = "f.cbz", format = "CBZ", sizeBytes = 1, title = "f", author = "From File", editedFields = "year")

    @Test
    fun apiMetadataCountsAsHandEditsAndIsChecked() {
        val m = IncomingMeta.parse(
            mapOf(
                ImportContract.M_TITLE to "  Blue Period, Vol. 3 ",
                ImportContract.M_AUTHORS to arrayOf("Tsubasa Yamaguchi", "", "Tsubasa Yamaguchi"),
                ImportContract.M_SERIES to "Blue Period",
                ImportContract.M_SERIES_NUMBER to 3f,
                ImportContract.M_TYPE to "Manga",
                ImportContract.M_GENRES to arrayOf("Drama", "Slice of Life"),
                ImportContract.M_DIRECTION to "rtl",
                ImportContract.M_MODE to 2,
            )::get,
        )
        val b = m.applyTo(book)
        assertEquals("Blue Period, Vol. 3", b.title)
        assertEquals("Tsubasa Yamaguchi", b.author)
        assertEquals("Blue Period", b.series)
        assertEquals(3f, b.seriesNumber)
        assertEquals("Manga", b.typeOverride)
        assertTrue(b.isManga)
        assertEquals(true, b.rtlOverride)
        assertEquals(ReadingMode.BOOK_REVERSED.name, b.mode)
        assertEquals("Drama; Slice of Life", b.genres)
        // Given fields are hand edits (the lookup leaves them alone); earlier edits are kept.
        listOf("year", "title", "authors", "series", "number", "type", "genres").forEach { assertTrue(it, b.edited(it)) }
        assertFalse(b.edited("description"))
    }

    @Test
    fun badOrMissingMetadataIsIgnored() {
        val m = IncomingMeta.parse(
            mapOf(
                ImportContract.M_TITLE to "   ",
                ImportContract.M_AUTHORS to 42,
                ImportContract.M_SERIES_NUMBER to "NaN",
                ImportContract.M_TYPE to "Movie",
                ImportContract.M_DIRECTION to "up",
                ImportContract.M_MODE to 7,
            )::get,
        )
        assertTrue(m.isEmpty)
        val b = m.applyTo(book)
        assertEquals("f", b.title)
        assertEquals("From File", b.author)
        assertEquals("year", b.editedFields)
        assertEquals(ReadingMode.TOP, IncomingMeta.parse(mapOf(ImportContract.M_MODE to "3")::get).mode)
        assertEquals(3.5f, IncomingMeta.parse(mapOf(ImportContract.M_SERIES_NUMBER to "3.5")::get).seriesNumber)
        assertEquals("Comics", IncomingMeta.parse(mapOf(ImportContract.M_TYPE to "comic")::get).type)
    }

    @Test
    fun copiedFilesGetSafeNamesWithTheRightExtension() {
        assertEquals("x.cbz", IncomingImporter.safeFileName("../../x.cbr", BookFormat.CBZ))
        assertEquals("ab.epub", IncomingImporter.safeFileName("a<b>.epub", BookFormat.EPUB))
        assertEquals("book.pdf", IncomingImporter.safeFileName(".pdf", BookFormat.PDF))
        assertEquals("Dune.epub", IncomingImporter.safeFileName("Dune", BookFormat.EPUB))
        assertEquals(124, IncomingImporter.safeFileName("x".repeat(300) + ".pdf", BookFormat.PDF).length)
    }

    // ---- intent handling --------------------------------------------------------------------

    private data class U(val s: String)

    @Test
    fun sharedLinksAreContentOnly() {
        val scheme: (U) -> String? = { it.s.substringBefore("://") }
        val a = U("content://files/a.epub")
        val b = U("content://files/b.cbz")
        assertEquals(listOf(a), SharedLinks.pick("android.intent.action.SEND", a, emptyList(), null, listOf(a), scheme))
        assertEquals(listOf(a, b), SharedLinks.pick("android.intent.action.SEND_MULTIPLE", null, listOf(a, b, U("file:///sdcard/x.epub")), null, emptyList(), scheme))
        assertEquals(listOf(b), SharedLinks.pick("android.intent.action.VIEW", null, emptyList(), b, emptyList(), scheme))
        assertEquals("raw file path", emptyList<U>(), SharedLinks.pick("android.intent.action.VIEW", null, emptyList(), U("file:///data/x.pdf"), emptyList(), scheme))
        assertEquals("http link", emptyList<U>(), SharedLinks.pick("android.intent.action.VIEW", null, emptyList(), U("https://x/y.epub"), emptyList(), scheme))
        assertEquals("other action", emptyList<U>(), SharedLinks.pick("android.intent.action.DELETE", a, emptyList(), a, listOf(a), scheme))
        assertEquals(200, SharedLinks.pick("android.intent.action.SEND_MULTIPLE", null, (1..300).map { U("content://f/$it") }, null, emptyList(), scheme).size)
    }

    // ---- signature permission (manifest) -----------------------------------------------------

    @Test
    fun theImportApiNeedsTheSignaturePermission() {
        val f = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
        val ns = "http://schemas.android.com/apk/res/android"
        fun org.w3c.dom.Element.a(n: String) = getAttributeNS(ns, n)
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }

        val perm = elements("permission").single { it.a("name") == ImportContract.PERMISSION_IMPORT }
        assertEquals("signature", perm.a("protectionLevel"))

        val components = elements("service") + elements("activity")
        val protectedActions = setOf(ImportContract.ACTION_IMPORT_BOOKS, ImportContract.ACTION_CHOOSE_FOLDER)
        var found = 0
        for (c in components) {
            val actions = c.getElementsByTagName("action").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).a("name") } }
            if (actions.any { it in protectedActions }) {
                found++
                assertEquals("${c.a("name")} must require the import permission", ImportContract.PERMISSION_IMPORT, c.a("permission"))
                assertTrue("${c.a("name")} only serves the trusted action", actions.all { it in protectedActions })
            }
        }
        assertEquals(2, found)
        val service = elements("service").single { it.a("name") == ".importer.ImportService" }
        assertEquals("true", service.a("exported"))
        // The public share screen must not be the API's way in.
        val share = elements("activity").single { it.a("name") == ".importer.ShareImportActivity" }
        assertEquals("", share.a("permission"))
    }
}

class ImportApiTarLinkTest {
    @Test
    fun tarLinksAreRefused() {
        val f = File.createTempFile("thor", ".cbt").apply { deleteOnExit() }
        TarArchiveOutputStream(f.outputStream()).use { t ->
            t.putArchiveEntry(TarArchiveEntry("001.jpg", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/data/data/x" })
            t.closeArchiveEntry()
        }
        try {
            ArchiveSafety.check(f, BookFormat.CBT)
            fail("expected the archive to be refused")
        } catch (e: UnsafeArchiveException) {
            // expected
        }
    }
}
