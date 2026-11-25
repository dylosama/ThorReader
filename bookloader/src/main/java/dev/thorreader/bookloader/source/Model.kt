package dev.thorreader.bookloader.source

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ContentType(val label: String) {
    BOOK("Books"), MANGA("Manga"), COMIC("Comics");

    /** The name Thor Reader's import API expects. */
    val apiName get() = when (this) { BOOK -> "Book"; MANGA -> "Manga"; COMIC -> "Comic" }
}

/**
 * One search / browse result. [folderUrl] set = an OPDS navigation entry (opens another list,
 * nothing to download). [extra] is the provider's own data (JSON text), kept so a queued
 * download can be resumed from just this object.
 */
data class CatalogItem(
    val sourceId: String,
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val coverUrl: String? = null,
    val type: ContentType = ContentType.BOOK,
    val summary: String? = null,
    val folderUrl: String? = null,
    val extra: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sourceId", sourceId).put("id", id).put("title", title)
        .put("authors", JSONArray(authors)).put("coverUrl", coverUrl).put("type", type.name)
        .put("summary", summary).put("folderUrl", folderUrl).put("extra", extra)

    companion object {
        fun fromJson(o: JSONObject) = CatalogItem(
            sourceId = o.getString("sourceId"),
            id = o.getString("id"),
            title = o.getString("title"),
            authors = o.optJSONArray("authors").strings(),
            coverUrl = o.optStringOrNull("coverUrl"),
            type = runCatching { ContentType.valueOf(o.getString("type")) }.getOrDefault(ContentType.BOOK),
            summary = o.optStringOrNull("summary"),
            folderUrl = o.optStringOrNull("folderUrl"),
            extra = o.optString("extra"),
        )
    }
}

/** One downloadable thing of an item: an edition / format, a chapter, an issue. */
data class Entry(
    val id: String,
    val title: String,
    /** Chapter / issue / volume number if known (auto series numbering). */
    val number: Float? = null,
    /** "EPUB", "PDF", "CBZ", ... (what the saved file will be). */
    val format: String,
    val url: String? = null,
    val extra: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("number", number?.toDouble())
        .put("format", format).put("url", url).put("extra", extra)

    companion object {
        fun fromJson(o: JSONObject) = Entry(
            id = o.getString("id"),
            title = o.getString("title"),
            number = if (o.isNull("number")) null else o.getDouble("number").toFloat(),
            format = o.getString("format"),
            url = o.optStringOrNull("url"),
            extra = o.optString("extra"),
        )
    }
}

data class ItemDetails(
    val item: CatalogItem,
    val synopsis: String? = null,
    val tags: List<String> = emptyList(),
    val formatLabel: String = "",
    val entries: List<Entry> = emptyList(),
    /** Right-to-left (Japanese manga). */
    val rtl: Boolean = false,
    /** "Chapters", "Issues", "Editions"... */
    val entriesLabel: String = "Editions",
    /** Shown under the details (licence / attribution, e.g. the scanlation group). */
    val note: String? = null,
)

/** Metadata sent with a book (import API extras / ComicInfo.xml). Null / empty = leave to Thor Reader. */
data class BookMeta(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesNumber: Float? = null,
    val type: ContentType? = null,
    val genres: List<String> = emptyList(),
    /** true = right-to-left, false = left-to-right, null = automatic. */
    val rtl: Boolean? = null,
    /** Thor Reader mode 1-4, null = Thor Reader decides. */
    val mode: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("authors", JSONArray(authors)).put("series", series)
        .put("seriesNumber", seriesNumber?.toDouble()).put("type", type?.name).put("genres", JSONArray(genres))
        .put("rtl", rtl).put("mode", mode)

    companion object {
        fun fromJson(o: JSONObject) = BookMeta(
            title = o.optStringOrNull("title"),
            authors = o.optJSONArray("authors").strings(),
            series = o.optStringOrNull("series"),
            seriesNumber = if (o.isNull("seriesNumber")) null else o.getDouble("seriesNumber").toFloat(),
            type = o.optStringOrNull("type")?.let { t -> runCatching { ContentType.valueOf(t) }.getOrNull() },
            genres = o.optJSONArray("genres").strings(),
            rtl = if (o.isNull("rtl")) null else o.getBoolean("rtl"),
            mode = if (o.isNull("mode")) null else o.getInt("mode"),
        )

        /**
         * What a download is filled in with before the user edits anything: a single edition keeps
         * the item's title; chapters / issues become "Series, Ch. 12" in series "Series" with
         * number 12 (or their position in the selection when they have no number).
         */
        fun defaults(d: ItemDetails, e: Entry, indexInSelection: Int): BookMeta {
            val item = d.item
            val chapters = item.type != ContentType.BOOK && (d.entries.size > 1 || e.number != null)
            return BookMeta(
                title = if (chapters) "${item.title}, ${e.title}" else item.title,
                authors = item.authors,
                series = if (chapters) item.title else null,
                seriesNumber = if (chapters) (e.number ?: (indexInSelection + 1).toFloat()) else null,
                type = item.type,
                genres = d.tags.take(12),
                rtl = if (item.type == ContentType.BOOK) null else d.rtl,
                mode = null,
            )
        }
    }
}

/**
 * A source of books - the plugin interface. Built in: Gutendex, MangaDex, OPDS feeds; users add
 * more OPDS feeds or JSON source lists at runtime. Every call runs off the main thread.
 */
interface SourceProvider {
    val id: String
    val name: String
    val types: Set<ContentType>
    /** One line for the Sources screen. */
    val description: String

    /** [query] blank = browse (popular / the feed's start page). [folderUrl] = an OPDS navigation entry. */
    fun search(query: String, type: ContentType?, folderUrl: String? = null): List<CatalogItem>

    fun details(item: CatalogItem): ItemDetails

    /**
     * Download [entry] into [dir] (packing chapters into a CBZ where needed) and return the final
     * file, named with the right extension. [progress] gets 0..1.
     */
    fun download(item: CatalogItem, entry: Entry, meta: BookMeta, dir: File, progress: (Float) -> Unit): File
}

// ---- small JSON helpers ----

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

/** A file name without folders or characters storage providers reject. */
fun safeName(s: String, max: Int = 100): String =
    s.filter { it >= ' ' && it !in "/\\<>:\"|?*" }.trim().trim('.').take(max).ifEmpty { "download" }
