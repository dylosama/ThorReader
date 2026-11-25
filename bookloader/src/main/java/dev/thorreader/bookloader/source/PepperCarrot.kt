package dev.thorreader.bookloader.source

import dev.thorreader.bookloader.work.Cbz
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Pepper&Carrot by David Revoy: a free-culture webcomic (Creative Commons Attribution 4.0 -
 * anyone may share it, crediting the author). Read from the site's official JSON catalogue;
 * each episode is packed into a CBZ (title page, pages, credits) with the author and licence in
 * ComicInfo.xml.
 */
class PepperCarrotSource : SourceProvider {
    override val id = ID
    override val name = "Pepper&Carrot"
    override val types = setOf(ContentType.COMIC)
    override val description = "Free-culture comic by David Revoy (CC BY 4.0), English episodes"

    override fun search(query: String, type: ContentType?, folderUrl: String?): List<CatalogItem> {
        if (type != null && type != ContentType.COMIC) return emptyList()
        val q = query.trim().lowercase()
        if (q.isNotEmpty() && listOf("pepper", "carrot", "revoy", "witch", "webcomic", "comic").none { q.contains(it) || it.contains(q) }) return emptyList()
        return listOf(ITEM)
    }

    override fun details(item: CatalogItem): ItemDetails {
        val episodes = parseEpisodes(Http.text("$BASE/episodes-v1.json", accept = "application/json").first)
        return ItemDetails(
            item = item,
            synopsis = item.summary,
            tags = listOf("Fantasy", "Comedy", "All ages", "Free culture"),
            formatLabel = "CBZ (one per episode)",
            entries = episodes,
            entriesLabel = "Episodes",
            note = "Pepper&Carrot © David Revoy, www.peppercarrot.com - Creative Commons Attribution 4.0. You may share it, crediting the author.",
        )
    }

    override fun download(item: CatalogItem, entry: Entry, meta: BookMeta, dir: File, progress: (Float) -> Unit): File {
        val files = JSONObject(entry.extra).optJSONArray("pages").strings()
        if (files.isEmpty()) throw IOException("This episode has no pages")
        val pageDir = File(dir, "pages-${entry.id}").apply { mkdirs() }
        try {
            val pages = files.mapIndexedNotNull { i, f ->
                val out = File(pageDir, "%03d.jpg".format(i + 1))
                // Pages with text come per language ("en_..."); pictures without text have no prefix.
                val bytes = runCatching { Http.bytes("$BASE/${entry.id}/low-res/${LANG}_$f", max = 20L shl 20) }
                    .recoverCatching { Http.bytes("$BASE/${entry.id}/low-res/$f", max = 20L shl 20) }
                    .getOrElse { throw IOException("A page couldn't be downloaded (${it.message})") }
                progress((i + 1f) / files.size * 0.95f)
                // Some "pages" are thin strips (episode 1's credits are 1200 x 24 px): leave those out.
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outHeight in 1 until 300) return@mapIndexedNotNull null
                out.writeBytes(bytes)
                out
            }
            val info = Cbz.comicInfo(
                meta.copy(authors = meta.authors.ifEmpty { listOf(AUTHOR) }),
                pageCount = pages.size,
                summary = "Pepper&Carrot, ${entry.title}. © David Revoy, www.peppercarrot.com - CC BY 4.0.",
                web = "https://www.peppercarrot.com",
                language = "en",
            )
            val out = File(dir, safeName(meta.title ?: "Pepper&Carrot ${entry.title}") + ".cbz")
            Cbz.write(out, pages, info)
            progress(1f)
            return out
        } finally {
            pageDir.deleteRecursively()
        }
    }

    companion object {
        const val ID = "peppercarrot"
        const val BASE = "https://www.peppercarrot.com/0_sources"
        const val LANG = "en"
        const val AUTHOR = "David Revoy"

        val ITEM = CatalogItem(
            sourceId = ID,
            id = "pepper-and-carrot",
            title = "Pepper&Carrot",
            authors = listOf(AUTHOR),
            coverUrl = "$BASE/ep01_Potion-of-Flight/low-res/Pepper-and-Carrot_by-David-Revoy_E01.jpg",
            type = ContentType.COMIC,
            summary = "The young witch Pepper and her cat Carrot in a world of magic and potions. A free, open-source webcomic: every episode is free to read and share.",
        )

        /** "ep07_The-Wish" -> "Ep. 7: The Wish". */
        fun titleOf(name: String): String {
            val n = name.substringBefore('_').removePrefix("ep").toIntOrNull()
            val t = name.substringAfter('_', "").replace('-', ' ').trim()
            return (if (n != null) "Ep. $n" else name) + if (t.isNotEmpty()) ": $t" else ""
        }

        /** Episodes in English, pages in reading order: title, 1..n, credits. */
        fun parseEpisodes(json: String): List<Entry> = JSONArray(json).objects().mapNotNull { e ->
            val name = e.optStringOrNull("name") ?: return@mapNotNull null
            if (!e.optJSONArray("translated_languages").strings().contains(LANG)) return@mapNotNull null
            val p = e.optJSONObject("pages") ?: return@mapNotNull null
            val numbered = p.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to p.getString(k) } }.sortedBy { it.first }.map { it.second }.toList()
            val pages = listOfNotNull(p.optStringOrNull("title")) + numbered + listOfNotNull(p.optStringOrNull("credits"))
            Entry(
                id = name,
                title = titleOf(name),
                number = name.substringBefore('_').removePrefix("ep").toFloatOrNull(),
                format = "CBZ",
                extra = JSONObject().put("pages", JSONArray(pages)).toString(),
            )
        }
    }
}
