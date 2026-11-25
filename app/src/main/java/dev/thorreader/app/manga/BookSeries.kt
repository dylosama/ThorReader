package dev.thorreader.app.manga

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.engine.BookSeriesList
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
 * A book series' numbered books (Dune 1-6), looked up on Wikidata - only with Settings ->
 * Library -> Book info -> "Look up book info online" on, and only the series name is sent (or,
 * for a book with no series, its title - see [detect]). Kept on disk for a month (a series
 * grows slowly); "nothing found" for a week.
 */
class BookSeries(private val app: ThorApp) {
    private val dir = File(app.filesDir, "book_series").apply { mkdirs() }

    /** Series key -> its list (null = looked up, nothing found). A state map: screens update. */
    val lists = mutableStateMapOf<String, BookSeriesList?>()
    private val lock = Mutex()

    /** The list for [series], from memory, disk, or online (if allowed). Null if unknown. */
    suspend fun list(series: String): BookSeriesList? {
        val key = seriesKey(series) ?: return null
        if (lists.containsKey(key)) return lists[key]
        return lock.withLock {
            if (lists.containsKey(key)) return@withLock lists[key]
            val cached = withContext(Dispatchers.IO) { readCache(key) }
            if (cached != null && !cached.second) {
                withContext(Dispatchers.Main) { lists[key] = cached.first }
                return@withLock cached.first
            }
            if (!app.settings.lookupOnline) {
                cached?.first?.let { withContext(Dispatchers.Main) { lists[key] = it } }
                return@withLock cached?.first
            }
            val fresh = withContext(Dispatchers.IO) {
                runCatching { lookUp(series) }.onFailure { Log.w(TAG, "Book series '$series': lookup failed (${it.message})") }
                    .getOrElse { return@withContext cached?.first ?: FAILED }
            }
            if (fresh === FAILED) return@withLock null // offline: try again next time
            withContext(Dispatchers.IO) { writeCache(key, fresh) }
            withContext(Dispatchers.Main) { lists[key] = fresh }
            fresh
        }
    }

    /** Lists already known (memory or disk) for the book series in the library, then the rest online. */
    suspend fun warm(series: Collection<String>) {
        for (s in series) list(s)
    }

    /**
     * Books (not manga / comics) with no series: which series each is in, by its title and author
     * on Wikidata (Harry Potter and the Philosopher's Stone -> Harry Potter #1), saved to the book.
     * Online only if allowed; each title is asked once (again after a month if nothing was found).
     */
    suspend fun detect(books: List<dev.thorreader.app.data.Book>) {
        if (!app.settings.lookupOnline) return
        val todo = books.filter { b ->
            b.series.isNullOrBlank() && !b.edited("series") && b.title.isNotBlank() &&
                dev.thorreader.app.ui.LibraryLogic.typeOf(b) == dev.thorreader.app.ui.LibraryLogic.BOOKS
        }
        for (b in todo) {
            val found = lock.withLock {
                withContext(Dispatchers.IO) {
                    val f = File(dir, "t_" + dev.thorreader.app.metadata.Http.sha1(b.title.lowercase() + "|" + (b.author ?: "").lowercase()) + ".json")
                    val old = runCatching { JSONObject(f.readText()) }.getOrNull()
                    if (old != null && (old.optString("series").isNotEmpty() || System.currentTimeMillis() - old.optLong("at") < 30L * DAY)) {
                        return@withContext old.optString("series").ifEmpty { null }?.let { it to old.optInt("n", 0).takeIf { n -> n > 0 } }
                    }
                    val hit = runCatching { seriesOfTitle(b.title, b.author) }
                        .onFailure { Log.w(TAG, "Book series of '${b.title}': lookup failed (${it.message})") }
                        .getOrElse { return@withContext null } // offline: try again next time
                    runCatching { f.writeText(JSONObject().put("at", System.currentTimeMillis()).put("series", hit?.first ?: "").put("n", hit?.second ?: 0).toString()) }
                    hit
                }
            } ?: continue
            Log.i(TAG, "Book series of '${b.title}': ${found.first} #${found.second ?: "?"}")
            app.db.books().setSeriesIfBlank(b.id, found.first, found.second?.toFloat())
        }
    }

    private fun seriesOfTitle(title: String, author: String?): Pair<String, Int?>? {
        val ids = BookSeriesList.searchIds(
            get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=15&search=" + enc(title.take(200))),
        )
        if (ids.isEmpty()) return null
        val answer = get("https://query.wikidata.org/sparql?format=json&query=" + enc(BookSeriesList.bookQuery(ids)), sparql = true)
        return BookSeriesList.seriesOfBook(answer, title, ids, author)
    }

    private fun lookUp(series: String): BookSeriesList? {
        val ids = BookSeriesList.searchIds(
            get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=10&search=" + enc(series.take(200))),
        )
        if (ids.isEmpty()) return null
        val answer = get("https://query.wikidata.org/sparql?format=json&query=" + enc(BookSeriesList.query(ids)), sparql = true)
        val list = BookSeriesList.fromSparql(answer, ids)
        Log.i(TAG, "Book series '$series': ${list?.books?.size ?: 0} numbered books on Wikidata")
        return list
    }

    // ---- HTTP and cache --------------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, sparql: Boolean = false): String {
        val conn = (URL(dev.thorreader.app.metadata.Http.requireHttps(url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", dev.thorreader.app.metadata.Http.USER_AGENT)
            setRequestProperty("Accept", if (sparql) "application/sparql-results+json" else "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { String(dev.thorreader.app.io.Limits.readCapped(it, 4L shl 20, "a series answer"), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun file(key: String) = File(dir, dev.thorreader.app.metadata.Http.sha1(key) + ".json")

    /** (list, stale?) from disk; a missing entry is null. */
    private fun readCache(key: String): Pair<BookSeriesList?, Boolean>? {
        val f = file(key)
        if (!f.exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            val list = j.optJSONArray("books")?.let { a ->
                BookSeriesList((0 until a.length()).map { i ->
                    val e = a.getJSONObject(i)
                    BookSeriesList.Entry(e.getInt("n"), e.optString("t").ifEmpty { null })
                })
            }
            val age = System.currentTimeMillis() - j.getLong("at")
            list to (age > (if (list != null) 30L else 7L) * DAY)
        }.getOrNull()
    }

    private fun writeCache(key: String, list: BookSeriesList?) {
        val j = JSONObject().put("at", System.currentTimeMillis())
        if (list != null) j.put("books", JSONArray(list.books.map { JSONObject().put("n", it.number).put("t", it.title ?: "") }))
        runCatching { file(key).writeText(j.toString()) }
    }

    private companion object {
        const val DAY = 24L * 3600 * 1000
        val FAILED = BookSeriesList(emptyList(), "failed")
    }
}
