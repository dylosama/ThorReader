package dev.thorreader.bookloader.source

import dev.thorreader.bookloader.work.Cbz
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * MangaDex through its official public API (api.mangadex.org): search, English chapters, and
 * each chapter's pages packed into a CBZ with ComicInfo.xml (the uploading group is credited in
 * the file). Only "safe" and "suggestive" titles; chapters hosted elsewhere (external links) are
 * left out. Requests stay well under MangaDex's rate limits.
 */
class MangaDexSource : SourceProvider {
    override val id = ID
    override val name = "MangaDex"
    override val types = setOf(ContentType.MANGA)
    override val description = "MangaDex public API: English chapters, packed into CBZ"

    override fun search(query: String, type: ContentType?, folderUrl: String?): List<CatalogItem> {
        if (type != null && type != ContentType.MANGA) return emptyList()
        val q = StringBuilder("$API/manga?limit=30")
        listOf("cover_art", "author", "artist").forEach { q.append("&includes%5B%5D=").append(it) }
        RATINGS.forEach { q.append("&contentRating%5B%5D=").append(it) }
        q.append("&availableTranslatedLanguage%5B%5D=").append(LANG).append("&hasAvailableChapters=true")
        if (query.isBlank()) q.append("&order%5BfollowedCount%5D=desc") else q.append("&title=").append(Http.enc(query.trim())).append("&order%5Brelevance%5D=desc")
        return parseSearch(Http.text(q.toString(), accept = "application/json").first)
    }

    override fun details(item: CatalogItem): ItemDetails {
        val entries = ArrayList<Entry>()
        var offset = 0
        var total: Int
        do {
            val url = "$API/manga/${item.id}/feed?limit=500&offset=$offset&translatedLanguage%5B%5D=$LANG" +
                "&order%5Bvolume%5D=asc&order%5Bchapter%5D=asc&includes%5B%5D=scanlation_group&includeExternalUrl=0" +
                RATINGS.joinToString("") { "&contentRating%5B%5D=$it" }
            val (page, t) = parseFeed(Http.text(url, accept = "application/json").first)
            entries += page
            total = t
            offset += 500
        } while (offset < total && offset < 3000)
        return detailsFrom(item, dedupe(entries))
    }

    override fun download(item: CatalogItem, entry: Entry, meta: BookMeta, dir: File, progress: (Float) -> Unit): File {
        val pages = parseAtHome(Http.text("$API/at-home/server/${entry.id}", accept = "application/json").first)
        if (pages.isEmpty()) throw IOException("This chapter has no pages on MangaDex")
        val pageDir = File(dir, "pages-${entry.id}").apply { mkdirs() }
        try {
            val files = pages.mapIndexed { i, (full, saver) ->
                val ext = full.substringAfterLast('.', "jpg").substringBefore('?')
                val f = File(pageDir, "%04d.%s".format(i + 1, ext))
                f.writeBytes(fetchPage(full, saver))
                progress((i + 1f) / pages.size * 0.95f)
                f
            }
            val x = runCatching { JSONObject(entry.extra) }.getOrDefault(JSONObject())
            val info = Cbz.comicInfo(
                meta,
                pageCount = files.size,
                summary = item.summary,
                web = "https://mangadex.org/chapter/${entry.id}",
                translator = x.optStringOrNull("group"),
                language = LANG,
                volume = x.optStringOrNull("volume"),
            )
            val out = File(dir, safeName(meta.title ?: "${item.title} ${entry.title}") + ".cbz")
            Cbz.write(out, files, info)
            progress(1f)
            return out
        } finally {
            pageDir.deleteRecursively()
        }
    }

