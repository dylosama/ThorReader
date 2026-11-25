package dev.thorreader.app.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.engine.Anchor
import dev.thorreader.app.engine.EpubSections
import dev.thorreader.app.engine.KoSync
import dev.thorreader.app.engine.ReaderSession
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.io.SafFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Reading progress sync with a KOReader sync server - the one CrossPoint (Xteink X4) and
 * KOReader use - so a book read on the X4 opens here at the same place, and the other way round.
 *
 * Seamless: when a book opens (and when the app comes back to an open book) the server is asked;
 * if another device read it more recently, the reader jumps there ("Synced from …", and
 * "↩ Back to page N" undoes it). A position read here is sent a few seconds after the last page
 * turn, and at once when the book closes or the app is left. Needs the same book file on both
 * devices (books are matched by a fingerprint of the file, or by file name). Only EPUB, PDF and
 * comic files: other formats are read through a converted copy whose chapters don't line up.
 *
 * Only the MD5 of the password is kept (the protocol's key); the password itself never is.
 */
class ProgressSync(private val app: ThorApp) {
    private val prefs = app.getSharedPreferences("progress_sync", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    var server by mutableStateOf(prefs.getString("server", null) ?: DEFAULT_SERVER)
        private set
    var user by mutableStateOf(prefs.getString("user", null) ?: "")
        private set
    private var key: String? = prefs.getString("key", null)
    var signedIn by mutableStateOf(key != null && user.isNotEmpty())
        private set

    /** Match books by file name instead of file contents (KOReader's "filename" method). */
    var byFileName by mutableStateOf(prefs.getBoolean("by_name", false))
        private set

    /** For Settings: the last thing that happened ("Signed in", "Synced 'Dune'", an error). */
    var status by mutableStateOf<String?>(null)
        private set

    private val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
        prefs.edit().putString("device_id", it).apply()
    }

    fun updateEnabled(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("enabled", on).apply()
        Log.i(TAG, "Progress sync ${if (on) "on" else "off"}")
    }

    fun updateServer(url: String) {
        server = url.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER }
        prefs.edit().putString("server", server).apply()
    }

    fun updateByFileName(on: Boolean) {
        byFileName = on
        prefs.edit().putBoolean("by_name", on).apply()
    }

    // ---- account ---------------------------------------------------------------------------------

    /** Check [name] / [password] with the server and keep them (the key only). Returns a message. */
    suspend fun logIn(name: String, password: String, create: Boolean): String = withContext(Dispatchers.IO) {
        val u = name.trim()
        if (u.isEmpty() || password.isEmpty()) return@withContext "Enter a username and password."
        val k = KoSync.md5(password)
        val msg = try {
            if (create) {
                val (code, _) = call("POST", "/users/create", JSONObject().put("username", u).put("password", k).toString(), null)
                when {
                    code == 402 -> return@withContext "That username is taken on this server."
                    code !in 200..299 -> return@withContext "The server refused (HTTP $code)."
                }
            }
            val (code, _) = call("GET", "/users/auth", null, u to k)
            when {
                code in 200..299 -> null
                code == 401 -> "Wrong username or password."
                else -> "The server answered HTTP $code."
            }
        } catch (e: IOException) {
            "Can't reach the server (${e.message})."
        }
        if (msg != null) return@withContext msg
        withContext(Dispatchers.Main) {
            user = u
            key = k
            signedIn = true
            prefs.edit().putString("user", u).putString("key", k).apply()
            if (!enabled) updateEnabled(true)
        }
        Log.i(TAG, "Progress sync: signed in${if (create) " (new account)" else ""}")
        (if (create) "Account created. " else "") + "Signed in as $u."
    }

    fun logOut() {
        key = null
        signedIn = false
        prefs.edit().remove("key").apply()
        status = null
        Log.i(TAG, "Progress sync: signed out")
    }

    // ---- the reader's hooks ----------------------------------------------------------------------

    private var pushJob: Job? = null
    private var pending: ReaderSession? = null

    /** A book is open and laid out (or the app came back to it): has another device read further? */
    fun bookReady(s: ReaderSession, why: String) {
        if (!active(s.book)) return
        app.scope.launch {
            // Wait for the whole layout (a position anywhere in the book must be showable).
            var waited = 0
            while (!s.syncReady && waited < 30_000) {
                delay(250)
                waited += 250
            }
            if (app.session !== s || !s.syncReady) return@launch
            val book = s.book
            val doc = withContext(Dispatchers.IO) { documentId(book) } ?: return@launch
            val remote = try {
                withContext(Dispatchers.IO) { pull(doc) }
            } catch (e: IOException) {
                report("Couldn't sync '${book.title}': ${e.message}")
                return@launch
            }
            val localAt = prefs.getLong("moved_${book.id}", 0L)
            when {
                remote == null -> {
                    Log.i(TAG, "Progress sync ($why): no progress on the server for '${book.title}' yet")
                    if (localAt > 0) push(s, "first upload")
                }
                remote.deviceId == deviceId -> Log.i(TAG, "Progress sync ($why): the server has this device's own position")
                remote.timestamp * 1000 > localAt -> {
                    val a = withContext(Dispatchers.IO) { toAnchor(book, remote) }
                    if (a == null) {
                        Log.w(TAG, "Progress sync: couldn't place '${remote.progress}' in '${book.title}'")
                        return@launch
                    }
                    if (app.session !== s) return@launch
                    val device = remote.device.ifBlank { "another device" }
                    val jumped = s.syncJump(a, device)
                    // Remember the synced place as this device's latest (not to be sent back).
                    prefs.edit().putLong("moved_${book.id}", remote.timestamp * 1000).apply()
                    report(if (jumped) "Synced '${book.title}' from $device." else "'${book.title}' is already at $device's place.")
                }
                else -> {
                    Log.i(TAG, "Progress sync ($why): this device read '${book.title}' more recently than ${remote.device}; sending")
                    push(s, "newer here")
                }
            }
        }
    }

    /** The reader saved a new position. One made here is sent shortly (debounced). */
    fun positionSaved(s: ReaderSession, a: Anchor, fromSync: Boolean) {
        if (fromSync || !active(s.book)) return
        prefs.edit().putLong("moved_${s.book.id}", System.currentTimeMillis()).apply()
        pending = s
        pushJob?.cancel()
        pushJob = app.scope.launch {
            delay(PUSH_AFTER_MS)
            pending = null
            push(s, "reading")
        }
    }

    /** Send a waiting position now (book closing, app left). */
    fun flush(why: String) {
        val s = pending ?: return
        pending = null
        pushJob?.cancel()
        app.scope.launch(NonCancellable) { push(s, why) }
    }

    private fun active(book: Book): Boolean = enabled && signedIn && supported(book)

    private suspend fun push(s: ReaderSession, why: String) {
        val book = s.book
        val a = s.syncAnchor() ?: Anchor(book.posChapter, book.posFraction)
        try {
            withContext(Dispatchers.IO) {
                val doc = documentId(book) ?: return@withContext
                val (progress, pct) = fromAnchor(book, a) ?: return@withContext
                val body = JSONObject()
                    .put("document", doc)
                    .put("progress", progress)
                    .put("percentage", pct.toDouble())
                    .put("device", DEVICE_NAME)
                    .put("device_id", deviceId)
                val (code, _) = call("PUT", "/syncs/progress", body.toString(), auth())
                when {
                    code in 200..299 -> Log.i(TAG, "Progress sync ($why): sent '${book.title}' at ${"%.1f".format(pct * 100)} % ($progress)")
                    code == 401 -> throw IOException("the server no longer accepts the sign-in")
                    else -> throw IOException("HTTP $code")
                }
            }
        } catch (e: IOException) {
            report("Couldn't send '${book.title}': ${e.message}")
        }
    }

    private fun report(msg: String) {
        Log.i(TAG, "Progress sync: $msg")
        app.scope.launch(Dispatchers.Main) { status = msg }
    }

    // ---- documents and positions -----------------------------------------------------------------

    private fun supported(book: Book): Boolean =
        runCatching { BookFormat.valueOf(book.format) }.getOrNull() in SUPPORTED

    private fun isEpub(book: Book) = book.format == BookFormat.EPUB.name

    /** The book's id on the server (cached): KOReader's partial MD5 of the file, or of its name. */
    private fun documentId(book: Book): String? {
        val cacheKey = "doc_${book.id}_${if (byFileName) "name" else "bin"}"
        prefs.getString(cacheKey, null)?.let { return it }
        val id = if (byFileName) {
            KoSync.filenameMd5(book.fileName)
        } else {
            runCatching {
                SafFile.open(app, Uri.parse(book.uri)).use { f ->
                    val size = f.channel.size()
                    KoSync.partialMd5(size) { offset, len ->
                        val buf = ByteBuffer.allocate(minOf(len.toLong(), size - offset).toInt().coerceAtLeast(0))
                        var pos = offset
                        while (buf.hasRemaining()) {
                            val r = f.channel.read(buf, pos)
                            if (r <= 0) break
                            pos += r
                        }
                        buf.array().copyOf(buf.position())
                    }
                }
            }.onFailure { Log.w(TAG, "Progress sync: can't read '${book.fileName}' for its fingerprint", it) }.getOrNull()
        } ?: return null
        prefs.edit().putString(cacheKey, id).apply()
        return id
    }

    /** This device's position as the server's (progress, percentage). */
    private fun fromAnchor(book: Book, a: Anchor): Pair<String, Float>? {
        if (!isEpub(book)) {
            val count = book.pageCount.coerceAtLeast(1)
            val page = (a.chapter + 1).coerceIn(1, count)
            return page.toString() to page.toFloat() / count
        }
        return withEpub(book) { e ->
            val sizes = e.spine.indices.map { e.sectionSize(it) }
            val spine = a.chapter.coerceIn(0, (e.spine.size - 1).coerceAtLeast(0))
            val section = e.sectionHtml(spine)?.let { KoSync.scan(it) }
            if (section == null) {
                "/body/DocFragment[${spine + 1}]/body" to KoSync.bookFraction(sizes, spine, a.fraction)
            } else {
                val (xp, raw) = KoSync.xpointerFor(section, spine, a.fraction)
                xp to KoSync.bookFraction(sizes, spine, raw)
            }
        }
    }

    /** The server's position as this device's anchor. */
    private fun toAnchor(book: Book, r: Remote): Anchor? {
        if (!isEpub(book)) {
            val count = book.pageCount.coerceAtLeast(1)
            val page = r.progress.trim().toIntOrNull() ?: (r.percentage * count).toInt().coerceAtLeast(1)
            return Anchor((page - 1).coerceIn(0, count - 1), 0f)
        }
        return withEpub(book) { e ->
            val sizes = e.spine.indices.map { e.sectionSize(it) }
            val byPct = KoSync.sectionAt(sizes, r.percentage)
            val spine = KoSync.docFragment(r.progress)?.takeIf { it in e.spine.indices } ?: byPct.first
            val exact = e.sectionHtml(spine)?.let { KoSync.fractionFor(KoSync.scan(it), r.progress) }
            Anchor(spine, exact ?: if (byPct.first == spine) byPct.second else 0f)
        }
    }

    private fun <T> withEpub(book: Book, block: (EpubSections) -> T): T? = runCatching {
        SafFile.open(app, Uri.parse(book.uri)).use { f -> EpubSections(f).use(block) }
    }.onFailure { Log.w(TAG, "Progress sync: can't read '${book.fileName}'", it) }.getOrNull()

    // ---- the server ------------------------------------------------------------------------------

    private data class Remote(val progress: String, val percentage: Float, val device: String, val deviceId: String, val timestamp: Long)

    private fun auth(): Pair<String, String> = (user to (key ?: throw IOException("not signed in")))

    /** The server's position for [doc], or null if it has none. */
    private fun pull(doc: String): Remote? {
        val (code, text) = call("GET", "/syncs/progress/$doc", null, auth())
        when {
            code == 404 || code == 204 -> return null
            code == 401 -> throw IOException("the server no longer accepts the sign-in")
            code !in 200..299 -> throw IOException("HTTP $code")
        }
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (!j.has("percentage") || !j.has("progress")) return null // KOReader: "no progress found"
        return Remote(
            j.optString("progress"), j.optDouble("percentage", 0.0).toFloat(),
            j.optString("device"), j.optString("device_id"), j.optLong("timestamp", 0L),
        )
    }

    /** One request; returns (status, body). Only HTTPS (the app's network rules). */
    private fun call(method: String, path: String, body: String?, auth: Pair<String, String>?): Pair<Int, String> {
        val base = dev.thorreader.app.metadata.Http.requireHttps(if ("://" in server) server else "https://$server")
        val conn = try {
            (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.koreader.v1+json")
                setRequestProperty("User-Agent", dev.thorreader.app.metadata.Http.USER_AGENT)
                auth?.let { (u, k) ->
                    setRequestProperty("x-auth-user", u)
                    setRequestProperty("x-auth-key", k)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
        } catch (e: UnknownHostException) {
            throw IOException("no network")
        } catch (e: SocketTimeoutException) {
            throw IOException("timed out")
        } catch (e: IOException) {
            throw IOException("cannot connect")
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: UnknownHostException) {
                throw IOException("no network")
            } catch (e: SocketTimeoutException) {
                throw IOException("timed out")
            }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { String(dev.thorreader.app.io.Limits.readCapped(it, 64L shl 10, "a sync answer"), Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** CrossPoint's own sync server (the X4's default); KOReader's is https://sync.koreader.rocks. */
        const val DEFAULT_SERVER = "https://sync.crosspointreader.com"

        /** One-tap choices in Settings → Sync (any other KOReader-compatible server can be typed). */
        val SERVERS = listOf(
            "CrossPoint" to DEFAULT_SERVER,
            "KOReader" to "https://sync.koreader.rocks",
        )
        const val DEVICE_NAME = "Thor Reader"

        /** A position read here is sent this long after the last page turn. */
        const val PUSH_AFTER_MS = 8_000L

        private val SUPPORTED = setOf(BookFormat.EPUB, BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR, BookFormat.CB7, BookFormat.CBT)
    }
}
