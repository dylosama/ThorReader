package dev.thorreader.bookloader.source

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** A feed the user added (or a built-in one). Credentials are only ever sent to the feed's own host. */
data class OpdsFeed(
    val id: String,
    val name: String,
    val url: String,
    val type: ContentType = ContentType.COMIC,
    val user: String? = null,
    val password: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("type", type.name)
        .put("user", user).put("password", password)

    companion object {
        fun fromJson(o: JSONObject) = OpdsFeed(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            type = runCatching { ContentType.valueOf(o.optString("type")) }.getOrDefault(ContentType.COMIC),
            user = o.optStringOrNull("user"),
            password = o.optStringOrNull("password"),
        )
    }
}

/** One parsed OPDS page: entries (publications and navigation folders), how to search, the next page. */
data class OpdsPage(
    val items: List<CatalogItem>,
    /** A URL with {searchTerms} in it, ready to fill in. */
    val searchTemplate: String? = null,
    /** An OpenSearch description to fetch for the template (OPDS 1). */
    val openSearchUrl: String? = null,
    val next: String? = null,
)

/**
 * Any OPDS 1.2 (Atom XML) or OPDS 2.0 (JSON) catalogue: Komga, Kavita, Calibre-web, public-domain
 * libraries... Browse its navigation, search it if it offers search, download open acquisition
 * links (no buy / borrow / subscribe links).
 */
class OpdsSource(val feed: OpdsFeed) : SourceProvider {
    override val id = feed.id
    override val name = feed.name
    override val types = setOf(feed.type)
    override val description = "OPDS feed: ${runCatching { URL(feed.url).host }.getOrDefault(feed.url)}"

    private var searchTemplate: String? = null
    private var searchChecked = false

    private fun authFor(url: String): Http.Auth? {
        val u = feed.user ?: return null
        val sameHost = runCatching { URL(url).host == URL(feed.url).host }.getOrDefault(false)
        return if (sameHost) Http.Auth(u, feed.password.orEmpty()) else null
    }

    private fun page(url: String): OpdsPage {
        val (text, _) = Http.text(url, authFor(url), accept = "application/opds+json, application/atom+xml;q=0.9, */*;q=0.5")
        return OpdsParser.parse(text, url, feed)
    }

    override fun search(query: String, type: ContentType?, folderUrl: String?): List<CatalogItem> {
        if (type != null && type != feed.type) return emptyList()
        if (folderUrl != null || query.isBlank()) {
            // Browse: the page and up to two more ("next") pages.
            val out = ArrayList<CatalogItem>()
            var url: String? = folderUrl ?: feed.url
            var pages = 0
            while (url != null && pages < 3) {
                val p = page(url)
                out += p.items
                url = p.next
                pages++
            }
            return out
        }
        val template = searchUrlTemplate()
        if (template == null) {
            // No search offered: filter the start page.
            return page(feed.url).items.filter { it.title.contains(query.trim(), ignoreCase = true) }
        }
        return page(template.replace("{searchTerms}", Http.enc(query.trim()))).items
    }

    private fun searchUrlTemplate(): String? {
        if (searchChecked) return searchTemplate
        val root = page(feed.url)
        searchTemplate = root.searchTemplate ?: root.openSearchUrl?.let { os ->
            runCatching { OpdsParser.openSearchTemplate(Http.text(os, authFor(os)).first, os) }.getOrNull()
        }
        searchChecked = true
        return searchTemplate
    }

    override fun details(item: CatalogItem): ItemDetails {
        val x = runCatching { JSONObject(item.extra) }.getOrDefault(JSONObject())
        val entries = x.optJSONArray("acq").objects().mapIndexedNotNull { i, a ->
            val format = OpdsParser.formatOf(a.optString("type")) ?: return@mapIndexedNotNull null
            Entry(id = "acq$i", title = a.optStringOrNull("title") ?: format, number = null, format = format, url = a.optString("href"))
        }.distinctBy { it.format }
        return ItemDetails(
            item = item,
            synopsis = item.summary,
            tags = x.optJSONArray("tags").strings(),
            formatLabel = entries.joinToString(" / ") { it.format },
            entries = entries,
            rtl = item.type == ContentType.MANGA,
            entriesLabel = "Formats",
            note = "From ${feed.name}.",
        )
    }

    override fun download(item: CatalogItem, entry: Entry, meta: BookMeta, dir: File, progress: (Float) -> Unit): File {
        val url = entry.url ?: error("No download link")
        val tmp = File(dir, "download.part")
        val serverName = Http.download(url, tmp, authFor(url), progress)
        val ext = entry.format.lowercase()
        val base = safeName(meta.title ?: serverName?.substringBeforeLast('.') ?: item.title)
        val out = File(dir, "$base.$ext")
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        return out
    }
}

