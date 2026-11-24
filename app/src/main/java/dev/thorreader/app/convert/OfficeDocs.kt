package dev.thorreader.app.convert

import android.util.Xml
import dev.thorreader.app.io.SafFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream

private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    setInput(input, null)
}

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
    return null
}

private fun ZipFile.bytes(path: String): ByteArray? = getEntry(path)?.let { e -> getInputStream(e).use { it.readBytes() } }

/** Normalises "word/../media/x.png" style paths inside a zip. */
private fun zipPath(base: String, target: String): String {
    val parts = ArrayList<String>()
    val full = if (target.startsWith("/")) target.drop(1) else if (base.isEmpty()) target else "$base/$target"
    full.split('/').forEach {
        when (it) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += it
        }
    }
    return parts.joinToString("/")
}

/** Inline text formatting while walking a document. */
private class Runs {
    var bold = false
    var italic = false
    var underline = false
    var sup = false
    var sub = false

    fun wrap(text: String): String {
        if (text.isEmpty()) return ""
        var t = esc(text)
        if (sup) t = "<sup>$t</sup>"
        if (sub) t = "<sub>$t</sub>"
        if (underline) t = "<u>$t</u>"
        if (italic) t = "<i>$t</i>"
        if (bold) t = "<b>$t</b>"
        return t
    }

    fun reset() {
        bold = false; italic = false; underline = false; sup = false; sub = false
    }
}

/**
 * Word documents (.docx): paragraphs, headings (Heading 1-6 / Title styles or outline level),
 * bold/italic/underline, lists, tables, line breaks and pictures. Used only when MuPDF can't
 * open the file itself.
 */
