package dev.thorreader.app.engine

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OPDS 1.x catalogs (Atom feeds): Calibre's content server, Calibre-Web, Kavita, Komga, Project
 * Gutenberg, Standard Ebooks… Pure parsing (javax.xml, also on the JVM), so it is unit-tested.
 * A feed is either navigation (rows leading to other feeds) or acquisition (books with download
 * links), often mixed; pages continue via rel="next"; search via OpenSearch.
 */
object Opds {
    data class Link(val href: String, val rel: String?, val type: String?, val title: String?)

    data class Entry(
        val id: String,
        val title: String,
        val authors: List<String>,
        val summary: String?,
        val links: List<Link>,
    ) {
        /** Where this row leads, if it is a folder (another feed). */
        val navigation: Link?
            get() = links.firstOrNull { l -> isFeed(l.type) && (l.rel == null || l.rel == "subsection" || l.rel.startsWith("http://opds-spec.org/sort")) }
                ?: links.firstOrNull { l ->
                    isFeed(l.type) && l.rel !in setOf("self", "search", "up", "start") &&
                        l.rel?.startsWith("http://opds-spec.org/acquisition") != true
                }

        /** Downloadable files (no buying / borrowing, no DRM). */
        val downloads: List<Link>
            get() = links.filter { l ->
                val rel = l.rel ?: return@filter false
                rel.startsWith("http://opds-spec.org/acquisition") &&
                    !rel.endsWith("/buy") && !rel.endsWith("/borrow") && !rel.endsWith("/subscribe") && !rel.endsWith("/sample") &&
                    !isDrm(l.type) && formatRank(l) < 9 // a book file the reader opens (not audio, not a web page)
            }

        val thumbnail: String?
            get() = (links.firstOrNull { it.rel == "http://opds-spec.org/image/thumbnail" || it.rel == "http://opds-spec.org/thumbnail" }
                ?: links.firstOrNull { it.rel == "http://opds-spec.org/image" || it.rel == "http://opds-spec.org/cover" })?.href

        /** The file to download: the format the reader handles best (EPUB first). */
        val best: Link? get() = downloads.minByOrNull { formatRank(it) }

        val isBook: Boolean get() = downloads.isNotEmpty()
    }

    data class Feed(
        val title: String,
        val entries: List<Entry>,
        val next: String?,
        /** OpenSearch description document (fetch it for the template), if any. */
        val searchDescription: String?,
        /** Ready search template ("…{searchTerms}…"), if the feed gives one directly. */
        val searchTemplate: String?,
    )

    // ---- parsing ---------------------------------------------------------------------------------

    /** An OPDS 1 (Atom XML) or OPDS 2 (JSON) feed. */
    fun parse(body: ByteArray, base: String): Feed {
        val first = body.firstOrNull { !it.toInt().toChar().isWhitespace() && it.toInt() != 0xEF && it.toInt() != 0xBB && it.toInt() != 0xBF }
        return if (first?.toInt()?.toChar() == '{') parseJson(String(body, Charsets.UTF_8), base) else parseAtom(body, base)
    }

    private fun parseAtom(xml: ByteArray, base: String): Feed {
        val root = document(xml).documentElement
        if (root.localName() != "feed") throw IllegalArgumentException("Not an OPDS catalog (no Atom feed)")
        var next: String? = null
        var searchDesc: String? = null
        var searchTemplate: String? = null
        for (l in children(root, "link").map { link(it, base) }) {
            when {
                l.rel == "next" -> next = l.href
                l.rel == "search" && l.type?.contains("opensearchdescription") == true -> searchDesc = l.href
                l.rel == "search" && "{searchTerms}" in l.href -> searchTemplate = l.href
            }
        }
        val entries = children(root, "entry").map { e ->
            Entry(
                id = text(e, "id") ?: "",
                title = text(e, "title")?.trim()?.ifEmpty { null } ?: "Untitled",
                authors = children(e, "author").mapNotNull { a -> text(a, "name")?.trim()?.ifEmpty { null } },
                summary = (text(e, "summary") ?: text(e, "content"))?.let(::plain)?.ifEmpty { null },
                links = children(e, "link").map { link(it, base) },
            )
        }
        return Feed(text(root, "title")?.trim() ?: "Catalog", entries, next, searchDesc, searchTemplate)
    }

    // ---- OPDS 2 (JSON: Internet Archive, Komga, Thorium…) ------------------------------------------

