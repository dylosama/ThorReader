package dev.thorreader.app

import dev.thorreader.app.convert.MobiFile
import dev.thorreader.app.engine.ChapterViewCache
import dev.thorreader.app.engine.PageView
import dev.thorreader.app.engine.layoutPercent
import dev.thorreader.app.io.CacheBudget
import dev.thorreader.app.io.ComicArchives
import dev.thorreader.app.io.Limits
import dev.thorreader.app.io.SafeXml
import dev.thorreader.app.io.TarImageArchive
import dev.thorreader.app.io.TooLargeException
import dev.thorreader.app.io.ZipImageArchive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Step 20: zip slip, decompression bombs, XXE, corrupt archives, cache budget, progress. */
class HardeningTest {

    // ---- zip slip --------------------------------------------------------------------------

    @Test
    fun unpackedFilesNeverLeaveTheirFolder() {
        val dir = File.createTempFile("unpack", "").apply { delete(); mkdirs() }
        try {
            val evil = listOf("../../evil.jpg", "/abs/evil.jpg", "a/../../b.jpg", "x.jpg/../../../y.jpg", "..\\..\\w.png", "ok.jpg")
            val order = evil.withIndex().associate { it.value to it.index }
            for (name in evil) {
                val f = ComicArchives.targetFile(dir, name, order) ?: continue
                assertEquals("$name stays inside", dir.canonicalFile, f.canonicalFile.parentFile)
                assertTrue("only safe characters: ${f.name}", f.name.matches(Regex("\\d{5}\\.[a-z0-9]{1,5}")))
            }
            assertEquals("ComicInfo.xml", ComicArchives.targetFile(dir, "../../ComicInfo.xml", order)!!.name)
            assertNull(Limits.childFile(dir, "../x"))
            assertNull(Limits.childFile(dir, "a/b"))
            assertNull(Limits.childFile(dir, ".."))
            assertEquals("jpg", Limits.safeExtension("x.j/p.g../evil"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- decompression bombs ---------------------------------------------------------------

    @Test
    fun readsAreCapped() {
        // A "bomb": a stream that never ends.
        val endless = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        try {
            Limits.readCapped(endless, 1L shl 20)
            fail("an endless stream must be rejected")
        } catch (e: TooLargeException) {
            // expected
        }
        // Unpacking: one huge file ...
        val out = Limits.CappedOutput(ByteArrayOutputStream(), 1L shl 20, Limits.Budget(3L shl 20))
        try {
            out.write(ByteArray(2 shl 20))
            fail("per-file cap")
        } catch (e: TooLargeException) {
            // expected
        }
        // ... or many files adding up.
        val shared = Limits.Budget(3L shl 20)
        try {
            repeat(5) { Limits.CappedOutput(ByteArrayOutputStream(), 1L shl 20, shared).write(ByteArray(1 shl 20)) }
            fail("total cap")
        } catch (e: TooLargeException) {
            // expected
        }
    }

    @Test
    fun zipBombPageIsRejected() {
        // 300 MB of zeros compress to well under 1 MB: reading that page must stop at the cap.
        val buf = ByteArrayOutputStream()
        ZipArchiveOutputStream(buf).use { z ->
            z.putArchiveEntry(ZipArchiveEntry("001.jpg"))
            val zeros = ByteArray(1 shl 20)
            repeat(300) { z.write(zeros) }
            z.closeArchiveEntry()
        }
        val zip = ZipImageArchive(SeekableInMemoryByteChannel(buf.toByteArray()))
        assertEquals(listOf("001.jpg"), zip.pages)
        try {
            zip.read("001.jpg")
            fail("the bomb page must be rejected")
        } catch (e: TooLargeException) {
            // expected
        } finally {
            zip.close()
        }
    }

    // ---- XXE -------------------------------------------------------------------------------

    @Test
    fun doctypeAndEntitiesAreRemoved() {
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///data/data/x\"> <!ENTITY a \"aaaa\"> ]>" +
            "<ComicInfo><Title>&xxe;</Title></ComicInfo>"
        val clean = String(SafeXml.strip(xxe.toByteArray()))
        assertFalse(clean.contains("DOCTYPE", ignoreCase = true))
        assertFalse(clean.contains("ENTITY"))
        assertFalse(clean.contains("file:///"))
        assertTrue(clean.startsWith("<?xml version=\"1.0\"?><ComicInfo>"))
        // A simple DOCTYPE without an internal subset, and documents without one.
        assertEquals("<a/>", String(SafeXml.strip("<!DOCTYPE html PUBLIC \"x\" \"y\"><a/>".toByteArray())))
        assertEquals("<b>é</b>", String(SafeXml.strip("<b>é</b>".toByteArray()), Charsets.UTF_8))
    }

    // ---- corrupt archives ------------------------------------------------------------------

    @Test
    fun corruptArchivesFailCleanly() {
        val junk = ByteArray(4096) { (it * 31).toByte() }
        val attempts = listOf<() -> Any>(
            { ZipImageArchive(SeekableInMemoryByteChannel(junk)) },
            { TarImageArchive(SeekableInMemoryByteChannel(junk)).pages },
            { MobiFile(junk) },
            { MobiFile(ByteArray(10)) },
        )
        for (open in attempts) {
            try {
                open()
            } catch (e: IOException) {
                // a clear "damaged" error, not a crash
            }
        }
        // A ZIP that is cut off half-way.
        val buf = ByteArrayOutputStream()
        ZipArchiveOutputStream(buf).use { z ->
            z.putArchiveEntry(ZipArchiveEntry("001.jpg"))
            z.write(ByteArray(100_000) { (it % 251).toByte() })
            z.closeArchiveEntry()
        }
        val cut = buf.toByteArray().copyOf(buf.size() / 2)
        try {
            ZipImageArchive(SeekableInMemoryByteChannel(cut))
            fail("a truncated zip should be reported")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("damaged"))
        }
    }

    // ---- caches and progress ---------------------------------------------------------------

    @Test
    fun cacheBudgetRemovesOldestButKeepsRecent() {
        val now = 10_000_000_000L
        val mb = 1L shl 20
        val items = listOf(
            CacheBudget.Item("old", 300 * mb, now - 10 * 86_400_000L),
            CacheBudget.Item("mid", 200 * mb, now - 86_400_000L),
            CacheBudget.Item("open book", 400 * mb, now - 60_000L), // used a minute ago
        )
        assertEquals(listOf("old", "mid"), CacheBudget.toDelete(items, 500 * mb, now).map { it.path })
        assertTrue(CacheBudget.toDelete(items, 2000 * mb, now).isEmpty())
    }

    @Test
    fun layoutProgressNeverSitsAt100() {
        assertEquals(0, layoutPercent(0, 10))
        assertEquals(50, layoutPercent(5, 10))
        assertEquals(99, layoutPercent(10, 10)) // all done: the badge goes away instead of showing 100
        assertEquals(99, layoutPercent(1, 1))
        for (n in 1..50) for (d in 0..n) assertTrue(layoutPercent(d, n) in 0..99)
    }

    @Test
    fun chapterPagesAreBuiltOncePerLayout() {
        val cache = ChapterViewCache()
        val page = listOf(PageView(emptyList()))
        repeat(5) { cache.get("layout A", 3, final = true) { page } }
        assertEquals("final chapters are built once", 1, cache.builds)
        repeat(3) { cache.get("layout A", 7, final = false) { page } }
        assertEquals("unfinished chapters are rebuilt (their headers may still change)", 4, cache.builds)
        cache.get("layout B", 3, final = true) { page }
        assertEquals("a new layout starts over", 5, cache.builds)
    }
}
