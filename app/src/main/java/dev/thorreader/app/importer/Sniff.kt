package dev.thorreader.app.importer

/**
 * What a file really is, from its first bytes (magic numbers) - the name only breaks ties inside
 * one family (a zip can be an EPUB, CBZ, DOCX, ODT or XPS). Used for files handed to us by other
 * apps, where neither the name nor the MIME type can be trusted. A ".cbr" that is really a zip
 * comes back as CBZ, a ".pdf" that is really HTML as HTML, an executable as null.
 */
object Sniff {
    /** Read this many bytes from the start of the file for [detect]. */
    const val HEAD = 4096

    private val ZIP_KINDS = setOf(BookFormat.EPUB, BookFormat.CBZ, BookFormat.DOCX, BookFormat.ODT, BookFormat.XPS)
    private val MOBI_KINDS = setOf(BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3)
    private val TEXT_KINDS = setOf(BookFormat.TXT, BookFormat.HTML, BookFormat.MARKDOWN, BookFormat.FB2)

    fun detect(head: ByteArray, name: String?): BookFormat? {
        val byName = name?.let { BookFormat.fromFileName(it) }
        fun at(off: Int, vararg b: Int) = head.size >= off + b.size && b.indices.all { head[off + it] == b[it].toByte() }
        fun ascii(off: Int, s: String) = at(off, *s.map { it.code }.toIntArray())
        val latin = String(head, Charsets.ISO_8859_1)

        return when {
            latin.take(1024).contains("%PDF-") -> BookFormat.PDF
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> when {
                byName in ZIP_KINDS && byName != BookFormat.EPUB -> byName
                latin.contains("mimetypeapplication/epub+zip") -> BookFormat.EPUB
                latin.contains("mimetypeapplication/vnd.oasis.opendocument.text") -> BookFormat.ODT
                byName == BookFormat.EPUB -> BookFormat.EPUB // some EPUBs don't store "mimetype" first
                else -> BookFormat.CBZ
            }
            ascii(0, "Rar!\u001A\u0007") -> BookFormat.CBR
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> BookFormat.CB7
            ascii(257, "ustar") -> BookFormat.CBT
            ascii(60, "BOOKMOBI") || ascii(60, "TEXtREAd") -> if (byName in MOBI_KINDS) byName else BookFormat.MOBI
            ascii(0, "{\\rtf") -> BookFormat.RTF
            at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x89, 0x50, 0x4E, 0x47) || ascii(0, "GIF8") ||
                (ascii(0, "RIFF") && ascii(8, "WEBP")) || (ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis"))) -> BookFormat.IMAGE
            looksLikeText(head) -> {
                val lower = latin.take(2048).lowercase()
                when {
                    lower.contains("<fictionbook") -> BookFormat.FB2
                    byName in TEXT_KINDS && byName != BookFormat.FB2 -> byName
                    lower.contains("<html") || lower.contains("<!doctype html") -> BookFormat.HTML
                    else -> BookFormat.TXT
                }
            }
            else -> null
        }
    }

    /** Text: no NUL bytes and almost no control characters (UTF-8, UTF-16 with BOM aside). */
    fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        var odd = 0
        for (b in head) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x20 && c != 9 && c != 10 && c != 13 && c != 12) odd++
        }
        return odd * 100 < head.size
    }
}