object DocxConverter {
    fun convert(file: SafFile): ConvBook {
        val book = ConvBook()
        ZipFile.builder().setSeekableByteChannel(file.newChannel()).get().use { zip ->
            val doc = zip.bytes("word/document.xml") ?: throw IOException("Not a Word document")
            // Pictures: relationship id -> file in the zip.
            val rels = HashMap<String, String>()
            zip.bytes("word/_rels/document.xml.rels")?.let { b ->
                val p = parser(b.inputStream())
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name.endsWith("Relationship")) {
                        val id = p.attr("Id") ?: continue
                        val target = p.attr("Target") ?: continue
                        if (p.attr("TargetMode") != "External") rels[id] = zipPath("word", target)
                    }
                }
            }
            // Heading level of each paragraph style.
            val headingOf = HashMap<String, Int>()
            zip.bytes("word/styles.xml")?.let { b ->
                val p = parser(b.inputStream())
                var id: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "w:style" -> id = p.attr("w:styleId")
                        "w:name" -> id?.let { sid -> levelFromName(p.attr("w:val") ?: "")?.let { headingOf[sid] = it } }
                        "w:outlineLvl" -> id?.let { sid -> p.attr("w:val")?.toIntOrNull()?.let { if (it in 0..5) headingOf.putIfAbsent(sid, it + 1) } }
                    }
                }
            }
            readCore(zip, book)
            val images = HashMap<String, String>()
            fun image(rid: String): String? = images.getOrPut(rid) {
                rels[rid]?.let { path -> zip.bytes(path)?.takeIf { imageExt(it) != null }?.let { book.addImage(it, path) } } ?: ""
            }.ifEmpty { null }

            val html = StringBuilder()
            val para = StringBuilder()
            val runs = Runs()
            var level = 0
            var listItem = false
            var inList = false
            var inPPr = false
            var inRPr = false
            var inText = false
            val p = parser(doc.inputStream())
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "w:p" -> {
                            para.setLength(0); level = 0; listItem = false
                        }
                        "w:pPr" -> inPPr = true
                        "w:pStyle" -> if (inPPr) level = levelFromStyle(p.attr("w:val"), headingOf)
                        "w:outlineLvl" -> if (inPPr) p.attr("w:val")?.toIntOrNull()?.let { if (it in 0..5) level = it + 1 }
                        "w:numPr" -> if (inPPr) listItem = true
                        "w:r" -> runs.reset()
                        "w:rPr" -> inRPr = true
                        "w:b" -> if (inRPr && !inPPr) runs.bold = on(p)
                        "w:i" -> if (inRPr && !inPPr) runs.italic = on(p)
                        "w:u" -> if (inRPr && !inPPr) runs.underline = p.attr("w:val") != "none" && on(p)
                        "w:vertAlign" -> if (inRPr && !inPPr) {
                            runs.sup = p.attr("w:val") == "superscript"
                            runs.sub = p.attr("w:val") == "subscript"
                        }
                        "w:t" -> inText = true
                        "w:tab" -> if (!inPPr) para.append(" &#160; ")
                        "w:br", "w:cr" -> if (p.attr("w:type") != "page") para.append("<br/>")
                        "a:blip" -> p.attr("r:embed")?.let(::image)?.let { para.append("""<img src="../$it" alt=""/>""") }
                        "v:imagedata" -> p.attr("r:id")?.let(::image)?.let { para.append("""<img src="../$it" alt=""/>""") }
                        "w:tbl" -> {
                            if (inList) { html.append("</ul>"); inList = false }
                            html.append("<table>")
                        }
                        "w:tr" -> html.append("<tr>")
                        "w:tc" -> html.append("<td>")
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "w:pPr" -> inPPr = false
                        "w:rPr" -> inRPr = false
                        "w:t" -> inText = false
                        "w:p" -> {
                            val content = para.toString()
                            if (listItem && level == 0) {
                                if (!inList) { html.append("<ul>"); inList = true }
                                html.append("<li>").append(content).append("</li>")
                            } else {
                                if (inList) { html.append("</ul>"); inList = false }
                                when {
                                    level in 1..6 -> if (content.isNotBlank()) html.append("<h$level>").append(content).append("</h$level>\n")
                                    content.isBlank() -> html.append("<p>&#160;</p>\n")
                                    else -> html.append("<p>").append(content).append("</p>\n")
                                }
                            }
                        }
                        "w:tc" -> {
                            if (inList) { html.append("</ul>"); inList = false }
                            html.append("</td>")
                        }
                        "w:tr" -> html.append("</tr>")
                        "w:tbl" -> html.append("</table>\n")
                    }
                    XmlPullParser.TEXT -> if (inText) para.append(runs.wrap(p.text))
                }
            }
            if (inList) html.append("</ul>")
            HeadingSplitter.split(book, html.toString(), book.title)
        }
        return book
    }

    private fun on(p: XmlPullParser): Boolean = p.attr("w:val")?.lowercase() !in setOf("0", "false", "none", "off")

    private fun levelFromName(name: String): Int? {
        val n = name.trim().lowercase()
        if (n == "title") return 1
        return Regex("^heading\\s*([1-6])$").find(n)?.groupValues?.get(1)?.toInt()
    }

    private fun levelFromStyle(id: String?, map: Map<String, Int>): Int {
        id ?: return 0
        return map[id] ?: levelFromName(id) ?: Regex("^Heading([1-6])$", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.toInt() ?: 0
    }

    private fun readCore(zip: ZipFile, book: ConvBook) {
        val b = zip.bytes("docProps/core.xml") ?: return
        val p = parser(b.inputStream())
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "dc:title" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { book.title = it }
                "dc:creator" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { book.authors += it }
                "dc:subject", "cp:keywords" -> p.nextText().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { book.subjects += it }
                "dc:language" -> book.language = p.nextText().trim().ifEmpty { null }
            }
        }
    }
}

/**
 * OpenDocument text (.odt, LibreOffice): headings, paragraphs, bold/italic/underline from the
 * automatic styles, lists, tables, line breaks, spaces, tabs and pictures.
 */
object OdtConverter {
    private class Style(var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var parent: String? = null)

