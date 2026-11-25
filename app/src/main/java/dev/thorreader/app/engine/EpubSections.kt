package dev.thorreader.app.engine

import android.util.Xml
import dev.thorreader.app.io.SafFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.net.URLDecoder

/**
 * The EPUB's spine (its sections in reading order) and the heading at the start of each
 * section, read straight from the book's HTML. Used for running headers when the table of
 * contents doesn't name the current chapter.
 */
class EpubSections(file: SafFile) : Closeable {
    private val zip: ZipFile = ZipFile.builder().setSeekableByteChannel(file.newChannel()).get()

    /** Zip paths of the spine items, in reading order. */
    val spine: List<String> = runCatching { readSpine() }.getOrDefault(emptyList())

    private val headings = HashMap<Int, String?>()

    /**
     * Heading (h1-h3) at the start of spine item [index], or null. Read once per section:
     * "no heading" is remembered too. (getOrPut treats a stored null as missing, which made
     * every page of a heading-less chapter re-read and re-scan that chapter's whole HTML:
     * 2.8 s before Dune's first page.)
     */
    fun heading(index: Int): String? {
        if (headings.containsKey(index)) return headings[index]
        val h = run {
            val path = spine.getOrNull(index) ?: return@run null
            val entry = zip.getEntry(path) ?: return@run null
            runCatching {
                val html = zip.getInputStream(entry).use { String(dev.thorreader.app.io.Limits.readCapped(it, dev.thorreader.app.io.Limits.MAX_XML, "a chapter"), Charsets.UTF_8) }
                HtmlHeading.find(html)
            }.getOrNull()
        }
        headings[index] = h
        return h
    }

    private fun readSpine(): List<String> {
        val container = zip.getEntry("META-INF/container.xml") ?: return emptyList()
        var opfPath: String? = null
        zip.getInputStream(container).use { s ->
            val p = parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == "rootfile") {
                    opfPath = p.getAttributeValue(null, "full-path")
                    break
                }
            }
        }
        val opf = opfPath?.let { zip.getEntry(it) } ?: return emptyList()
        val dir = opfPath!!.substringBeforeLast('/', "")
        val manifest = HashMap<String, String>()
        val order = ArrayList<String>()
        zip.getInputStream(opf).use { s ->
            val p = parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name.substringAfter(':')) {
                    "item" -> {
                        val id = p.getAttributeValue(null, "id")
                        val href = p.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = href
                    }
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { order += it }
                }
            }
        }
        return order.mapNotNull { id -> manifest[id]?.let { resolve(dir, it) } }
    }

    private fun parser(s: java.io.InputStream) = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // No DOCTYPE processing and the DOCTYPE itself removed: no external entities (XXE),
        // no entity expansion bombs. Input is size-capped.
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(java.io.ByteArrayInputStream(dev.thorreader.app.io.SafeXml.read(s)), null)
    }

    private fun resolve(base: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = ArrayList<String>()
        (if (base.isEmpty()) decoded else "$base/$decoded").substringBefore('#').split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += it
            }
        }
        return parts.joinToString("/")
    }

    override fun close() = zip.close()
}

/** Finds the heading at the start of an HTML section. Pure, so it can be unit-tested. */
object HtmlHeading {
    private val heading = Regex("""<h([1-3])\b[^>]*>(.*?)</h\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tags = Regex("""<[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private val scripts = Regex("""<(script|style)\b.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Max visible characters allowed before the heading for it to count as "at the start". */
    private const val MAX_TEXT_BEFORE = 400

    fun find(html: String): String? {
        val bodyAt = Regex("<body\\b", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: 0
        val body = html.substring(bodyAt).replace(scripts, "")
        val first = heading.find(body) ?: return null
        if (visible(body.substring(0, first.range.first)).length > MAX_TEXT_BEFORE) return null
        var text = visible(first.groupValues[2])
        // "Chapter 3" immediately followed by a second heading "The Humming Ridge": join them.
        val second = heading.find(body, first.range.last + 1)
        if (second != null && visible(body.substring(first.range.last + 1, second.range.first)).isEmpty()) {
            val sub = visible(second.groupValues[2])
            if (sub.isNotEmpty() && text.length <= 20) text = "$text: $sub"
        }
        return text.takeIf { it.isNotEmpty() }
    }

    private fun visible(html: String): String =
        unescape(html.replace(Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE), " ").replace(tags, " "))
            .replace(Regex("\\s+"), " ").trim()

    private fun unescape(s: String): String {
        var t = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#160;", " ")
        t = Regex("&#(x?)([0-9a-fA-F]+);").replace(t) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        return t
    }
}
