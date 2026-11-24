package dev.thorreader.app.convert

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A book converted from another format, before it is written out as an EPUB that MuPDF reads
 * like any other EPUB (chapters laid out one at a time, headings, table of contents).
 */
class ConvBook {
    var title: String? = null
    val authors = ArrayList<String>()
    val subjects = ArrayList<String>()
    var language: String? = null
    var series: String? = null
    var seriesIndex: Float? = null

    /** Chapters in reading order. */
    val sections = ArrayList<Section>()

    /** Pictures etc., by path inside the EPUB (e.g. "images/img3.jpg"). */
    val resources = LinkedHashMap<String, ByteArray>()

    /** Extra style sheets, by path inside the EPUB. */
    val styles = LinkedHashMap<String, String>()

    /** Resource path of the cover image, if known. */
    var coverPath: String? = null

    /**
     * [body] = inner HTML of <body>, or with [fullDocument] a complete (X)HTML document that is
     * stored as it is. [title] goes into the table of contents.
     */
    class Section(val title: String?, val body: String, val fullDocument: Boolean = false)

    fun addSection(title: String?, body: String) {
        if (body.isNotBlank()) sections += Section(title, body)
    }

    /** Store a picture and return its path inside the EPUB. */
    fun addImage(bytes: ByteArray, hint: String = ""): String {
        val ext = imageExt(bytes) ?: hint.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "bin"
        val path = "images/img${resources.size + 1}.$ext"
        resources[path] = bytes
        return path
    }
}

/** File extension from an image's first bytes, or null if it's not a picture we know. */
fun imageExt(b: ByteArray): String? = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
    b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
    b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
    b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp"
    b.size >= 5 && String(b, 0, 5, Charsets.ISO_8859_1).let { it == "<?xml" || it.startsWith("<svg") } -> "svg"
    else -> null
}

fun mediaType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "css" -> "text/css"
    "xhtml", "html", "htm" -> "application/xhtml+xml"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    else -> "application/octet-stream"
}

/** Escape text for HTML/XML content and attribute values. */
fun esc(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) when (c) {
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '&' -> sb.append("&amp;")
        '"' -> sb.append("&quot;")
        else -> if (c.code < 0x20 && c != '\n' && c != '\t') Unit else sb.append(c)
    }
    return sb.toString()
}

/** Writes a [ConvBook] as a small, valid EPUB 2 file (with an NCX table of contents). */
object EpubWriter {
    private const val BASE_CSS = """
body { margin: 0; }
p { margin: 0 0 0.6em 0; }
h1, h2, h3, h4, h5, h6 { margin: 1em 0 0.5em 0; }
img { max-width: 100%; }
table { border-collapse: collapse; }
td, th { border: 1px solid #888; padding: 2px 4px; }
pre { white-space: pre-wrap; }
blockquote { margin: 0.5em 1.5em; }
"""

