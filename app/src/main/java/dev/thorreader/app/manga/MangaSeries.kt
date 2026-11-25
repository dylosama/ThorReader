package dev.thorreader.app.manga

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.engine.MangaSeriesMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A manga series' chapter map, looked up online (only with Settings → Library → Book info →
 * "Look up book info online" on): Wikipedia's volume list (which chapters each volume holds,
 * the newest chapters not in a volume yet) and AniList's status (Ongoing / Completed and the
 * final chapter count). Only the series name is sent. Kept on disk: a week for a running
 * series, three months for a finished one.
 */
class MangaSeries(private val app: ThorApp) {
    private val dir = File(app.filesDir, "manga_series").apply { mkdirs() }

    /** Series key → its map (null = looked up, nothing found). A state map: screens update. */
    val maps = mutableStateMapOf<String, MangaSeriesMap?>()
    private val lock = Mutex()

    /** The map for [series], from memory, disk, or online (if allowed). Null if unknown. */
    suspend fun map(series: String): MangaSeriesMap? {
        val key = seriesKey(series) ?: return null
        if (maps.containsKey(key)) return maps[key]
        return lock.withLock {
            if (maps.containsKey(key)) return@withLock maps[key]
            val cached = withContext(Dispatchers.IO) { readCache(key) }
            if (cached != null && !cached.second) {
                withContext(Dispatchers.Main) { maps[key] = cached.first }
                return@withLock cached.first
            }
            if (!app.settings.lookupOnline) return@withLock cached?.first
            val fresh = withContext(Dispatchers.IO) {
                runCatching { lookUp(series) }.onFailure { Log.w(TAG, "Manga series '$series': lookup failed (${it.message})") }.getOrNull()
            } ?: cached?.first
            withContext(Dispatchers.IO) { writeCache(key, fresh) }
            withContext(Dispatchers.Main) { maps[key] = fresh }
            fresh
        }
    }

    private fun lookUp(series: String): MangaSeriesMap? {
        val volumes = wikipedia(series)
        val (status, total) = aniList(series)
        if (volumes == null && status == null) {
            Log.i(TAG, "Manga series '$series': no volume list or status found")
            return null
        }
        val map = MangaSeriesMap(volumes?.first ?: emptyMap(), volumes?.second ?: emptyList(), status, total,
            listOfNotNull(volumes?.let { "Wikipedia" }, status?.let { "AniList" }).joinToString(" + "))
        Log.i(TAG, "Manga series '$series': ${map.volumes.size} volumes, ${map.loose.size} chapters not in a volume, last ${map.lastChapter}, $status (${map.source})")
        return map
    }

    // ---- Wikipedia -------------------------------------------------------------------------------

    private fun wikitext(title: String): Pair<String, String>? {
        val u = "https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&redirects=1&prop=wikitext&page=" + enc(title)
        val j = runCatching { JSONObject(get(u)) }.getOrNull() ?: return null
        val p = j.optJSONObject("parse") ?: return null
        return p.optString("title") to p.optString("wikitext")
    }

    /** Volumes → chapters (and loose chapters) from the series' Wikipedia pages, or null. */
    private fun wikipedia(series: String): Pair<Map<Int, List<Float>>, List<Float>>? {
        val tried = HashSet<String>()
        val candidates = ArrayList<String>().apply {
            add("List of $series chapters")
            add("$series (manga)")
            add(series)
        }
        runCatching {
            val s = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" + enc("$series manga")))
            val arr = s.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("title")?.let(candidates::add)
        }
        val words = series.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        for (c in candidates) {
            if (!tried.add(c.lowercase())) continue
            val (title, text) = wikitext(c) ?: continue
            if (words.isNotEmpty() && words.none { it in title.lowercase() }) continue // a different article
            var parsed = MangaSeriesMap.fromWikitext(text)
            // A main article that points to separate chapter lists: read those (several pages for
            // long series, e.g. "List of One Piece chapters (1–186)") and merge.
            val lists = Regex("""\[\[(List of [^\]|#]*chapters[^\]|#]*)""").findAll(text).map { it.groupValues[1] }.distinct()
                .filter { l -> words.isEmpty() || words.any { it in l.lowercase() } }.take(12).toList()
            if (lists.isNotEmpty()) {
                val vols = LinkedHashMap<Int, List<Float>>(parsed?.first ?: emptyMap())
                val loose = ArrayList<Float>(parsed?.second ?: emptyList())
                for (l in lists) {
                    if (!tried.add(l.lowercase())) continue
                    val more = wikitext(l)?.second?.let { MangaSeriesMap.fromWikitext(it) } ?: continue
                    more.first.forEach { (k, v) -> if (v.isNotEmpty() || k !in vols) vols[k] = v }
                    loose += more.second
                }
                if (vols.isNotEmpty()) {
                    val last = vols.values.flatten().maxOrNull() ?: 0f
                    parsed = vols.toSortedMap() to loose.filter { it > last }.distinct().sorted()
                }
            }
            if (parsed != null && parsed.first.values.any { it.isNotEmpty() }) {
                Log.i(TAG, "Manga series '$series': volume list from Wikipedia '$title'")
                return parsed
            }
        }
        return null
    }