    /** A page, retried; falls back to the smaller "data-saver" copy if the full one keeps failing. */
    private fun fetchPage(full: String, saver: String?): ByteArray {
        var last: Exception? = null
        for (url in listOfNotNull(full, full, saver)) {
            try {
                return Http.bytes(url, max = 40L shl 20)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("A page couldn't be downloaded (${last?.message})")
    }

    companion object {
        const val ID = "mangadex"
        const val API = "https://api.mangadex.org"
        const val LANG = "en"
        val RATINGS = listOf("safe", "suggestive")

        fun parseSearch(json: String): List<CatalogItem> = JSONObject(json).optJSONArray("data").objects().map { m ->
            val id = m.getString("id")
            val a = m.optJSONObject("attributes") ?: JSONObject()
            val rel = m.optJSONArray("relationships").objects()
            val cover = rel.firstOrNull { it.optString("type") == "cover_art" }?.optJSONObject("attributes")?.optStringOrNull("fileName")
            val people = rel.filter { it.optString("type") == "author" || it.optString("type") == "artist" }
                .mapNotNull { it.optJSONObject("attributes")?.optStringOrNull("name") }.distinct()
            val tags = a.optJSONArray("tags").objects().mapNotNull { it.optJSONObject("attributes")?.optJSONObject("name")?.optStringOrNull("en") }
            CatalogItem(
                sourceId = ID,
                id = id,
                title = titleOf(a),
                authors = people,
                coverUrl = cover?.let { "https://uploads.mangadex.org/covers/$id/$it.256.jpg" },
                type = ContentType.MANGA,
                summary = a.optJSONObject("description")?.optStringOrNull("en")?.let(::cleanDescription),
                extra = JSONObject()
                    .put("tags", org.json.JSONArray(tags))
                    .put("originalLanguage", a.optString("originalLanguage"))
                    .put("year", a.opt("year"))
                    .put("status", a.optString("status"))
                    .toString(),
            )
        }

        /** English title if there is one (also among the alternative titles), else the main one. */
        fun titleOf(a: JSONObject): String {
            val t = a.optJSONObject("title") ?: JSONObject()
            t.optStringOrNull("en")?.let { return it }
            a.optJSONArray("altTitles").objects().firstNotNullOfOrNull { it.optStringOrNull("en") }?.let { return it }
            return t.keys().asSequence().firstOrNull()?.let { t.optString(it) } ?: "Untitled"
        }

        /** MangaDex descriptions end with link lists and "---" sections: keep the story text. */
        fun cleanDescription(d: String): String = d.substringBefore("\n---").replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1").trim()

        /** One page of a chapter feed: chapters (no external links, no unavailable ones) and the total. */
        fun parseFeed(json: String): Pair<List<Entry>, Int> {
            val o = JSONObject(json)
            val list = o.optJSONArray("data").objects().mapNotNull { c ->
                val a = c.optJSONObject("attributes") ?: return@mapNotNull null
                if (!a.isNull("externalUrl") || a.optBoolean("isUnavailable") || a.optInt("pages") <= 0) return@mapNotNull null
                val num = a.optStringOrNull("chapter")
                val title = a.optStringOrNull("title")
                val group = c.optJSONArray("relationships").objects().firstOrNull { it.optString("type") == "scanlation_group" }
                    ?.optJSONObject("attributes")?.optStringOrNull("name")
                Entry(
                    id = c.getString("id"),
                    title = buildString {
                        append(if (num != null) "Ch. $num" else "Oneshot")
                        if (title != null) append(": ").append(title)
                    },
                    number = num?.toFloatOrNull(),
                    format = "CBZ",
                    extra = JSONObject().put("group", group).put("volume", a.optStringOrNull("volume")).put("pages", a.optInt("pages")).toString(),
                )
            }
            return list to o.optInt("total", list.size)
        }

        /** The same chapter from several groups: keep the first, in chapter order. */
        fun dedupe(entries: List<Entry>): List<Entry> =
            entries.distinctBy { it.number ?: it.id }.sortedWith(compareBy(nullsLast()) { it.number })

        fun detailsFrom(item: CatalogItem, entries: List<Entry>): ItemDetails {
            val x = runCatching { JSONObject(item.extra) }.getOrDefault(JSONObject())
            return ItemDetails(
                item = item,
                synopsis = item.summary,
                tags = x.optJSONArray("tags").strings(),
                formatLabel = "CBZ (one per chapter)",
                entries = entries,
                rtl = x.optString("originalLanguage") == "ja",
                entriesLabel = "Chapters",
                note = "Chapters are uploaded to MangaDex by scanlation groups (credited in each file). Please support official releases.",
            )
        }

        /** at-home/server answer -> page URLs (full quality, data-saver). */
        fun parseAtHome(json: String): List<Pair<String, String?>> {
            val o = JSONObject(json)
            val base = o.optStringOrNull("baseUrl") ?: return emptyList()
            val ch = o.optJSONObject("chapter") ?: return emptyList()
            val hash = ch.optString("hash")
            val saver = ch.optJSONArray("dataSaver").strings()
            return ch.optJSONArray("data").strings().mapIndexed { i, f ->
                "$base/data/$hash/$f" to saver.getOrNull(i)?.let { "$base/data-saver/$hash/$it" }
            }
        }
    }
}