    fun convert(file: SafFile): ConvBook {
        val book = ConvBook()
        ZipFile.builder().setSeekableByteChannel(file.newChannel()).get().use { zip ->
            val content = zip.bytes("content.xml") ?: throw IOException("Not an OpenDocument text file")
            readMeta(zip, book)
            val styles = HashMap<String, Style>()
            zip.bytes("styles.xml")?.let { readStyles(it, styles) }
            readStyles(content, styles)

            fun resolve(name: String?): Style {
                val out = Style()
                var n = name
                var guard = 0
                while (n != null && guard++ < 10) {
                    val s = styles[n] ?: break
                    out.bold = out.bold || s.bold
                    out.italic = out.italic || s.italic
                    out.underline = out.underline || s.underline
                    n = s.parent
                }
                return out
            }

            val images = HashMap<String, String>()
            val html = StringBuilder()
            // Open inline formats (one entry per open text:span; false = nothing opened).
            val spans = ArrayList<String>()
            var inBody = false
            var skip = 0 // inside notes, annotations, tracked-change history
            val blockStack = ArrayList<String>()
            val p = parser(content.inputStream())
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = p.name
                        if (name == "office:text") inBody = true
                        if (!inBody) continue
                        if (skip > 0 || name in SKIPPED) {
                            skip++
                            continue
                        }
                        when (name) {
                            "text:h" -> {
                                val lvl = (p.attr("text:outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6)
                                html.append("<h$lvl>"); blockStack += "</h$lvl>\n"
                            }
                            "text:p" -> {
                                val st = resolve(p.attr("text:style-name"))
                                var open = "<p>"
                                var close = "</p>\n"
                                if (st.bold) { open += "<b>"; close = "</b>$close" }
                                if (st.italic) { open += "<i>"; close = "</i>$close" }
                                html.append(open); blockStack += close
                            }
                            "text:list" -> { html.append("<ul>"); blockStack += "</ul>\n" }
                            "text:list-item", "text:list-header" -> { html.append("<li>"); blockStack += "</li>" }
                            "table:table" -> { html.append("<table>"); blockStack += "</table>\n" }
                            "table:table-row" -> { html.append("<tr>"); blockStack += "</tr>" }
                            "table:table-cell", "table:covered-table-cell" -> { html.append("<td>"); blockStack += "</td>" }
                            "text:span" -> {
                                val st = resolve(p.attr("text:style-name"))
                                var open = ""
                                var close = ""
                                if (st.bold) { open += "<b>"; close = "</b>$close" }
                                if (st.italic) { open += "<i>"; close = "</i>$close" }
                                if (st.underline) { open += "<u>"; close = "</u>$close" }
                                html.append(open); spans += close
                            }
                            "text:line-break" -> html.append("<br/>")
                            "text:tab" -> html.append(" &#160; ")
                            "text:s" -> repeat((p.attr("text:c")?.toIntOrNull() ?: 1).coerceAtMost(40)) { html.append("&#160;") }
                            "draw:image" -> p.attr("xlink:href")?.takeIf { !it.startsWith("http") }?.let { href ->
                                val path = images.getOrPut(href) {
                                    zip.bytes(zipPath("", href))?.takeIf { imageExt(it) != null }?.let { book.addImage(it, href) } ?: ""
                                }
                                if (path.isNotEmpty()) html.append("""<img src="../$path" alt=""/>""")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = p.name
                        if (name == "office:text") inBody = false
                        if (!inBody) continue
                        if (skip > 0) {
                            skip--
                            continue
                        }
                        when (name) {
                            "text:h", "text:p", "text:list", "text:list-item", "text:list-header",
                            "table:table", "table:table-row", "table:table-cell", "table:covered-table-cell",
                            -> if (blockStack.isNotEmpty()) html.append(blockStack.removeAt(blockStack.size - 1))
                            "text:span" -> if (spans.isNotEmpty()) html.append(spans.removeAt(spans.size - 1))
                        }
                    }
                    XmlPullParser.TEXT -> if (inBody && skip == 0) html.append(esc(p.text))
                }
            }
            HeadingSplitter.split(book, html.toString(), book.title)
        }
        return book
    }

    private val SKIPPED = setOf("text:note", "office:annotation", "text:tracked-changes", "text:sequence-decls", "office:forms")

    private fun readStyles(bytes: ByteArray, out: MutableMap<String, Style>) {
        val p = parser(bytes.inputStream())
        var current: Style? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "style:style" -> {
                    val name = p.attr("style:name")
                    current = Style(parent = p.attr("style:parent-style-name"))
                    if (name != null) out[name] = current
                }
                "style:text-properties" -> current?.let { s ->
                    if (p.attr("fo:font-weight") == "bold") s.bold = true
                    if (p.attr("fo:font-style") == "italic") s.italic = true
                    p.attr("style:text-underline-style")?.let { if (it != "none") s.underline = true }
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "style:style") {
                current = null
            }
        }
    }

    private fun readMeta(zip: ZipFile, book: ConvBook) {
        val b = zip.bytes("meta.xml") ?: return
        val p = parser(b.inputStream())
        var initial: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "dc:title" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { book.title = it }
                "dc:creator" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { book.authors += it }
                "meta:initial-creator" -> initial = p.nextText().trim().ifEmpty { null }
                "dc:subject", "meta:keyword" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { book.subjects += it }
                "dc:language" -> book.language = p.nextText().trim().ifEmpty { null }
            }
        }
        if (book.authors.isEmpty()) initial?.let { book.authors += it }
    }
}
