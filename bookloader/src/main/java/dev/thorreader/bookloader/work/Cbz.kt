package dev.thorreader.bookloader.work

import dev.thorreader.bookloader.source.BookMeta
import dev.thorreader.bookloader.source.ContentType
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs downloaded pages into a valid CBZ: pages "001.jpg", "002.png"... in reading order, plus
 * ComicInfo.xml (the ComicRack schema Thor Reader and other readers understand).
 */
object Cbz {

    fun write(out: File, pages: List<File>, comicInfo: String) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION) // pictures are already compressed
            val width = maxOf(3, pages.size.toString().length)
            pages.forEachIndexed { i, f ->
                val ext = f.name.substringAfterLast('.', "jpg").lowercase().takeIf { it.length in 3..4 && it.all(Char::isLetterOrDigit) } ?: "jpg"
                z.putNextEntry(ZipEntry("${(i + 1).toString().padStart(width, '0')}.$ext"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("ComicInfo.xml"))
            z.write(comicInfo.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }

    /** ComicInfo.xml for [meta]; [web] and [translator] are attribution (source page, scanlation group). */
    fun comicInfo(meta: BookMeta, pageCount: Int, summary: String? = null, web: String? = null, translator: String? = null, language: String? = null, volume: String? = null): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n")
        fun tag(name: String, value: String?) {
            if (!value.isNullOrBlank()) sb.append("  <").append(name).append('>').append(esc(value)).append("</").append(name).append(">\n")
        }
        tag("Title", meta.title)
        tag("Series", meta.series)
        tag("Number", meta.seriesNumber?.let { if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString() })
        tag("Volume", volume)
        tag("Summary", summary)
        tag("Writer", meta.authors.joinToString(", ").ifEmpty { null })
        tag("Translator", translator)
        tag("Genre", meta.genres.joinToString(", ").ifEmpty { null })
        tag("Web", web)
        tag("PageCount", pageCount.toString())
        tag("LanguageISO", language)
        tag(
            "Manga",
            when {
                meta.rtl == true -> "YesAndRightToLeft"
                meta.type == ContentType.MANGA -> "Yes"
                else -> "No"
            },
        )
        sb.append("</ComicInfo>\n")
        return sb.toString()
    }

    fun esc(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c >= ' ' || c == '\n' || c == '\t') append(c)
            }
        }
    }
}