object OpdsParser {
    private const val ACQ = "http://opds-spec.org/acquisition"
    private val PAID = setOf("$ACQ/buy", "$ACQ/borrow", "$ACQ/subscribe", "$ACQ/sample", "$ACQ/preview")

    /** OPDS media type -> the file format Thor Reader reads; null = not downloadable for us. */
    fun formatOf(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
        "application/epub+zip" -> "EPUB"
        "application/pdf" -> "PDF"
        "application/vnd.comicbook+zip", "application/x-cbz", "application/zip", "application/x-zip-compressed" -> "CBZ"
        "application/vnd.comicbook-rar", "application/x-cbr", "application/x-rar-compressed", "application/vnd.rar" -> "CBR"
        "application/x-cb7", "application/x-7z-compressed" -> "CB7"
        "application/x-mobipocket-ebook" -> "MOBI"
        "application/x-fictionbook+xml" -> "FB2"
        else -> null
    }

    fun isDownloadRel(rel: String) = rel.startsWith(ACQ) && rel !in PAID

    fun parse(text: String, baseUrl: String, feed: OpdsFeed): OpdsPage =
        if (text.trimStart().startsWith("{")) parseJson(text, baseUrl, feed) else parseXml(text, baseUrl, feed)

    // ---- OPDS 2.0 (JSON) ----

    fun parseJson(text: String, baseUrl: String, feed: OpdsFeed): OpdsPage {
        val o = JSONObject(text)
        val items = ArrayList<CatalogItem>()
        fun nav(arr: JSONArray?) = arr.objects().forEach { n ->
            val href = n.optStringOrNull("href") ?: return@forEach
            val url = Http.resolve(baseUrl, href)
            if (url == baseUrl) return@forEach // a link back to this page
            items += CatalogItem(feed.id, "nav:$url", n.optString("title", "More"), type = feed.type, folderUrl = url)
        }
        fun pubs(arr: JSONArray?) = arr.objects().forEach { p -> publicationJson(p, baseUrl, feed)?.let { items += it } }
        nav(o.optJSONArray("navigation"))
        pubs(o.optJSONArray("publications"))
        o.optJSONArray("groups").objects().forEach { g ->
            nav(g.optJSONArray("navigation"))
            pubs(g.optJSONArray("publications"))
        }
        val links = o.optJSONArray("links").objects()
        val search = links.firstOrNull { relsOf(it).contains("search") }?.optStringOrNull("href")?.let { Http.resolve(baseUrl, it) }
        val next = links.firstOrNull { relsOf(it).contains("next") }?.optStringOrNull("href")?.let { Http.resolve(baseUrl, it) }
        return OpdsPage(items.distinctBy { it.id }, searchTemplate = search?.let(::expandUriTemplate), next = next)
    }

    /** "https://x/search{?query}" (RFC 6570) -> "https://x/search?query={searchTerms}". */
    fun expandUriTemplate(t: String): String {
        if (t.contains("{searchTerms}")) return t
        val m = Regex("\\{([?&])([^}]+)}").find(t) ?: return t
        val first = m.groupValues[2].split(',').first().trim()
        return t.replaceRange(m.range, "${m.groupValues[1]}$first={searchTerms}")
    }

    private fun relsOf(link: JSONObject): List<String> = when (val r = link.opt("rel")) {
        is JSONArray -> r.strings()
        is String -> listOf(r)
        else -> emptyList()
    }

    private fun names(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is JSONObject -> listOfNotNull(v.optStringOrNull("name"))
        is JSONArray -> (0 until v.length()).flatMap { names(v.opt(it)) }
        else -> emptyList()
    }

    private fun publicationJson(p: JSONObject, baseUrl: String, feed: OpdsFeed): CatalogItem? {
        val m = p.optJSONObject("metadata") ?: return null
        val title = m.optStringOrNull("title") ?: return null
        val acq = JSONArray()
        p.optJSONArray("links").objects().forEach { l ->
            if (relsOf(l).any { isDownloadRel(it) }) {
                acq.put(JSONObject().put("href", Http.resolve(baseUrl, l.optString("href"))).put("type", l.optString("type")).put("title", l.optStringOrNull("title")))
            }
        }
        val images = p.optJSONArray("images").objects()
        val cover = (images.firstOrNull { it.optInt("height", 0) in 1..600 } ?: images.firstOrNull())?.optStringOrNull("href")
        return CatalogItem(
            sourceId = feed.id,
            id = m.optStringOrNull("identifier") ?: "pub:$title",
            title = title,
            authors = names(m.opt("author")),
            coverUrl = cover?.let { Http.resolve(baseUrl, it) },
            type = feed.type,
            summary = m.optStringOrNull("description")?.let(::stripHtml),
            extra = JSONObject().put("acq", acq).put("tags", JSONArray(names(m.opt("subject")))).toString(),
        )
    }

