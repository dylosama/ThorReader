package dev.thorreader.bookloader.source

import org.json.JSONObject
import java.io.File

/**
 * Project Gutenberg's public-domain books through the Gutendex API (gutendex.com): search by
 * title / author, EPUB (and PDF where Gutenberg has one). Everything here is public domain in the
 * USA; check your own country's rules.
 */
class GutendexSource : SourceProvider {
    override val id = ID
    override val name = "Project Gutenberg"
    override val types = setOf(ContentType.BOOK)
    override val description = "70,000+ public-domain books (Gutendex API), EPUB / PDF"

    override fun search(query: String, type: ContentType?, folderUrl: String?): List<CatalogItem> {
        if (type != null && type != ContentType.BOOK) return emptyList()
        val url = if (query.isBlank()) "$BASE/books/" else "$BASE/books/?search=${Http.enc(query.trim())}"
        return parseSearch(Http.text(url, accept = "application/json").first)
    }

    override fun details(item: CatalogItem): ItemDetails = parseDetails(item)

    override fun download(item: CatalogItem, entry: Entry, meta: BookMeta, dir: File, progress: (Float) -> Unit): File {
        val out = File(dir, safeName(meta.title ?: item.title) + "." + entry.format.lowercase())
        // gutenberg.org first, then Project Gutenberg's own mirrors (same files) if it is down.
        val urls = listOfNotNull(entry.url) + mirrors(item.id, entry.format)
        var last: Exception? = null
        for (u in urls) {
            try {
                Http.download(u, out, progress = progress)
                return out
            } catch (e: java.io.IOException) {
                last = e
                // Only "the server is down / unreachable" moves on to a mirror; 404 etc. won't change.
                if (e is HttpException && e.code < 500) throw e
            }
        }
        throw last ?: java.io.IOException("No download link")
    }

    companion object {
        const val ID = "gutendex"
        const val BASE = "https://gutendex.com"

        /** Project Gutenberg's official mirrors (gutenberg.org's own file layout). */
        val MIRRORS = listOf("https://gutenberg.pglaf.org", "https://aleph.pglaf.org")

        fun mirrors(id: String, format: String): List<String> = when (format) {
            "EPUB" -> MIRRORS.map { "$it/cache/epub/$id/pg$id-images-3.epub" }
            else -> emptyList()
        }

        fun parseSearch(json: String): List<CatalogItem> = JSONObject(json).optJSONArray("results").objects().map { b ->
            val formats = b.optJSONObject("formats") ?: JSONObject()
            CatalogItem(
                sourceId = ID,
                id = b.get("id").toString(),
                title = b.optString("title").trim(),
                // Gutenberg writes "Shelley, Mary Wollstonecraft": show it as "Mary Wollstonecraft Shelley".
                authors = b.optJSONArray("authors").objects().map { flipName(it.optString("name")) }.filter { it.isNotBlank() },
                coverUrl = formats.optStringOrNull("image/jpeg"),
                // gutenberg.org is sometimes down: the same cover from its official mirror.
                altCoverUrl = "${MIRRORS[0]}/cache/epub/${b.get("id")}/pg${b.get("id")}.cover.medium.jpg",
                type = ContentType.BOOK,
                summary = b.optJSONArray("summaries").strings().firstOrNull(),
                extra = JSONObject()
                    .put("formats", formats)
                    .put("subjects", b.optJSONArray("subjects"))
                    .put("bookshelves", b.optJSONArray("bookshelves"))
                    .put("languages", b.optJSONArray("languages"))
                    .toString(),
            )
        }

        fun parseDetails(item: CatalogItem): ItemDetails {
            val x = runCatching { JSONObject(item.extra) }.getOrDefault(JSONObject())
            val formats = x.optJSONObject("formats") ?: JSONObject()
            val entries = buildList {
                // EPUB first (Gutenberg's own .epub3.images / .epub.images links), then PDF.
                formats.keys().asSequence().filter { it.startsWith("application/epub+zip") }.firstOrNull()?.let {
                    add(Entry("epub", "EPUB (with pictures)", null, "EPUB", formats.getString(it)))
                }
                formats.keys().asSequence().filter { it.startsWith("application/pdf") }.firstOrNull()?.let {
                    add(Entry("pdf", "PDF", null, "PDF", formats.getString(it)))
                }
            }
            val subjects = x.optJSONArray("subjects").strings().map { it.substringBefore(" -- ").trim() }
            val shelves = x.optJSONArray("bookshelves").strings().map { it.removePrefix("Category: ").removePrefix("Browsing: ").trim() }
            val tags = (subjects + shelves).filter { it.length in 2..40 && !it.contains("(") }.distinct().take(15)
            return ItemDetails(
                item = item,
                synopsis = item.summary,
                tags = tags,
                formatLabel = entries.joinToString(" / ") { it.format },
                entries = entries,
                entriesLabel = "Formats",
                note = "Public domain in the USA (Project Gutenberg).",
            )
        }

        fun flipName(n: String): String {
            val parts = n.split(", ")
            return if (parts.size == 2 && !parts[1].contains(',')) "${parts[1]} ${parts[0]}".trim() else n.trim()
        }
    }
}
