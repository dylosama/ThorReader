package dev.thorreader.app.opds

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableStateListOf
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.Opds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One saved OPDS catalog. The password is kept encrypted (Android Keystore), never in the clear. */
data class Catalog(val id: String, val name: String, val url: String, val user: String?, val password: String?) {
    val host: String get() = runCatching { URL(url).host }.getOrDefault(url)
}

/**
 * The user's OPDS catalogs (Settings → Online → Catalogs): saved list, fetching pages and covers,
 * and downloading a book into Download/Thor Reader, then adding it to the library.
 * HTTPS only (the app's network rules). A catalog's sign-in is sent only to its own server.
 */
class Catalogs(private val app: ThorApp) {
    private val prefs = app.getSharedPreferences("opds", Context.MODE_PRIVATE)

    /** Saved catalogs, in order (a state list: Settings updates as it changes). */
    val list = mutableStateListOf<Catalog>().apply { addAll(load()) }

    private fun load(): List<Catalog> = runCatching {
        val arr = JSONArray(prefs.getString("catalogs", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Catalog(
                o.getString("id"), o.getString("name"), o.getString("url"),
                o.optString("user").ifEmpty { null },
                o.optString("pw").ifEmpty { null }?.let { Secrets.decrypt(it) },
            )
        }
    }.onFailure { Log.w(TAG, "Catalogs: couldn't read the saved list", it) }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject().put("id", c.id).put("name", c.name).put("url", c.url)
                    .put("user", c.user ?: "").put("pw", c.password?.let { Secrets.encrypt(it) } ?: ""),
            )
        }
        prefs.edit().putString("catalogs", arr.toString()).apply()
    }

    /**
     * Check [url] is an OPDS catalog (with the sign-in, if any) and save it. Returns an error
     * message, or null when added.
     */
    suspend fun add(name: String, url: String, user: String?, password: String?): String? {
        val u = normalize(url) ?: return "Enter the catalog's address (https://…)."
        val c = Catalog(UUID.randomUUID().toString(), name.trim().ifEmpty { URL(u).host.removePrefix("www.") }, u, user?.trim()?.ifEmpty { null }, password?.ifEmpty { null })
        val feed = try {
            feed(c, u)
        } catch (e: IOException) {
            return e.message ?: "Couldn't open that catalog."
        }
        withContext(Dispatchers.Main) {
            list += c.copy(name = if (name.isBlank()) feed.title.ifBlank { c.name } else c.name)
            save()
        }
        Log.i(TAG, "Catalogs: added '${c.host}' (${feed.entries.size} entries on its first page)")
        return null
    }

    fun remove(c: Catalog) {
        list.removeAll { it.id == c.id }
        save()
        // Its remembered downloads go too (the books stay in the library).
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("file_${c.id}_") || it.startsWith("got_${c.id}_") }.forEach(::remove)
        }.apply()
        Log.i(TAG, "Catalogs: removed '${c.host}'")
    }

    // ---- network ---------------------------------------------------------------------------------

    /** One feed page (OPDS 1 or 2). */
    suspend fun feed(c: Catalog, url: String): Opds.Feed = withContext(Dispatchers.IO) {
        val (body, finalUrl) = get(c, url, MAX_FEED, "application/atom+xml, application/opds+json, application/json;q=0.9, */*;q=0.5")
        try {
            Opds.parse(body, finalUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Catalogs: '${c.host}' answered something that isn't a catalog", e)
            throw IOException("That address didn't answer with a catalog (OPDS).")
        }
    }

    /** The search template of [feed] (fetching its OpenSearch description if needed), or null. */
    suspend fun searchTemplate(c: Catalog, feed: Opds.Feed): String? = feed.searchTemplate ?: feed.searchDescription?.let { d ->
        withContext(Dispatchers.IO) {
            runCatching { get(c, d, MAX_FEED, "application/opensearchdescription+xml, */*;q=0.5").let { (b, u) -> Opds.searchTemplate(b, u) } }.getOrNull()
        }
    }

    private val thumbs = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    /** Memory is short: drop the catalog covers (fetched again when shown). */
    fun trimThumbnails() = thumbs.evictAll()

    /** A cover thumbnail (about [widthPx] wide), cached in memory; null if it can't be loaded. */
    suspend fun thumbnail(c: Catalog, url: String, widthPx: Int): Bitmap? {
        thumbs.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (bytes, _) = get(c, url, MAX_THUMB, "image/*")
                // Size-checked (no image bombs from a catalog), decoded at the shown size.
                dev.thorreader.app.io.SafeImages.decode(bytes, widthPx * 2)
            }.getOrNull()?.also { thumbs.put(url, it) }
        }
    }

    /**
     * Download [link] of [entry] into Download/Thor Reader and add it to the library. [progress]
     * gets 0..1 (or -1 when the size is unknown). Returns null when done, else an error message.
     */
    suspend fun download(c: Catalog, entry: Opds.Entry, link: Opds.Link, progress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val name = Opds.fileName(entry, link)
        val resolver = app.contentResolver
        var target: Uri? = null
        try {
            var href = link.href
            var hops = 0
            while (true) {
                // Some servers (the Internet Archive's cache) answer with a redirect written as
                // JSON - {"status_code": 302, "_headers": {"location": …}} - instead of a real one.
                val wrapped = open(c, href, "*/*").use { (conn, stream) ->
                    val got = (conn.contentType ?: "").lowercase()
                    if ("json" !in got) return@use null
                    val j = runCatching { JSONObject(String(dev.thorreader.app.io.Limits.readCapped(stream, 64L shl 10, "a catalog answer"), Charsets.UTF_8)) }.getOrNull()
                    val loc = j?.takeIf { it.optInt("status_code") in 300..399 }?.optJSONObject("_headers")?.optString("location")
                    loc?.takeIf { it.startsWith("http") } ?: throw IOException(NOT_A_BOOK)
                } ?: break
                if (++hops > 3) throw IOException(NOT_A_BOOK)
                Log.d(TAG, "Catalogs: a JSON-wrapped redirect; following it")
                href = wrapped
            }
            open(c, href, "*/*").use { (conn, stream) ->
                val total = conn.contentLengthLong
                if (total > MAX_BOOK) throw IOException("That file is too large (${total shr 20} MB).")
                // A page or an error message instead of the file (some catalogs list items they can't serve).
                val got = (conn.contentType ?: "").lowercase()
                if (listOf("json", "html", "xml").any { it in got } && Opds.extensionFor(link) !in setOf("html", "htm", "fb2")) {
                    throw IOException(NOT_A_BOOK)
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Thor Reader")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't save to Downloads.")
                target = uri
                resolver.openOutputStream(uri)?.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_BOOK) throw IOException("That file is too large.")
                        out.write(buf, 0, n)
                        progress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                    if (done < 1024) throw IOException(NOT_A_BOOK)
                } ?: throw IOException("Can't save to Downloads.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            Log.i(TAG, "Catalogs: downloaded '$name' from '${c.host}'; adding to the library")
            app.importer.importFiles(listOf(target!!))
            prefs.edit().putString("file_${c.id}_${entry.id}", target.toString()).apply()
            null
        } catch (e: IOException) {
            target?.let { runCatching { resolver.delete(it, null, null) } }
            Log.w(TAG, "Catalogs: download of '$name' failed: ${e.message}")
            e.message ?: "The download failed."
        }
    }

    /** Is [entry] (downloaded from [c] before) still in the library? */
    suspend fun inLibrary(c: Catalog, entry: Opds.Entry): Boolean {
        val uri = prefs.getString("file_${c.id}_${entry.id}", null) ?: return false
        return withContext(Dispatchers.IO) { app.db.books().countWithUri(uri) > 0 }
    }

    private class Open(val conn: HttpURLConnection, val stream: InputStream) : AutoCloseable {
        operator fun component1() = conn
        operator fun component2() = stream
        override fun close() {
            runCatching { stream.close() }
            conn.disconnect()
        }
    }

    private fun get(c: Catalog, url: String, cap: Long, accept: String): Pair<ByteArray, String> =
        open(c, url, accept).use { o -> dev.thorreader.app.io.Limits.readCapped(o.stream, cap, "a catalog answer") to o.conn.url.toString() }

    /**
     * Open [url] (redirects followed here, at most 5). The catalog's sign-in (HTTP Basic) is only
     * sent to the catalog's own host, and only over HTTPS.
     */
    private fun open(c: Catalog, url: String, accept: String): Open {
        var u = secure(url)
        repeat(6) {
            val conn = try {
                (URL(u).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", dev.thorreader.app.metadata.Http.USER_AGENT)
                    setRequestProperty("Accept", accept)
                    if (c.user != null && c.password != null && URL(u).host.equals(c.host, ignoreCase = true)) {
                        val token = Base64.encodeToString("${c.user}:${c.password}".toByteArray(), Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $token")
                    }
                }
            } catch (e: IOException) {
                throw IOException("Can't connect to ${c.host}.")
            }
            val code = try {
                conn.responseCode
            } catch (e: UnknownHostException) {
                conn.disconnect()
                throw IOException("No network (or ${c.host} can't be found).")
            } catch (e: SocketTimeoutException) {
                conn.disconnect()
                throw IOException("${c.host} didn't answer in time.")
            } catch (e: javax.net.ssl.SSLException) {
                conn.disconnect()
                throw IOException("${c.host} has no valid secure (https) connection.")
            } catch (e: IOException) {
                conn.disconnect()
                throw IOException("Can't connect to ${c.host}.")
            }
            when {
                code in 300..399 -> {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    val to = Opds.resolve(u, loc ?: throw IOException("${c.host} sent a broken redirect."))
                    Log.d(TAG, "Catalogs: $code redirect to ${runCatching { URL(to).host }.getOrDefault("?")}")
                    u = try {
                        secure(to)
                    } catch (e: IOException) {
                        throw IOException("${c.host} sent the download to an address that isn't https.")
                    }
                }
                code == 401 || code == 403 -> {
                    conn.disconnect()
                    throw IOException(if (c.user == null) "This catalog needs a username and password." else "Wrong username or password for this catalog.")
                }
                code >= 400 -> {
                    conn.disconnect()
                    throw IOException("${c.host} answered HTTP $code.")
                }
                else -> return Open(conn, conn.inputStream)
            }
        }
        throw IOException("${c.host} redirected too often.")
    }

    /** HTTPS only: an http:// address is tried over https. */
    private fun secure(url: String): String = dev.thorreader.app.metadata.Http.requireHttps(url)

    private fun normalize(url: String): String? {
        val t = url.trim()
        if (t.isEmpty() || t == "https://") return null
        return runCatching { secure(if ("://" in t) t else "https://$t").also { URL(it) } }.getOrNull()
    }

    companion object {
        /** Free catalogs offered with one tap (OPDS 1 and OPDS 2). */
        val FREE = listOf(
            "Project Gutenberg" to "https://www.gutenberg.org/ebooks.opds/",
            "Internet Archive" to "https://archive.org/services/opds",
        )

        private const val NOT_A_BOOK = "The catalog didn't send a book file for this item (it may not have one)."
        private const val MAX_FEED = 8L shl 20
        private const val MAX_THUMB = 3L shl 20
        private const val MAX_BOOK = 500L shl 20
    }
}

/** Small secrets (catalog passwords) encrypted with a key that never leaves the Android Keystore. */
private object Secrets {
    private const val ALIAS = "thorreader_catalogs"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = runCatching {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
