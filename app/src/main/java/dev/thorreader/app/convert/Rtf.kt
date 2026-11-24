package dev.thorreader.app.convert

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Rich Text Format (.rtf) -> HTML: paragraphs, headings (outline level or "heading N" styles),
 * bold/italic/underline, line breaks, Unicode and code-page characters, PNG/JPEG pictures and
 * the title/author from the info block. Everything else (fonts, colours, page setup) is ignored.
 */
object RtfConverter {

    private class State(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        /** Inside a group whose text is not shown (font table, header, field instruction...). */
        var skip: Boolean = false,
        /** Inside \info\title / \info\author / a style sheet entry / a picture. */
        var capture: String? = null,
        var uc: Int = 1,
    ) {
        fun copy() = State(bold, italic, underline, skip, capture, uc)
    }

    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr",
        "footerf", "footnote", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnsl", "themedata",
        "colorschememapping", "datastore", "latentstyles", "pgdsctbl", "fldinst", "revtbl", "mmathPr",
        "filetbl", "operator", "company", "doccomm", "keywords", "subject", "category", "comment", "nonshppict",
        "bkmkstart", "bkmkend", "object", "objdata", "shpinst", "sp", "annotation", "atnid", "atnauthor",
    )

    fun convert(bytes: ByteArray): ConvBook {
        val book = ConvBook()
        val src = String(bytes, Charsets.ISO_8859_1)
        var cp: Charset = Charset.forName("windows-1252")
        val html = StringBuilder()
        val para = StringBuilder()
        var level = 0
        val styleLevel = HashMap<Int, Int>()
        var styleNo = -1
        val styleName = StringBuilder()
        val captured = HashMap<String, StringBuilder>()
        val pict = StringBuilder()
        var pictType: String? = null

        val stack = ArrayList<State>()
        var st = State()
        var pendingSkip = 0
        // Text run with its formatting, flushed into [para] when the formatting changes.
        val run = StringBuilder()
        var runFmt = Triple(false, false, false)

        fun flushRun() {
            if (run.isEmpty()) return
            var t = esc(run.toString())
            if (runFmt.third) t = "<u>$t</u>"
            if (runFmt.second) t = "<i>$t</i>"
            if (runFmt.first) t = "<b>$t</b>"
            para.append(t)
            run.setLength(0)
        }

        fun text(s: String) {
            if (st.skip) return
            when (val c = st.capture) {
                null -> {
                    val f = Triple(st.bold, st.italic, st.underline)
                    if (f != runFmt) {
                        flushRun()
                        runFmt = f
                    }
                    run.append(s)
                }
                "stylesheet" -> styleName.append(s)
                "pict" -> Unit
                else -> captured.getOrPut(c) { StringBuilder() }.append(s)
            }
        }

        fun endParagraph() {
            flushRun()
            val content = para.toString()
            para.setLength(0)
            if (level in 1..6 && content.isNotBlank()) html.append("<h$level>").append(content).append("</h$level>\n")
            else if (content.isNotBlank()) html.append("<p>").append(content).append("</p>\n")
            else html.append("<p>&#160;</p>\n")
        }

        val hex = ByteArrayOutputStream()
        fun flushHex() {
            if (hex.size() == 0) return
            text(String(hex.toByteArray(), cp))
            hex.reset()
        }

        var i = 0
        var ignorableNext = false
        while (i < src.length) {
            val c = src[i]
            if (c != '\\' || i + 1 >= src.length || src[i + 1] != '\'') flushHex()
            when (c) {
                '{' -> {
                    stack += st.copy()
                    i++
                }
                '}' -> {
                    // Leaving a picture group: store the picture.
                    if (st.capture == "pict" && !st.skip && stack.lastOrNull()?.capture != "pict") {
                        val type = pictType
                        if (type != null && pict.isNotEmpty()) {
                            val data = hexToBytes(pict)
                            if (imageExt(data) != null) {
                                flushRun()
                                para.append("""<img src="../${book.addImage(data, "p.$type")}" alt=""/>""")
                            }
                        }
                        pict.setLength(0)
                        pictType = null
                    }
                    if (st.capture == "stylesheet") {
                        if (styleNo >= 0) headingLevel(styleName.toString())?.let { styleLevel[styleNo] = it }
                        styleNo = -1
                        styleName.setLength(0)
                    }
                    st = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else State()
                    i++
                }
                '\\' -> {
                    if (i + 1 >= src.length) break
                    val n = src[i + 1]
                    when {
                        n == '\'' -> {
                            val b = src.substring(i + 2, (i + 4).coerceAtMost(src.length)).toIntOrNull(16)
                            i += 4
                            if (pendingSkip > 0) pendingSkip-- else if (b != null) hex.write(b)
                        }
                        n == '\\' || n == '{' || n == '}' -> {
                            if (pendingSkip > 0) pendingSkip-- else text(n.toString())
                            i += 2
                        }
                        n == '~' -> { text(" "); i += 2 }
                        n == '_' -> { text("‑"); i += 2 }
                        n == '-' -> i += 2
                        n == '*' -> { ignorableNext = true; i += 2 }
                        n == '\n' || n == '\r' -> { endParagraph(); i += 2 }
                        n.isLetter() -> {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var k = j
                            if (k < src.length && (src[k] == '-' || src[k].isDigit())) {
                                k++
                                while (k < src.length && src[k].isDigit()) k++
                            }
                            val param = if (k > j) src.substring(j, k).toIntOrNull() else null
                            if (k < src.length && src[k] == ' ') k++
                            i = k
                            val ignorable = ignorableNext
                            ignorableNext = false
                            when (word) {
                                "par", "sect", "page" -> if (!st.skip && st.capture == null) endParagraph()
                                "line" -> if (!st.skip && st.capture == null) { flushRun(); para.append("<br/>") }
                                "tab" -> text(" ")
                                "emdash" -> text("—")
                                "endash" -> text("–")
                                "lquote" -> text("‘")
                                "rquote" -> text("’")
                                "ldblquote" -> text("“")
                                "rdblquote" -> text("”")
                                "bullet" -> text("•")
                                "b" -> st.bold = param != 0
                                "i" -> st.italic = param != 0
                                "ul" -> st.underline = param != 0
                                "ulnone" -> st.underline = false
                                "plain" -> { st.bold = false; st.italic = false; st.underline = false }
                                "pard" -> level = 0
                                "outlinelevel" -> if (param != null && param in 0..5) level = param + 1
                                "s" -> if (st.capture == "stylesheet") styleNo = param ?: -1 else styleLevel[param ?: -1]?.let { level = it }
                                "ansicpg" -> param?.let { p -> runCatching { cp = Charset.forName("windows-$p") }.recoverCatching { cp = Charset.forName("cp$p") } }
                                "uc" -> st.uc = param ?: 1
                                "u" -> if (param != null) {
                                    val ch = if (param < 0) param + 65536 else param
                                    text(String(Character.toChars(ch)))
                                    pendingSkip = st.uc
                                }
                                "info" -> st.skip = false.also { st.capture = "info" }
                                "title", "author" -> if (st.capture == "info" || stack.any { it.capture == "info" }) st.capture = word
                                "stylesheet" -> st.capture = "stylesheet"
                                "pict" -> st.capture = "pict"
                                "pngblip" -> if (st.capture == "pict") pictType = "png"
                                "jpegblip" -> if (st.capture == "pict") pictType = "jpg"
                                "fldrslt" -> st.skip = false
                                else -> if (word in SKIP_DESTINATIONS || (ignorable && word !in setOf("pict", "shppict"))) st.skip = true
                            }
                        }
                        else -> i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (st.capture == "pict") {
                        if (c.isLetterOrDigit()) pict.append(c)
                    } else if (pendingSkip > 0) {
                        pendingSkip--
                    } else {
                        // Gather a run of plain characters at once.
                        var j = i
                        while (j < src.length && src[j] !in "\\{}\r\n") j++
                        text(src.substring(i, j))
                        i = j
                        continue
                    }
                    i++
                }
            }
        }
        flushHex()
        endParagraph()
        // Info block (skipped as text): title and author.
        captured["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { book.title = it }
        captured["author"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { book.authors += it }
        HeadingSplitter.split(book, html.toString(), book.title)
        return book
    }

    private fun headingLevel(name: String): Int? {
        val n = name.trim().trimEnd(';').trim().lowercase()
        if (n == "title") return 1
        return Regex("^heading\\s*([1-6])").find(n)?.groupValues?.get(1)?.toInt()
    }

    private fun hexToBytes(s: CharSequence): ByteArray {
        val out = ByteArrayOutputStream(s.length / 2)
        var k = 0
        while (k + 1 < s.length) {
            val hi = Character.digit(s[k], 16)
            val lo = Character.digit(s[k + 1], 16)
            if (hi < 0 || lo < 0) { k++; continue }
            out.write(hi * 16 + lo)
            k += 2
        }
        return out.toByteArray()
    }
}