    private fun parseJson(text: String, base: String): Feed {
        val root = org.json.JSONObject(text)
        fun links(arr: org.json.JSONArray?): List<Link> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val href = o.optString("href").ifEmpty { return@mapNotNull null }
            Link(resolve(base, href), o.optString("rel").ifEmpty { null }, o.optString("type").ifEmpty { null }, o.optString("title").ifEmpty { null })
        }
        fun navRow(o: org.json.JSONObject, i: Int): Entry? {
            val l = links(org.json.JSONArray().put(o)).firstOrNull() ?: return null
            // Mark it as a feed so it reads as a folder.
            return Entry("nav$i:${l.href}", l.title ?: "Untitled", emptyList(), null, listOf(l.copy(rel = "subsection", type = l.type ?: "application/opds+json")))
        }
        fun publication(o: org.json.JSONObject, i: Int): Entry? {
            val meta = o.optJSONObject("metadata") ?: return null
            val authors = when (val a = meta.opt("author")) {
                is String -> listOf(a)
                is org.json.JSONObject -> listOfNotNull(a.optString("name").ifEmpty { null })
                is org.json.JSONArray -> (0 until a.length()).mapNotNull { k ->
                    when (val x = a.opt(k)) {
                        is String -> x
                        is org.json.JSONObject -> x.optString("name").ifEmpty { null }
                        else -> null
                    }
                }
                else -> emptyList()
            }
            val images = links(o.optJSONArray("images")).mapIndexed { k, l -> l.copy(rel = if (k == 0) "http://opds-spec.org/image" else "http://opds-spec.org/image/thumbnail") }
            return Entry(
                meta.optString("identifier").ifEmpty { "pub$i" },
                meta.optString("title").ifEmpty { "Untitled" },
                authors,
                meta.optString("description").ifEmpty { null }?.let(::plain),
                links(o.optJSONArray("links")) + images.reversed(), // thumbnail (smaller) first
            )
        }
        val entries = ArrayList<Entry>()
        root.optJSONArray("navigation")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { navRow(it, i) }?.let(entries::add) }
        root.optJSONArray("publications")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { publication(it, i) }?.let(entries::add) }
        // Groups (a shelf with a few books and "more"): one folder row each, to its own feed.
        root.optJSONArray("groups")?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.optJSONObject(i) ?: continue
                val title = g.optJSONObject("metadata")?.optString("title")?.ifEmpty { null } ?: "More"
                val self = links(g.optJSONArray("links")).firstOrNull { it.rel == "self" }
                if (self != null) {
                    entries += Entry("group$i:${self.href}", title, emptyList(), null, listOf(self.copy(rel = "subsection", type = self.type ?: "application/opds+json")))
                } else {
                    g.optJSONArray("navigation")?.let { n -> for (k in 0 until n.length()) n.optJSONObject(k)?.let { navRow(it, 1000 * i + k) }?.let(entries::add) }
                    g.optJSONArray("publications")?.let { p -> for (k in 0 until p.length()) p.optJSONObject(k)?.let { publication(it, 1000 * i + k) }?.let(entries::add) }
                }
            }
        }
        val top = links(root.optJSONArray("links"))
        return Feed(
            root.optJSONObject("metadata")?.optString("title")?.ifEmpty { null } ?: "Catalog",
            entries,
            top.firstOrNull { it.rel == "next" }?.href,
            null,
            top.firstOrNull { it.rel == "search" }?.href,
        )
    }

    /** The Atom search template from an OpenSearch description, or null. */
    fun searchTemplate(xml: ByteArray, base: String): String? {
        val root = document(xml).documentElement
        val urls = children(root, "Url")
        val u = urls.firstOrNull { it.getAttribute("type").contains("atom") } ?: urls.firstOrNull() ?: return null
        val t = u.getAttribute("template").ifEmpty { return null }
        return resolve(base, t)
    }

    /**
     * The search URL for [terms]: OpenSearch's {searchTerms}, or OPDS 2's URI template {?query} /
     * {&query} / {query}; other (optional) parameters are dropped.
     */
    fun searchUrl(template: String, terms: String): String {
        val q = URLEncoder.encode(terms.trim(), "UTF-8").replace("+", "%20")
        return template.replace("{searchTerms}", q)
            .replace("{?query}", "?query=$q").replace("{&query}", "&query=$q").replace("{query}", q)
            .replace(Regex("""\{[^}]*}"""), "")
    }

    // ---- formats ---------------------------------------------------------------------------------

    private fun isFeed(type: String?) = type != null && ("atom+xml" in type || "opds+json" in type)

    private fun isDrm(type: String?) = type != null && ("adobe.adept" in type || "acsm" in type || "readium.lcp" in type)

    /** Lower = better. EPUB, then PDF, comics, Kindle formats, others. */
    fun formatRank(l: Link): Int {
        val t = (l.type ?: "").lowercase()
        val ext = extensionFor(l)
        return when {
            "epub" in t || ext == "epub" -> if ("kepub" in l.href.lowercase()) 1 else 0
            "pdf" in t || ext == "pdf" -> 2
            "cbz" in t || "comicbook+zip" in t || ext == "cbz" -> 3
            "cbr" in t || "comicbook-rar" in t || ext == "cbr" -> 4
            "fictionbook" in t || ext == "fb2" -> 5
            "mobipocket" in t || "amazon" in t || ext in setOf("mobi", "azw3", "azw") -> 6
            ext in setOf("cb7", "cbt", "txt", "rtf", "docx", "odt", "md", "html", "htm") -> 7
            else -> 9
        }
    }

    /** The file extension for a download (from its type, else its address), or null. */
    fun extensionFor(l: Link): String? {
        val t = (l.type ?: "").lowercase().substringBefore(';').trim()
        TYPE_EXT[t]?.let { return it }
        val path = runCatching { URI(l.href).path }.getOrNull() ?: l.href.substringBefore('?')
        return path.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
    }

    private val TYPE_EXT = mapOf(
        "application/epub+zip" to "epub",
        "application/pdf" to "pdf",
        "application/x-cbz" to "cbz",
        "application/vnd.comicbook+zip" to "cbz",
        "application/x-cbr" to "cbr",
        "application/vnd.comicbook-rar" to "cbr",
        "application/x-cb7" to "cb7",
        "application/x-mobipocket-ebook" to "mobi",
        "application/vnd.amazon.ebook" to "azw3",
        "application/x-mobi8-ebook" to "azw3",
        "application/fb2+zip" to "fb2",
        "application/x-fictionbook+xml" to "fb2",
        "text/plain" to "txt",
        "application/rtf" to "rtf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
    )

    /** A safe file name for a downloaded book: "Title - Author.epub". */
    fun fileName(entry: Entry, l: Link): String {
        val ext = extensionFor(l) ?: "epub"
        val base = listOfNotNull(entry.title, entry.authors.firstOrNull()).joinToString(" - ")
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "book" }
        return "$base.$ext"
    }

    // ---- XML helpers -----------------------------------------------------------------------------

    private fun document(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // No DTDs / external entities (XXE); not every parser knows every flag.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    private fun Node.localName(): String = (localName ?: nodeName).substringAfter(':')

    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.localName() == name) out += n
        }
        return out
    }

    private fun text(parent: Element, name: String): String? = children(parent, name).firstOrNull()?.textContent

    private fun link(e: Element, base: String) = Link(
        href = resolve(base, e.getAttribute("href")),
        rel = e.getAttribute("rel").ifEmpty { null },
        type = e.getAttribute("type").ifEmpty { null },
        title = e.getAttribute("title").ifEmpty { null },
    )

    /** [href] relative to [base]. Search templates' {braces} aren't URI characters: kept aside. */
    fun resolve(base: String, href: String): String {
        val safe = href.trim().replace(" ", "%20").replace("{", "%7B").replace("}", "%7D")
        runCatching { return URI(base).resolve(safe).toString().replace("%7B", "{").replace("%7D", "}") }
        // Addresses servers send aren't always strict URIs (unescaped [ ] | ^ or accents): the
        // lenient URL resolver, then plain joining.
        runCatching { return java.net.URL(java.net.URL(base), safe).toString().replace("%7B", "{").replace("%7D", "}") }
        val b = runCatching { URI(base) }.getOrNull() ?: return href
        return when {
            href.startsWith("//") -> "${b.scheme}:$href"
            href.startsWith("/") -> "${b.scheme}://${b.rawAuthority}$href"
            "://" in href -> href
            else -> base.substringBeforeLast('/') + "/" + href
        }
    }

    /** Summaries may be HTML: plain text for the list. */
    private fun plain(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ").replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ").trim()
}
