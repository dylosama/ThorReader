package dev.thorreader.app.metadata

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.importer.FilenameParser
import dev.thorreader.app.importer.formatNumber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Putting an online match into a book's fields. Pure (unit-tested). */
object MetaApply {

    /** What we know about [b], for searching. */
    fun queryFor(b: Book): Query {
        val format = runCatching { BookFormat.valueOf(b.format) }.getOrNull()
        val parsed = FilenameParser.parse(if (format == BookFormat.IMAGES) "${b.fileName}.dir" else b.fileName, format?.comic == true)
        return Query(
            title = b.title, series = b.series ?: parsed.series, volume = parsed.volume,
            issue = parsed.issue ?: if (parsed.volume == null) b.seriesNumber else null,
            year = b.year ?: parsed.year, author = b.author, publisher = b.publisher, isbn = b.isbn,
        )
    }

    /** Was [b]'s title just made up from its file name (so a real title may replace it)? */
    fun titleFromFileName(b: Book): Boolean {
        val format = runCatching { BookFormat.valueOf(b.format) }.getOrNull()
        val parsed = FilenameParser.parse(if (format == BookFormat.IMAGES) "${b.fileName}.dir" else b.fileName, format?.comic == true)
        return b.title == parsed.title || b.title == b.fileName
    }

    /**
     * [b] with [c] applied. Fields edited by hand never change. Comics/manga take the online
     * series, people and a "Series Vol. 1" style title (when the title was only the file
     * name); books keep what their file says and only fill gaps.
     */
    fun apply(b: Book, c: Candidate): Book {
        val format = runCatching { BookFormat.valueOf(b.format) }.getOrNull()
        val comic = format?.comic == true
        val fromName = titleFromFileName(b)
        val q = queryFor(b)
        fun can(field: String) = !b.edited(field)

        val series = if (can("series") && c.series != null && (comic || b.series == null)) c.series else b.series
        val number = if (can("number") && b.seriesNumber == null) c.number ?: b.seriesNumber else b.seriesNumber
        val title = when {
            !can("title") -> b.title
            comic && fromName && series != null -> series + when {
                q.volume != null && q.issue != null -> " v${q.volume} #${formatNumber(q.issue)}"
                q.volume != null -> ", Vol. ${q.volume}"
                number != null -> " #${formatNumber(number)}"
                else -> ""
            }
            !comic && fromName -> c.title
            else -> b.title
        }
        val authors = if (can("authors") && c.authors.isNotEmpty() && (comic || b.author == null)) c.authors.joinToString(", ") else b.author
        val genres = if (can("genres")) Genres.join(Genres.split(b.genres) + c.genres) else b.genres
        val description = if (can("description") && b.description.isNullOrBlank()) c.description else b.description
        val manga = b.isManga || c.isManga == true
        return b.copy(
            title = title, series = series, seriesNumber = number, author = authors, genres = genres,
            description = description, isManga = manga,
            // Japanese manga reads right-to-left. Only the automatic flag: a direction set by
            // hand (rtlOverride) always wins over it.
            mangaRtl = b.mangaRtl || (manga && c.country == "JP"),
            country = c.country ?: b.country, publisher = b.publisher ?: c.publisher, year = b.year ?: c.year,
            storyArc = c.storyArc ?: b.storyArc,
        )
    }
}

/**
 * The ComicVine key, encrypted with an AES key that lives in the Android Keystore (it never
 * leaves the device's secure hardware). Only the encrypted text is stored in preferences.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("thor_reader_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    var comicVineKey: String?
        get() = runCatching {
            val stored = prefs.getString("cv", null) ?: return null
            val (iv, data) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
        set(value) {
            val v = value?.trim()
            if (v.isNullOrEmpty()) {
                prefs.edit().remove("cv").apply()
                return
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(v.toByteArray()), Base64.NO_WRAP)
            prefs.edit().putString("cv", out).apply()
        }

    private companion object {
        const val ALIAS = "thor_reader_comicvine"
    }
}
