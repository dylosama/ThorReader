package dev.thorreader.app.metadata

import android.util.Log
import dev.thorreader.app.TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** The server says "too many requests" / is down: try this source again later. */
class RetryLaterException(msg: String) : IOException(msg)

/** No network (or the server can't be reached). */
class OfflineException(msg: String) : IOException(msg)

/** Rejected credentials (e.g. a wrong ComicVine key). */
class BadKeyException(msg: String) : IOException(msg)

/**
 * Small HTTP client for the metadata lookups: one request at a time per host with a minimum
 * gap, back-off after 429/5xx, and a 30-day disk cache so repeated lookups cost nothing.
 * [secret] (an API key in a URL) is never written to the log or used in cache file names.
 */
class Http(private val cacheDir: File) {
    private class Host(val minGapMs: Long) {
        val lock = Mutex()
        var last = 0L
        var blockedUntil = 0L
        var backoffMs = 30_000L
    }

    private val hosts = HashMap<String, Host>()

    private fun host(name: String) = synchronized(hosts) {
        hosts.getOrPut(name) {
            Host(
                when {
                    name.contains("anilist") -> 2500L // AniList allows ~30-90 requests a minute
                    name.contains("comicvine") -> 1500L // ComicVine asks for about 1 a second
                    else -> 1000L
                },
            )
        }
    }

    /**
     * GET (or POST [body] as JSON) [url]; returns the response text. [cacheKey] identifies the
     * request without any secret. Throws [RetryLaterException], [OfflineException] or
     * [BadKeyException].
     */
    suspend fun request(url: String, cacheKey: String, body: String? = null, useCache: Boolean = true): String {
        val file = File(cacheDir, sha1(cacheKey + (body ?: "")) + ".json")
        if (useCache && file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MS) return file.readText()
        val h = host(URL(url).host)
        return h.lock.withLock {
            val now = System.currentTimeMillis()
            if (now < h.blockedUntil) throw RetryLaterException("${URL(url).host} asked us to wait")
            val wait = h.last + h.minGapMs - now
            if (wait > 0) delay(wait)
            h.last = System.currentTimeMillis()
            val conn = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write(body.toByteArray()) }
                    }
                }
            } catch (e: IOException) {
                throw OfflineException(if (e is UnknownHostException) "no network" else "cannot connect (${e.javaClass.simpleName})")
            }
            try {
                // Error messages from the connection may contain the URL (and so a key): replace them.
                val code = try {
                    conn.responseCode
                } catch (e: UnknownHostException) {
                    throw OfflineException("no network")
                } catch (e: SocketTimeoutException) {
                    throw OfflineException("timed out")
                } catch (e: IOException) {
                    throw OfflineException("cannot connect (${e.javaClass.simpleName})")
                }
                when {
                    code == 429 || code >= 500 -> {
                        val retry = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                        val pause = (retry ?: h.backoffMs).coerceAtMost(MAX_BACKOFF_MS)
                        h.blockedUntil = System.currentTimeMillis() + pause
                        h.backoffMs = (h.backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        Log.w(TAG, "Lookup: ${URL(url).host} answered $code; pausing that source for ${pause / 1000} s")
                        throw RetryLaterException("HTTP $code")
                    }
                    code == 401 || code == 403 -> throw BadKeyException("HTTP $code")
                    code >= 400 -> throw IOException("HTTP $code")
                }
                h.backoffMs = 30_000L
                val text = try {
                    conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                } catch (e: IOException) {
                    throw OfflineException("download broke off (${e.javaClass.simpleName})")
                }
                if (useCache) runCatching { cacheDir.mkdirs(); file.writeText(text) }
                text
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        const val USER_AGENT = "ThorReader/0.2.0 (Android e-reader)"
        private const val CACHE_MS = 30L * 24 * 3600 * 1000
        private const val MAX_BACKOFF_MS = 10L * 60 * 1000

        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