    // ---- AniList ---------------------------------------------------------------------------------

    /** Status and (finished series) chapter count from AniList: the best title match. */
    private fun aniList(series: String): Pair<MangaSeriesMap.Status?, Int?> = runCatching {
        val q = "query(\$s:String){Page(perPage:8){media(search:\$s,type:MANGA,sort:SEARCH_MATCH){status chapters format title{romaji english native} synonyms}}}"
        val body = JSONObject().put("query", q).put("variables", JSONObject().put("s", series)).toString()
        val j = JSONObject(post("https://graphql.anilist.co", body))
        val media = j.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return@runCatching null to null
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val want = norm(series)
        // A work whose own title is the series beats one that only lists it as an alternative name
        // (AniList's top hit for "Blue Period" is "A Bitter Fruit"); the light novel adaptation
        // and one-shots never count; the comic (MANGA) before other formats.
        val all = (0 until media.length()).mapNotNull { media.optJSONObject(it) }.filter { it.optString("format") !in setOf("NOVEL", "ONE_SHOT") }
        fun titles(m: JSONObject) = m.optJSONObject("title").let { t -> listOfNotNull(t?.optString("english"), t?.optString("romaji"), t?.optString("native")) }
        fun synonyms(m: JSONObject) = m.optJSONArray("synonyms")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        fun hits(names: List<String>) = names.any { it.isNotBlank() && norm(it) == want }
        val byTitle = all.filter { hits(titles(it)) }
        val bySynonym = all.filter { hits(synonyms(it)) }
        val m = (byTitle.firstOrNull { it.optString("format") == "MANGA" } ?: byTitle.firstOrNull()
            ?: bySynonym.firstOrNull { it.optString("format") == "MANGA" } ?: bySynonym.firstOrNull())
            ?: return@runCatching null to null
        MangaSeriesMap.status(m.optString("status")) to (m.optInt("chapters", 0).takeIf { it > 0 })
    }.getOrDefault(null to null)

    // ---- HTTP and cache --------------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String = request(url, null)

    private fun post(url: String, body: String): String = request(url, body)

    private fun request(url: String, body: String?): String {
        val conn = (URL(dev.thorreader.app.metadata.Http.requireHttps(url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", dev.thorreader.app.metadata.Http.USER_AGENT)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { String(dev.thorreader.app.io.Limits.readCapped(it, 4L shl 20, "a series answer"), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun file(key: String) = File(dir, dev.thorreader.app.metadata.Http.sha1(key) + ".json")

    /** (map, stale?) from disk; a missing entry is null. */
    private fun readCache(key: String): Pair<MangaSeriesMap?, Boolean>? {
        val f = file(key)
        if (!f.exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            val map = j.optJSONObject("map")?.let { m ->
                val vols = LinkedHashMap<Int, List<Float>>()
                m.getJSONObject("volumes").let { v -> v.keys().forEach { k -> vols[k.toInt()] = floats(v.getJSONArray(k)) } }
                MangaSeriesMap(
                    vols.toSortedMap(), floats(m.getJSONArray("loose")),
                    m.optString("status").ifEmpty { null }?.let { runCatching { MangaSeriesMap.Status.valueOf(it) }.getOrNull() },
                    m.optInt("total", 0).takeIf { it > 0 }, m.optString("source"),
                )
            }
            val age = System.currentTimeMillis() - j.getLong("at")
            val ttl = if (map?.status == MangaSeriesMap.Status.COMPLETED) 90L * DAY else 7L * DAY
            map to (age > ttl)
        }.getOrNull()
    }

    private fun writeCache(key: String, map: MangaSeriesMap?) {
        val j = JSONObject().put("at", System.currentTimeMillis())
        if (map != null) {
            val vols = JSONObject()
            map.volumes.forEach { (k, v) -> vols.put(k.toString(), JSONArray(v.map { it.toDouble() })) }
            j.put("map", JSONObject().put("volumes", vols).put("loose", JSONArray(map.loose.map { it.toDouble() }))
                .put("status", map.status?.name ?: "").put("total", map.totalChapters ?: 0).put("source", map.source))
        }
        runCatching { file(key).writeText(j.toString()) }
    }

    private fun floats(a: JSONArray) = (0 until a.length()).map { a.getDouble(it).toFloat() }

    private companion object {
        const val DAY = 24L * 3600 * 1000
    }
}
