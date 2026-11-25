package dev.thorreader.bookloader.source

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * The only way Bookloader talks to the network: HTTPS only (redirects too), an honest
 * User-Agent, timeouts, size caps, and a small gap between requests to the same host (MangaDex
 * asks for at most 5 per second).
 */
object Http {
    /** The connection route (Settings → Connection). Set at start and whenever it changes. */
    @Volatile
    var route: NetConfig = NetConfig()

    const val USER_AGENT = "ThorBookloader/0.1.0 (Android e-reader companion)"
    const val MAX_TEXT = 16L shl 20
    const val MAX_FILE = 2L shl 30

    private val lastCall = HashMap<String, Long>()
    private const val MIN_GAP_MS = 250L

    /** Optional HTTP Basic credentials (OPDS servers like Komga), sent only over HTTPS. */
    data class Auth(val user: String, val password: String)

    private fun open(url: String, auth: Auth?, accept: String?, polite: Boolean = true): HttpURLConnection {
        val u = URL(url)
        if (u.protocol != "https") throw IOException("Only https:// addresses are allowed")
        val wait = synchronized(lastCall) {
            val now = System.nanoTime() / 1_000_000
            val next = (lastCall[u.host] ?: 0L) + MIN_GAP_MS
            lastCall[u.host] = maxOf(now, next)
            next - now
        }
        if (wait > 0 && polite) Thread.sleep(wait)
        val r = route
        val proxy = r.proxy()
        val c = (if (proxy == null) u.openConnection() else u.openConnection(proxy)) as HttpURLConnection
        // Tor and proxies are slower to connect: wait longer before giving up.
        c.connectTimeout = if (r.mode == NetMode.DIRECT) 15_000 else 45_000
        c.readTimeout = if (r.mode == NetMode.DIRECT) 30_000 else 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        accept?.let { c.setRequestProperty("Accept", it) }
        auth?.let {
            val token = java.util.Base64.getEncoder().encodeToString("${it.user}:${it.password}".toByteArray())
            c.setRequestProperty("Authorization", "Basic $token")
        }
        val code = try {
            c.responseCode
        } catch (e: java.io.IOException) {
            c.disconnect()
            // Never retried directly: a protected route that is down means no request at all.
            if (r.mode != NetMode.DIRECT && (e is java.net.ConnectException || e is java.net.SocketException || e.message?.contains("SOCKS", true) == true)) {
                throw IOException(
                    if (r.mode == NetMode.TOR) "Tor isn't reachable - start Orbot (nothing was sent without it)"
                    else "Your proxy isn't reachable (nothing was sent without it)",
                    e,
                )
            }
            throw e
        }
        if (code !in 200..299) {
            c.disconnect()
            throw HttpException(code, when (code) {
                401, 403 -> "The server refused access (HTTP $code)"
                404 -> "Not found (HTTP 404)"
                429 -> "Too many requests - try again in a minute (HTTP 429)"
                in 500..599 -> "The server has a problem right now (HTTP $code)"
                else -> "HTTP $code"
            })
        }
        if (c.url.protocol != "https") {
            c.disconnect()
            throw IOException("Redirected to a non-https address")
        }
        return c
    }

    /** A text response (JSON / XML) and its content type. */
    fun text(url: String, auth: Auth? = null, accept: String? = null): Pair<String, String> {
        val c = open(url, auth, accept)
        try {
            val bytes = c.inputStream.use { readCapped(it, MAX_TEXT) }
            return String(bytes, Charsets.UTF_8) to (c.contentType ?: "")
        } finally {
            c.disconnect()
        }
    }

    /** [polite] = false for pictures (covers): no pause between requests to the same host. */
    fun bytes(url: String, max: Long = 64L shl 20, auth: Auth? = null, polite: Boolean = true): ByteArray {
        val c = open(url, auth, null, polite)
        try {
            return c.inputStream.use { readCapped(it, max) }
        } finally {
            c.disconnect()
        }
    }

    /** Download to [out]; returns the server's file name (Content-Disposition) if it gave one. */
    fun download(url: String, out: File, auth: Auth? = null, progress: (Float) -> Unit = {}): String? {
        val c = open(url, auth, null)
        try {
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_FILE) throw IOException("The file is too large")
                        o.write(buf, 0, n)
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
            return c.getHeaderField("Content-Disposition")?.let { fileNameFromDisposition(it) }
        } finally {
            c.disconnect()
        }
    }

    fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("The response is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun fileNameFromDisposition(h: String): String? {
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(h)?.let {
            return java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
        }
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** [href] relative to [base] (OPDS links are often relative). */
    fun resolve(base: String, href: String): String {
        // "?page=2" keeps the base path (RFC 3986); java.net.URL would drop the last segment.
        if (href.startsWith("?")) return base.substringBefore('#').substringBefore('?') + href
        return runCatching { URL(URL(base), href).toString() }.getOrDefault(href)
    }
}