    fun write(book: ConvBook, out: File) {
        if (book.sections.isEmpty()) book.sections += ConvBook.Section(null, "<p></p>")
        val tmp = File(out.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            // "mimetype" must come first and be stored uncompressed.
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mt.size.toLong()
                compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            z.write(mt)
            z.closeEntry()
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
            put(
                "META-INF/container.xml",
                ("""<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                    """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""").toByteArray(),
            )
            put("OEBPS/style.css", BASE_CSS.toByteArray())
            book.styles.forEach { (path, css) -> put("OEBPS/$path", css.toByteArray()) }
            book.resources.forEach { (path, bytes) -> put("OEBPS/$path", bytes) }
            val styleLinks = (listOf("style.css") + book.styles.keys).joinToString("") { """<link rel="stylesheet" type="text/css" href="../$it"/>""" }
            book.sections.forEachIndexed { i, s ->
                val doc = if (s.fullDocument) s.body else
                    """<?xml version="1.0" encoding="utf-8"?>""" + "\n" +
                        """<html xmlns="http://www.w3.org/1999/xhtml"><head><meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>""" +
                        """<title>${esc(s.title ?: "")}</title>$styleLinks</head><body>${s.body}</body></html>"""
                put("OEBPS/text/s${i + 1}.xhtml", doc.toByteArray(Charsets.UTF_8))
            }
            put("OEBPS/content.opf", opf(book).toByteArray(Charsets.UTF_8))
            put("OEBPS/toc.ncx", ncx(book).toByteArray(Charsets.UTF_8))
        }
        out.delete()
        if (!tmp.renameTo(out)) throw java.io.IOException("Could not save converted book")
    }

    private fun opf(b: ConvBook): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">""")
        append("""<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">""")
        append("<dc:identifier id=\"uid\">thor-reader-converted</dc:identifier>")
        append("<dc:title>${esc(b.title ?: "Untitled")}</dc:title>")
        b.authors.forEach { append("<dc:creator>${esc(it)}</dc:creator>") }
        b.subjects.forEach { append("<dc:subject>${esc(it)}</dc:subject>") }
        append("<dc:language>${esc(b.language ?: "en")}</dc:language>")
        b.series?.let { append("""<meta name="calibre:series" content="${esc(it)}"/>""") }
        b.seriesIndex?.let { append("""<meta name="calibre:series_index" content="$it"/>""") }
        if (b.coverPath != null) append("""<meta name="cover" content="cover-image"/>""")
        append("</metadata><manifest>")
        append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
        append("""<item id="css" href="style.css" media-type="text/css"/>""")
        b.styles.keys.forEachIndexed { i, p -> append("""<item id="css$i" href="${esc(p)}" media-type="text/css"/>""") }
        b.resources.keys.forEachIndexed { i, p ->
            val id = if (p == b.coverPath) "cover-image" else "r$i"
            append("""<item id="$id" href="${esc(p)}" media-type="${mediaType(p)}"/>""")
        }
        b.sections.indices.forEach { i -> append("""<item id="s${i + 1}" href="text/s${i + 1}.xhtml" media-type="application/xhtml+xml"/>""") }
        append("""</manifest><spine toc="ncx">""")
        b.sections.indices.forEach { i -> append("""<itemref idref="s${i + 1}"/>""") }
        append("</spine></package>")
    }

    private fun ncx(b: ConvBook): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="thor-reader-converted"/></head>""")
        append("<docTitle><text>${esc(b.title ?: "Untitled")}</text></docTitle><navMap>")
        var order = 0
        b.sections.forEachIndexed { i, s ->
            val t = s.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            order++
            append("""<navPoint id="n$order" playOrder="$order"><navLabel><text>${esc(t)}</text></navLabel><content src="text/s${i + 1}.xhtml"/></navPoint>""")
        }
        append("</navMap></ncx>")
    }
}

/**
 * Splits generated HTML into chapters at top-level headings (h1, or h2 if there is no h1), so
 * long documents become several chapters (faster first page, chapter list, running headers).
 * Leading content before the first heading becomes its own section.
 */
object HeadingSplitter {
    private val tagRe = Regex("<h([12])\\b[^>]*>(.*?)</h\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun split(book: ConvBook, html: String, fallbackTitle: String?) {
        val matches = tagRe.findAll(html).toList()
        val level = if (matches.any { it.groupValues[1] == "1" }) "1" else "2"
        val cuts = matches.filter { it.groupValues[1] == level }
        if (cuts.size < 2) {
            book.addSection(cuts.firstOrNull()?.let { plain(it.groupValues[2]) } ?: fallbackTitle, html)
            return
        }
        val head = html.substring(0, cuts[0].range.first)
        if (plain(head).isNotBlank() || head.contains("<img", ignoreCase = true)) book.addSection(fallbackTitle, head)
        cuts.forEachIndexed { i, m ->
            val end = if (i + 1 < cuts.size) cuts[i + 1].range.first else html.length
            book.addSection(plain(m.groupValues[2]), html.substring(m.range.first, end))
        }
    }

    /** Visible text of an HTML fragment. */
    fun plain(html: String): String =
        html.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()
}
