package dev.thorreader.app.convert

import android.content.Context
import android.util.Log
import dev.thorreader.app.TAG
import dev.thorreader.app.io.Limits
import dev.thorreader.app.io.SafFile
import dev.thorreader.app.io.TooLargeException
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Text damage some EPUBs carry from a bad conversion, fixed in a copy (the user's file is never
 * touched): a "?" left where a break character inside a hyphenated word used to be
 * ("fief-?complete", "drip-?drip-?drip"; Dune has 1,634 of them). A "?" right after a hyphen
 * with a letter on both sides is never real punctuation, so it is dropped.
 */
object EpubRepair {
    private val BROKEN_HYPHEN = Regex("(?<=\\p{L}-)\\?(?=\\p{L})")

    fun fixText(s: String): String = BROKEN_HYPHEN.replace(s, "")

    fun countIn(s: String): Int = BROKEN_HYPHEN.findAll(s).count()

    private fun isChapter(name: String) = name.lowercase().let { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }

    private fun read(zip: ZipFile, e: org.apache.commons.compress.archivers.zip.ZipArchiveEntry, max: Long): ByteArray =
        zip.getInputStream(e).use { Limits.readCapped(it, max, "an entry") }

    /** How many broken hyphens the book's chapters hold. */
    fun damage(zip: ZipFile): Int {
        var n = 0
        var entries = 0
        for (e in zip.entries) {
            if (++entries > Limits.MAX_ENTRIES) throw TooLargeException("the list of files")
            if (e.isDirectory || !isChapter(e.name)) continue
            n += countIn(String(read(zip, e, Limits.MAX_XML), Charsets.UTF_8))
        }
        return n
    }

    /** A copy of [zip] with the chapters fixed, written to [out] ("mimetype" first and stored, as EPUB requires). */
    fun write(zip: ZipFile, out: OutputStream) {
        val all = zip.entriesInPhysicalOrder.toList().filter { !it.isDirectory }
        if (all.size > Limits.MAX_ENTRIES) throw TooLargeException("the list of files")
        var total = 0L
        ZipOutputStream(out.buffered()).use { z ->
            val mt = all.firstOrNull { it.name == "mimetype" }?.let { read(zip, it, 1024) } ?: "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong(); crc = CRC32().apply { update(mt) }.value
            })
            z.write(mt)
            z.closeEntry()
            val seen = HashSet<String>()
            for (e in all) {
                if (e.name == "mimetype" || !seen.add(e.name)) continue
                z.putNextEntry(ZipEntry(e.name))
                if (isChapter(e.name)) {
                    val text = fixText(String(read(zip, e, Limits.MAX_XML), Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    total += text.size
                    z.write(text)
                } else {
                    total += zip.getInputStream(e).use { copyCapped(it, z, Limits.MAX_UNPACKED_FILE) }
                }
                if (total > Limits.MAX_UNPACKED_TOTAL) throw TooLargeException("the book")
                z.closeEntry()
            }
        }
    }

    private fun copyCapped(input: InputStream, out: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) return n
            n += r
            if (n > max) throw TooLargeException("an entry")
            out.write(buf, 0, r)
        }
    }

    /**
     * The repaired copy of this EPUB to read instead of the file, or null when it needs none.
     * Decided once per file (uri + size): a damaged book gets its copy in app storage, a clean
     * one a marker, so later opens cost nothing. Any failure: null (the file is read as it is).
     */
    fun repairedCopy(context: Context, uri: String, size: Long, file: SafFile): File? = runCatching {
        val dir = File(context.filesDir, "converted").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        val fixed = File(dir, "$key-$size.fixed.epub")
        val clean = File(dir, "$key-$size.clean")
        if (fixed.exists() && fixed.length() > 0) return@runCatching fixed
        if (clean.exists()) return@runCatching null
        val t0 = System.currentTimeMillis()
        ZipFile.builder().setSeekableByteChannel(file.newChannel()).get().use { zip ->
            val n = damage(zip)
            if (n == 0) {
                clean.createNewFile()
                return@runCatching null
            }
            val tmp = File(dir, fixed.name + ".tmp")
            tmp.outputStream().use { write(zip, it) }
            if (!tmp.renameTo(fixed)) { tmp.delete(); return@runCatching null }
            Log.i(TAG, "EPUB text repaired: $n broken hyphens (\"-?\") fixed in a copy (${System.currentTimeMillis() - t0} ms)")
        }
        fixed
    }.onFailure { Log.w(TAG, "EPUB repair check failed (${it.message}); reading the file as it is") }.getOrNull()
}
