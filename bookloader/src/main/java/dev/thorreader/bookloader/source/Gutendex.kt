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
        Http.download(entry.url ?: error("No download link"), out, progress = progress)
        return out
    }

    companion object {
        const val ID = "gutendex"
        const val BASE = "https://gutendex.com"

        fun parseSearch(json: String): List<CatalogItem> = JSONObject(json).optJSONArray("results").objects().map { b ->
            val formats = b.optJSONObject("formats") ?: JSONObject()
            CatalogItem(
                sourceId = ID,
                id = b.get("id").toString(),
                title = b.optString("title").trim(),
                // Gutenberg writes "Shelley, Mary Wollstonecraft": show it as "Mary Wollstonecraft Shelley".
                authors = b.optJSONArray("authors").objects().map { flipName(it.optString("name")) }.filter { it.isNotBlank() },
                coverUrl = formats.optStringOrNull("image/jpeg"),
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
