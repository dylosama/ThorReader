package dev.thorreader.app.importer

import android.util.Base64
import android.util.Xml
import dev.thorreader.app.io.SafFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder

/** Metadata gathered on import. Any field may stay empty; filename parsing fills gaps. */
class ExtractedMeta {
    var title: String? = null
    var authors = mutableListOf<String>()
    var series: String? = null
    var seriesNumber: Float? = null
    val genres = mutableListOf<String>()
    var pageCount: Int = 0
    var coverBytes: ByteArray? = null
    var coverBitmap: android.graphics.Bitmap? = null
    var mangaRtl: Boolean = false
}

private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    setInput(input, null)
}

private val XmlPullParser.local: String get() = name.substringAfter(':').lowercase()

private fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).substringAfter(':').equals(localName, ignoreCase = true)) return getAttributeValue(i)
    }
    return null
}

private fun String?.clean(): String? = this?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

/** EPUB: container.xml -> OPF -> Dublin Core + calibre:series / belongs-to-collection + cover. */
object EpubMeta {
    fun read(file: SafFile, m: ExtractedMeta) {
        ZipFile.builder().setSeekableByteChannel(file.newChannel()).get().use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return
            var opfPath: String? = null
            zip.getInputStream(container).use { s ->
                val p = newParser(s)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.local == "rootfile") {
                        opfPath = p.attr("full-path")
                        break
                    }
                }
            }
            val opfEntry = opfPath?.let { zip.getEntry(it) } ?: return
            val opfDir = opfPath!!.substringBeforeLast('/', "")

            val manifest = HashMap<String, String>()
            var coverId: String? = null
            var coverHref: String? = null
            var collectionId: String? = null
            var collectionName: String? = null
            val refines = HashMap<String, MutableMap<String, String>>()
            var calibreSeries: String? = null
            var calibreIndex: String? = null

            zip.getInputStream(opfEntry).use { s ->
                val p = newParser(s)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.local) {
                        "title" -> if (m.title == null) m.title = p.nextText().clean()
                        "creator" -> p.nextText().clean()?.let { m.authors += it }
                        "subject" -> p.nextText().clean()?.let { m.genres += it }
                        "meta" -> {
                            val name = p.attr("name")
                            val content = p.attr("content")
                            val property = p.attr("property")
                            when {
                                name == "calibre:series" -> calibreSeries = content.clean()
                                name == "calibre:series_index" -> calibreIndex = content
                                name == "cover" -> coverId = content
                                property == "belongs-to-collection" -> {
                                    collectionId = p.attr("id")
                                    collectionName = p.nextText().clean()
                                }
                                property != null && p.attr("refines") != null -> {
                                    val target = p.attr("refines")!!.removePrefix("#")
                                    refines.getOrPut(target) { HashMap() }[property] = p.nextText().trim()
                                }
                            }
                        }
                        "item" -> {
                            val id = p.attr("id")
                            val href = p.attr("href")
                            if (id != null && href != null) manifest[id] = href
                            if (p.attr("properties")?.split(' ')?.contains("cover-image") == true) coverHref = href
                        }
                    }
                }
            }

            if (calibreSeries != null) {
                m.series = calibreSeries
                m.seriesNumber = calibreIndex?.toFloatOrNull()
            } else if (collectionName != null) {
                val ref = collectionId?.let { refines[it] }
                val type = ref?.get("collection-type")
                if (type == null || type == "series") {
                    m.series = collectionName
                    m.seriesNumber = ref?.get("group-position")?.toFloatOrNull()
                }
            }

            val href = coverHref ?: coverId?.let { manifest[it] }
            if (href != null) {
                val path = resolve(opfDir, href)
                zip.getEntry(path)?.let { e -> m.coverBytes = zip.getInputStream(e).use { it.readBytes() } }
            }
        }
    }

    private fun resolve(base: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = ArrayList<String>()
        (if (base.isEmpty()) decoded else "$base/$decoded").split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += it
            }
        }
        return parts.joinToString("/")
    }
}