    // ---- OPDS 1.2 (Atom XML) ----

    private fun dom(text: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(stripDoctype(text).byteInputStream())

    /** No DOCTYPE = no external entities, no entity expansion bombs. */
    fun stripDoctype(s: String): String = s.replace(Regex("<!DOCTYPE[^>\\[]*(\\[[^]]*])?[^>]*>", RegexOption.IGNORE_CASE), "")

    private fun Element.children(local: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { (it.localName ?: it.tagName) == local }

    private fun Element.text(local: String): String? = children(local).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    fun parseXml(text: String, baseUrl: String, feed: OpdsFeed): OpdsPage {
        val root = dom(text).documentElement
        val items = ArrayList<CatalogItem>()
        for (e in root.children("entry")) {
            val title = e.text("title") ?: continue
            val links = e.children("link")
            val acq = JSONArray()
            var cover: String? = null
            var thumb: String? = null
            var folder: String? = null
            for (l in links) {
                val rel = l.getAttribute("rel")
                val type = l.getAttribute("type")
                val href = l.getAttribute("href").takeIf { it.isNotEmpty() }?.let { Http.resolve(baseUrl, it) } ?: continue
                when {
                    isDownloadRel(rel) -> acq.put(JSONObject().put("href", href).put("type", type).put("title", l.getAttribute("title").ifEmpty { null }))
                    rel == "http://opds-spec.org/image/thumbnail" || rel == "http://opds-spec.org/thumbnail" -> thumb = href
                    rel == "http://opds-spec.org/image" || rel == "http://opds-spec.org/cover" -> cover = href
                    type.contains("atom+xml") || type.contains("opds") -> if (folder == null) folder = href
                }
            }
            val id = e.text("id") ?: title
            val authors = e.children("author").mapNotNull { it.text("name") }
            val tags = e.children("category").mapNotNull { c -> c.getAttribute("label").ifEmpty { c.getAttribute("term") }.takeIf { it.isNotBlank() } }
            val summary = (e.text("summary") ?: e.text("content"))?.let(::stripHtml)
            items += if (acq.length() > 0 || folder == null) {
                CatalogItem(
                    feed.id, id, title, authors, thumb ?: cover, feed.type, summary,
                    extra = JSONObject().put("acq", acq).put("tags", JSONArray(tags)).toString(),
                )
            } else {
                CatalogItem(feed.id, "nav:$folder", title, type = feed.type, summary = summary, folderUrl = folder)
            }
        }
        val feedLinks = root.children("link")
        fun link(rel: String, typePart: String? = null) = feedLinks.firstOrNull { it.getAttribute("rel") == rel && (typePart == null || it.getAttribute("type").contains(typePart)) }
            ?.getAttribute("href")?.takeIf { it.isNotEmpty() }?.let { Http.resolve(baseUrl, it) }
        val template = feedLinks.firstOrNull { it.getAttribute("rel") == "search" && it.getAttribute("href").contains("{searchTerms}") }
            ?.getAttribute("href")?.let { Http.resolve(baseUrl, it).replace("%7BsearchTerms%7D", "{searchTerms}") }
        return OpdsPage(
            items.filter { it.folderUrl == null || it.folderUrl != baseUrl }.distinctBy { it.id },
            searchTemplate = template,
            openSearchUrl = if (template == null) link("search", "opensearchdescription") else null,
            next = link("next"),
        )
    }

    /** OpenSearch description -> the Atom search URL template. */
    fun openSearchTemplate(text: String, baseUrl: String): String? {
        val root = dom(text).documentElement
        val urls = root.children("Url")
        val u = urls.firstOrNull { it.getAttribute("type").contains("atom") } ?: urls.firstOrNull() ?: return null
        return u.getAttribute("template").takeIf { it.contains("{searchTerms}") }?.let { Http.resolve(baseUrl, it).replace("%7BsearchTerms%7D", "{searchTerms}") }
    }

    fun stripHtml(s: String): String = s.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\n{3,}"), "\n\n").trim()
}
