package dev.thorreader.app.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class BookFormat(val label: String, val extensions: List<String>, val reflowable: Boolean, val comic: Boolean = false) {
    EPUB("EPUB", listOf("epub"), true),
    PDF("PDF", listOf("pdf"), false),
    MOBI("MOBI", listOf("mobi", "prc"), true),
    AZW("AZW", listOf("azw"), true),
    AZW3("AZW3", listOf("azw3", "kf8"), true),
    FB2("FB2", listOf("fb2"), true),
    XPS("XPS", listOf("xps", "oxps"), false),
    TXT("TXT", listOf("txt"), true),
    HTML("HTML", listOf("html", "htm", "xhtml", "xht"), true),
    MARKDOWN("Markdown", listOf("md", "markdown"), true),
    DOCX("DOCX", listOf("docx"), true),
    ODT("ODT", listOf("odt"), true),
    RTF("RTF", listOf("rtf"), true),
    CBZ("CBZ", listOf("cbz"), false, comic = true),
    CBR("CBR", listOf("cbr"), false, comic = true),
    CB7("CB7", listOf("cb7"), false, comic = true),
    CBT("CBT", listOf("cbt"), false, comic = true),
    /** One picture file = a one-page comic. */
    IMAGE("Image", listOf("jpg", "jpeg", "png", "webp", "gif", "avif"), false, comic = true),
    /** A folder of pictures (from "Add folder") = one comic. No file extension. */
    IMAGES("Images", emptyList(), false, comic = true);

    companion object {
        fun fromFileName(name: String): BookFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return entries.firstOrNull { ext in it.extensions }
        }

        /** Everything the importer accepts, for the empty-library hint and README. */
        val summary = "EPUB, MOBI, AZW, AZW3, FB2, TXT, HTML, Markdown, DOCX, ODT, RTF, PDF, XPS, CBZ, CBR, CB7, CBT, images"
    }
}

/** Plain text -> simple HTML that MuPDF lays out like any reflowable book. */
object TextToHtml {
    fun convert(bytes: ByteArray): ByteArray {
        var text = decode(bytes).removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = if (text.contains("\n\n")) {
            text.split(Regex("\n\\s*\n")).map { it.replace('\n', ' ') }
        } else {
            text.split('\n')
        }
        val sb = StringBuilder(text.length + paragraphs.size * 8 + 200)
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title></title></head><body>")
        for (p in paragraphs) {
            val t = p.trim()
            if (t.isEmpty()) continue
            sb.append("<p>")
            for (c in t) when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                else -> sb.append(c)
            }
            sb.append("</p>\n")
        }
        sb.append("</body></html>")
        text = ""
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
}
