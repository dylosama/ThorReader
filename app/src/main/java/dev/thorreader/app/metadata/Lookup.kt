package dev.thorreader.app.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.data.saveMeta
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.importer.BookFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Online book info (metadata only - never book content, never the files themselves). Books are
 * looked up one at a time in the background: after import, on "Refresh info", for the one-time
 * re-scan after the update, and again later if the network was down.
 */
class MetadataLookup(private val app: ThorApp) {

    val http = Http(File(app.cacheDir, "lookup"))
    val secrets = SecretStore(app)

    /** "Looking up book info… 3 left" while working; null when idle. */
    var status by mutableStateOf<String?>(null)
        private set

    /** One-time hint: a comic was imported but there is no ComicVine key. */
    var showKeyHint by mutableStateOf(false)

    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val waiting = LinkedHashSet<Long>()
    private var retryJob: Job? = null

    fun start() {
        app.scope.launch(Dispatchers.IO) {
            rescanAfterUpdate()
            enqueue(app.db.books().idsNeedingLookup(), "start-up (pending or retry)")
            for (id in queue) {
                synchronized(waiting) { waiting.remove(id) }
                showProgress()
                try {
                    process(id, force = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "Lookup for book $id failed: ${t.message}")
                }
                showProgress()
            }
        }
        // Back online: retry what failed.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = retrySoon(5_000, "network available")
            })
        }
    }

    fun enqueue(ids: List<Long>, reason: String) {
        if (ids.isEmpty()) return
        val added = synchronized(waiting) { ids.filter { waiting.add(it) } }
        if (added.isEmpty()) return
        Log.i(TAG, "Lookup queued ${added.size} book(s) ($reason)")
        added.forEach { queue.trySend(it) }
        showProgress()
    }

    /** "Refresh info" in the library popup: look this book up again, ignoring the cache. */
    fun refresh(book: Book) {
        app.scope.launch(Dispatchers.IO) {
            app.db.books().setLookupState(book.id, "pending", book.needsReview, book.lookupAt)
            try {
                process(book.id, force = true)
            } catch (t: Throwable) {
                Log.w(TAG, "Refresh info for '${book.title}' failed: ${t.message}")
            }
        }
    }

    /** Called by the importer: comics were added. Shows the ComicVine hint once if there's no key. */
    fun comicsImported() {
        val prefs = app.getSharedPreferences("thor_reader", android.content.Context.MODE_PRIVATE)
        if (secrets.comicVineKey == null && !prefs.getBoolean("cv_hint_shown", false)) {
            prefs.edit().putBoolean("cv_hint_shown", true).apply()
            app.scope.launch(Dispatchers.Main) { showKeyHint = true }
        }
    }

    private fun showProgress() {
        val n = synchronized(waiting) { waiting.size }
        app.scope.launch(Dispatchers.Main) { status = if (n > 0) "Looking up book info… $n left" else null }
    }

    private fun retrySoon(ms: Long, reason: String) {
        retryJob?.cancel()
        retryJob = app.scope.launch(Dispatchers.IO) {
            delay(ms)
            enqueue(app.db.books().idsNeedingLookup(), "retry: $reason")
        }
    }

    // ---- one book -----------------------------------------------------------------------

    private suspend fun process(id: Long, force: Boolean) {
        val dao = app.db.books()
        val book = dao.get(id) ?: return
        if (!app.settings.lookupOnline) {
            dao.setLookupState(id, "off", book.needsReview, book.lookupAt)
            return
        }
        val now = System.currentTimeMillis()
        try {
            val result = search(book, useCache = !force)
            val best = result.candidates.firstOrNull()
            val confident = best != null && best.score >= result.threshold
            val q = MetaApply.queryFor(book)
            Log.i(
                TAG,
                "Lookup '${book.title}': source=${result.source}, query=\"${q.name}\"" +
                    (q.volume?.let { " vol $it" } ?: "") + (q.issue?.let { " #$it" } ?: "") + (q.year?.let { " ($it)" } ?: "") +
                    ", best=${best?.let { "\"${it.title}\" (${it.year ?: "?"})" } ?: "none"}" +
                    ", score=${best?.let { "%.2f".format(it.score) } ?: "-"}" +
                    ", ${if (confident) "APPLIED" else if (result.source == "none") "skipped: ${result.note}" else "not applied (needs review)"}",
            )
            when {
                result.source == "none" -> dao.saveMeta(book.copy(lookupState = result.note, needsReview = false, lookupAt = now))
                confident -> {
                    val full = complete(best!!, useCache = !force)
                    var updated = MetaApply.apply(book, full).copy(lookupState = "done", needsReview = false, lookupAt = now)
                    if (updated.coverPath == null) full.coverUrl?.let { url -> downloadCover(url)?.let { updated = updated.copy(coverPath = it) } }
                    dao.saveMeta(updated)
                    // Japanese manga: the rest of the series reads right-to-left too (unless set by hand).
                    if (updated.mangaRtl && !book.mangaRtl) seriesKey(updated.series)?.let { dao.setSeriesAutoRtl(it) }
                }
                else -> dao.saveMeta(book.copy(lookupState = if (best == null) "nomatch" else "review", needsReview = true, lookupAt = now))
            }
        } catch (e: BadKeyException) {
            Log.w(TAG, "Lookup '${book.title}': the ComicVine key was rejected")
            dao.setLookupState(id, "badkey", book.needsReview, now)
        } catch (e: OfflineException) {
            Log.i(TAG, "Lookup '${book.title}': offline (${e.message}); will retry")
            dao.setLookupState(id, "retry", book.needsReview, now)
        } catch (e: RetryLaterException) {
            Log.i(TAG, "Lookup '${book.title}': ${e.message}; will retry")
            dao.setLookupState(id, "retry", book.needsReview, now)
            retrySoon(10 * 60_000L, "rate limit")
        }
    }

    class Result(val source: String, val candidates: List<Candidate>, val threshold: Float, val note: String = "")

    /**
     * Pick the source for [b] and return its candidates, best first.
     * Manga -> AniList. Comics: ComicInfo (series + writer) is enough; otherwise AniList with a
     * strict score (is it manga?), then ComicVine if there is a key. Books -> Open Library.
     */
    suspend fun search(b: Book, useCache: Boolean = true): Result {
        val format = runCatching { BookFormat.valueOf(b.format) }.getOrNull()
        val q = MetaApply.queryFor(b)
        if (format?.comic != true) return Result("Open Library", openLibrary(q, useCache), Match.THRESHOLD)
        val manga = b.isManga || b.rtl || b.typeOverride == "Manga"
        if (manga) return Result("AniList", aniList(q, useCache), Match.THRESHOLD)
        val anilist = aniList(q, useCache)
        if ((anilist.firstOrNull()?.score ?: 0f) >= Match.STRICT_THRESHOLD) return Result("AniList", anilist, Match.STRICT_THRESHOLD)
        val key = secrets.comicVineKey
        if (key == null) {
            if (b.author != null && b.series != null) return Result("none", emptyList(), 1f, "done (ComicInfo / file name)")
            return Result("none", emptyList(), 1f, "nokey")
        }
        return Result("ComicVine", comicVine(key, q, useCache), Match.THRESHOLD)
    }

    /** Candidates for "Search online" in Edit info: the top 5 from the book's source(s). */
    suspend fun candidatesFor(b: Book): List<Candidate> {
        val format = runCatching { BookFormat.valueOf(b.format) }.getOrNull()
        val q = MetaApply.queryFor(b)
        val list = if (format?.comic != true) openLibrary(q, true) else {
            val a = aniList(q, true)
            val key = secrets.comicVineKey
            val cv = if (key != null && !(b.isManga || b.rtl)) runCatching { comicVine(key, q, true) }.getOrDefault(emptyList()) else emptyList()
            (a + cv).sortedByDescending { it.score }
        }
        return list.take(5)
    }

    /** Fetch what a candidate needs before it is applied (ComicVine credits, OL description). */
    suspend fun complete(c: Candidate, useCache: Boolean = true): Candidate = when {
        c.source == "ComicVine" -> {
            val key = secrets.comicVineKey ?: return c
            var r = c
            val issueId = c.detailId ?: c.number?.let { n ->
                val (url, safe) = ComicVine.issueSearch(key, c.id, fmt(n))
                ComicVine.firstIssueId(http.request(url, safe, useCache = useCache))
            }
            if (issueId != null) {
                val (url, safe) = ComicVine.issueDetails(key, issueId)
                r = ComicVine.withDetails(r, http.request(url, safe, useCache = useCache))
            }
            r
        }
        c.source == "Open Library" && c.workKey != null && c.description == null ->
            runCatching { c.copy(description = OpenLibrary.description(http.request(OpenLibrary.work(c.workKey), OpenLibrary.work(c.workKey), useCache = useCache))) }.getOrDefault(c)
        else -> c
    }

    private suspend fun aniList(q: Query, useCache: Boolean): List<Candidate> =
        AniList.parse(http.request(AniList.URL, "anilist:" + q.name, AniList.body(q.name), useCache), q)

    private suspend fun comicVine(key: String, q: Query, useCache: Boolean): List<Candidate> {
        val (url, safe) = ComicVine.volumeSearch(key, q.name)
        return ComicVine.parseVolumes(http.request(url, safe, useCache = useCache), q)
    }

    private suspend fun openLibrary(q: Query, useCache: Boolean): List<Candidate> {
        val url = OpenLibrary.search(q)
        val list = OpenLibrary.parse(http.request(url, url, useCache = useCache), q)
        // ISBN gave nothing: try title + author.
        if (list.isEmpty() && q.isbn != null) {
            val q2 = q.copy(isbn = null)
            val u2 = OpenLibrary.search(q2)
            return OpenLibrary.parse(http.request(u2, u2, useCache = useCache), q2)
        }
        return list
    }

    /** "Test key" in Settings: one ComicVine search. Returns null if it worked, else the error. */
    suspend fun testKey(key: String): String? = withContext(Dispatchers.IO) {
        try {
            val (url, safe) = ComicVine.volumeSearch(key, "Batman")
            ComicVine.check(http.request(url, safe, useCache = false))
            Log.i(TAG, "ComicVine key test: works")
            null
        } catch (e: BadKeyException) {
            Log.i(TAG, "ComicVine key test: rejected")
            "ComicVine rejected this key. Check it was copied completely."
        } catch (e: OfflineException) {
            "No connection to ComicVine. Check the Wi-Fi."
        } catch (e: Exception) {
            "ComicVine error: ${e.message}"
        }
    }

    /** Download a cover picture into app storage (for books whose file has none). */
    suspend fun downloadCover(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", Http.USER_AGENT)
            }
            val bytes = try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            val dir = File(app.filesDir, "covers").apply { mkdirs() }
            val out = File(dir, UUID.randomUUID().toString() + ".jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            out.absolutePath
        }.getOrNull()
    }

    /** Small picture for the Edit info candidate list. */
    suspend fun thumbnail(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", Http.USER_AGENT)
            }
            try { conn.inputStream.use { BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
        }.getOrNull()
    }

    // ---- one-time re-scan after the update ------------------------------------------------

    /**
     * First start after the step-13 update: re-read every book's file metadata with the new
     * rules (genres vs description, manga flag, ISBN...), then look them all up online.
     */
    private suspend fun rescanAfterUpdate() {
        val prefs = app.getSharedPreferences("thor_reader", android.content.Context.MODE_PRIVATE)
        if (prefs.getInt("meta_version", 1) >= META_VERSION) return
        val books = app.db.books().all()
        Log.i(TAG, "Metadata re-scan of ${books.size} books (new rules)")
        books.forEachIndexed { i, b ->
            withContext(Dispatchers.Main) { status = "Updating book info… ${i + 1} of ${books.size}" }
            val fresh = app.importer.rescan(b) ?: b.copy(genres = Genres.join(Genres.normalize(Genres.split(b.genres))), description = b.description ?: Genres.descriptionFrom(Genres.split(b.genres)))
            app.db.books().saveMeta(fresh.copy(lookupState = "pending", needsReview = false))
        }
        prefs.edit().putInt("meta_version", META_VERSION).apply()
        withContext(Dispatchers.Main) { status = null }
        Log.i(TAG, "Metadata re-scan done; looking all books up online")
    }

    private fun fmt(f: Float) = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

    companion object {
        const val META_VERSION = 3
    }
}
