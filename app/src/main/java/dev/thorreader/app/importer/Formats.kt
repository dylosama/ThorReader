package dev.thorreader.app.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class BookFormat(val label: String, val extensions: List<String>, val reflowable: Boolean, val comic: Boolean = false) {
    EPUB("EPUB", listOf("epub"), true),
    PDF("PDF", listOf("pdf"), false),
    MOBI("MOBI", listOf("mobi", "prc", "azw"), true),
    FB2("FB2", listOf("fb2"), true),
    XPS("XPS", listOf("xps", "oxps"), false),
    TXT("TXT", listOf("txt"), true),
    CBZ("CBZ", listOf("cbz"), false, comic = true),
    CBR("CBR", listOf("cbr"), false, comic = true),
    CB7("CB7", listOf("cb7"), false, comic = true);

    companion object {
        fun fromFileName(name: String): BookFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
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
