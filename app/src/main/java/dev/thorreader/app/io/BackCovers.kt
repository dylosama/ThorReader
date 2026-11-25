package dev.thorreader.app.io

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import dev.thorreader.app.TAG
import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.BookFormat
import kotlin.math.abs

/**
 * The book's own back cover, if the file has one (Inspect book). No online sources.
 * - EPUB: a manifest image named like a back cover ("back", "rear", "bc").
 * - Comics: the ComicInfo page marked Type="BackCover"; else an image named like one; else the
 *   last image if it looks like a back cover (about the cover's shape, and little text).
 */
object BackCovers {
    fun find(context: Context, book: Book): ByteArray? = runCatching {
        when (val format = BookFormat.valueOf(book.format)) {
            BookFormat.EPUB -> SafFile.open(context, Uri.parse(book.uri)).use { f ->
                ZipImageArchive(f).use { a -> a.pages.firstOrNull { looksLikeBackName(it) }?.let { a.read(it) } }
            }
            BookFormat.CBZ -> SafFile.open(context, Uri.parse(book.uri)).use { f -> ZipImageArchive(f).use { comicBack(it) } }
            BookFormat.CBT -> SafFile.open(context, Uri.parse(book.uri)).use { f -> TarImageArchive(f).use { comicBack(it) } }
            BookFormat.CBR, BookFormat.CB7 -> SafFile.open(context, Uri.parse(book.uri)).use { f ->
                // Reuses the reader's unpacked copy when there is one.
                DirImageArchive(ComicArchives.unpack(context, "book${book.id}", f, format) {}).use { comicBack(it) }
            }
            else -> null
        }
    }.onFailure { Log.w(TAG, "Back cover of '${book.title}' couldn't be read", it) }.getOrNull()

    /** "back.jpg", "Cover_Back", "rear", "...-bc.png" - but not "background". */
    fun looksLikeBackName(path: String): Boolean {
        val n = path.substringAfterLast('/').lowercase()
        return ("back" in n && "background" !in n && "backdrop" !in n) || "rear" in n || Regex("(^|[^a-z])bc([^a-z]|$)").containsMatchIn(n)
    }

    /** The page index ComicInfo.xml marks as the back cover (Pages/Page Type="BackCover"), or null. */
    fun comicInfoBackIndex(xml: String): Int? {
        val page = Regex("<Page\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(xml).map { it.value }
            .firstOrNull { Regex("Type\\s*=\\s*\"BackCover\"", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
        return Regex("Image\\s*=\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun comicBack(a: ImageArchive): ByteArray? {
        val pages = a.pages
        if (pages.size < 2) return null
        a.comicInfo()?.let { String(it) }?.let(::comicInfoBackIndex)?.let { i -> pages.getOrNull(i)?.let { return a.read(it) } }
        pages.drop(1).lastOrNull(::looksLikeBackName)?.let { return a.read(it) }
        // The last image, if it is about the cover's shape and has little text on it.
        val first = a.read(pages.first())
        val last = a.read(pages.last())
        val ra = aspect(first) ?: return null
        val rb = aspect(last) ?: return null
        if (abs(ra - rb) / ra > 0.06f) return null
        val density = edgeDensity(last) ?: return null
        Log.d(TAG, "Back cover check: last page aspect ${"%.2f".format(rb)} vs cover ${"%.2f".format(ra)}, edge density ${"%.3f".format(density)}")
        return if (looksLikeCoverArt(density)) last else null
    }

    private fun aspect(bytes: ByteArray): Float? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
    }

    /**
     * How "busy with small strokes" a page is: the share of neighbouring pixels (at ~160 px wide)
     * that differ strongly. Lettering and speech balloons raise it.
     */
    private fun edgeDensity(bytes: ByteArray): Float? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 160) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            fun lum(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            var edges = 0
            var total = 0
            for (y in 0 until h) for (x in 1 until w) {
                total++
                if (abs(lum(px[y * w + x]) - lum(px[y * w + x - 1])) > 60) edges++
            }
            return if (total == 0) null else edges.toFloat() / total
        } finally {
            b.recycle()
        }
    }

    /** Few hard edges = cover art / an ad, not a story page full of lettering. */
    fun looksLikeCoverArt(edgeDensity: Float): Boolean = edgeDensity < 0.09f
}
