package dev.thorreader.app.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A book file tried to make us read or write far too much (a "decompression bomb"), or is broken. */
class TooLargeException(what: String) : IOException("This file is damaged or unsafe ($what is too large).")

/**
 * Safety limits for everything read from book files. Real books stay far below them; a
 * malicious or broken archive hits them and is rejected instead of filling memory or storage.
 */
object Limits {
    /** One comic page / picture in memory. */
    const val MAX_PAGE = 128L shl 20

    /** XML / metadata files (OPF, NCX, ComicInfo.xml, Word/ODT XML). */
    const val MAX_XML = 64L shl 20

    /** A whole book file read into memory (TXT, FB2, Kindle, HTML...). */
    const val MAX_FILE_IN_MEMORY = 256L shl 20

    /** Unpacking a CBR / CB7: entries, one file, everything together. */
    const val MAX_ENTRIES = 10_000
    const val MAX_UNPACKED_FILE = 256L shl 20
    const val MAX_UNPACKED_TOTAL = 4L shl 30

    /** Read at most [max] bytes; more means the file is rejected. */
    fun readCapped(input: InputStream, max: Long, what: String = "an entry"): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw TooLargeException(what)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Counts what is written through it; throws past [perFile] or the shared [total] budget. */
    class CappedOutput(out: OutputStream, private val perFile: Long, private val total: Budget) : FilterOutputStream(out) {
        private var written = 0L
        override fun write(b: Int) {
            count(1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count(len.toLong())
            out.write(b, off, len)
        }

        private fun count(n: Long) {
            written += n
            if (written > perFile) throw TooLargeException("an unpacked page")
            total.add(n)
        }
    }

    class Budget(private val max: Long) {
        var used = 0L
            private set

        fun add(n: Long) {
            used += n
            if (used > max) throw TooLargeException("the unpacked comic")
        }
    }

    /**
     * A file inside [dir] called [name] - only if it really stays inside [dir] (no "..", no
     * absolute paths, no folder parts at all). Null otherwise.
     */
    fun childFile(dir: File, name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == ".." || name.contains('\u0000')) return null
        val f = File(dir, name)
        val base = dir.canonicalFile
        return if (f.canonicalFile.parentFile == base) f else null
    }

    /** A safe file extension (letters/digits, at most 5) taken from an archive entry name. */
    fun safeExtension(entryName: String, default: String = "jpg"): String {
        val ext = entryName.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()
        return if (ext.length in 1..5 && ext.all { it in 'a'..'z' || it in '0'..'9' }) ext else default
    }
}

/**
 * XML from book files without its DOCTYPE (and so without any entity declarations): no
 * external entities can be fetched and no "billion laughs" expansion can happen (XXE).
 * Works on the raw bytes, so any ASCII-compatible encoding (UTF-8, Latin-1...) is kept.
 */
object SafeXml {
    fun strip(bytes: ByteArray): ByteArray {
        val s = String(bytes, Charsets.ISO_8859_1)
        val start = s.indexOf("<!DOCTYPE", ignoreCase = true)
        if (start < 0) return bytes
        var i = start + 9
        var depth = 0
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> if (depth > 0) depth--
                '>' -> if (depth == 0) break
            }
            i++
        }
        val end = (i + 1).coerceAtMost(s.length)
        return (s.substring(0, start) + s.substring(end)).toByteArray(Charsets.ISO_8859_1)
    }

    /** Read (capped) and strip, ready for the XML parser. */
    fun read(input: InputStream, max: Long = Limits.MAX_XML): ByteArray = strip(Limits.readCapped(input, max, "an XML file"))
}