/** FB2: <title-info> (book-title, author, genre, sequence, coverpage) + the cover <binary>. */
object Fb2Meta {
    fun read(file: SafFile, m: ExtractedMeta) {
        val bytes = file.readAll()
        val p = newParser(ByteArrayInputStream(bytes))
        var inTitleInfo = false
        var inAuthor = false
        var first = ""
        var middle = ""
        var last = ""
        var nick = ""
        var coverId: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "title-info" -> inTitleInfo = true
                    "book-title" -> if (inTitleInfo && m.title == null) m.title = p.nextText().clean()
                    "genre" -> if (inTitleInfo) p.nextText().clean()?.let { m.genres += it }
                    "author" -> if (inTitleInfo) {
                        inAuthor = true; first = ""; middle = ""; last = ""; nick = ""
                    }
                    "first-name" -> if (inAuthor) first = p.nextText().trim()
                    "middle-name" -> if (inAuthor) middle = p.nextText().trim()
                    "last-name" -> if (inAuthor) last = p.nextText().trim()
                    "nickname" -> if (inAuthor) nick = p.nextText().trim()
                    "sequence" -> if (inTitleInfo && m.series == null) {
                        m.series = p.attr("name").clean()
                        m.seriesNumber = p.attr("number")?.toFloatOrNull()
                    }
                    "image" -> if (inTitleInfo && coverId == null) coverId = p.attr("href")?.removePrefix("#")
                    "binary" -> if (coverId != null && p.attr("id") == coverId) {
                        m.coverBytes = runCatching { Base64.decode(p.nextText(), Base64.DEFAULT) }.getOrNull()
                        return
                    }
                }
                XmlPullParser.END_TAG -> when (p.local) {
                    "title-info" -> inTitleInfo = false
                    "author" -> if (inAuthor) {
                        inAuthor = false
                        listOf(first, middle, last).filter { it.isNotEmpty() }.joinToString(" ")
                            .ifEmpty { nick }.clean()?.let { m.authors += it }
                    }
                }
            }
        }
    }
}

/** ComicInfo.xml (ComicRack format). */
object ComicInfo {
    fun read(bytes: ByteArray, m: ExtractedMeta) {
        val p = newParser(ByteArrayInputStream(bytes))
        var number: String? = null
        var volume: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.local) {
                "title" -> p.nextText().clean()?.let { m.title = it }
                "series" -> p.nextText().clean()?.let { m.series = it }
                "number" -> number = p.nextText().trim()
                "volume" -> volume = p.nextText().trim()
                "writer" -> p.nextText().clean()?.let { w -> m.authors += w.split(',').mapNotNull { it.clean() } }
                "genre" -> p.nextText().clean()?.let { g -> m.genres += g.split(',').mapNotNull { it.clean() } }
                "manga" -> m.mangaRtl = p.nextText().trim().equals("YesAndRightToLeft", ignoreCase = true)
            }
        }
        m.seriesNumber = number?.toFloatOrNull() ?: volume?.toFloatOrNull()
        // Comics often have an issue title only; make it readable on its own.
        if (m.series != null && m.title == null) {
            m.title = m.series + (m.seriesNumber?.let { " #" + formatNumber(it) } ?: "")
        }
    }
}

fun formatNumber(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

/** Fallback: guess title / author / series / number from the file name. */
object FilenameParser {
    class Parsed(val title: String, val author: String?, val series: String?, val number: Float?)

    private val bracketed = Regex("""\s*[\(\[\{][^)\]\}]*[\)\]\}]""")
    private val seriesNumber = Regex("""^(.*?)[\s._-]*(?:#|vol\.?\s*|volume\s*|v|t|tome\s*|ch\.?\s*|chapter\s*)?(\d{1,4}(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)

    fun parse(fileName: String, comic: Boolean): Parsed {
        val base = fileName.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val stripped = base.replace(bracketed, "").trim().ifEmpty { base }

        var author: String? = null
        var rest = stripped
        val dash = stripped.indexOf(" - ")
        if (!comic && dash > 0) {
            val left = stripped.substring(0, dash).trim()
            val right = stripped.substring(dash + 3).trim()
            if (left.none { it.isDigit() } && right.isNotEmpty()) {
                author = left
                rest = right
            }
        }
        var series: String? = null
        var number: Float? = null
        seriesNumber.find(rest)?.let { mr ->
            val name = mr.groupValues[1].trim().trimEnd('-', ',', '.').trim()
            if (name.isNotEmpty()) {
                series = name
                number = mr.groupValues[2].toFloatOrNull()
            }
        }
        return Parsed(title = rest, author = author, series = series, number = number)
    }
}
