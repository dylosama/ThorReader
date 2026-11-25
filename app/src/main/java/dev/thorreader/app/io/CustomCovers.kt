package dev.thorreader.app.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import dev.thorreader.app.TAG
import dev.thorreader.app.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * The user's own pictures for a book's front cover, spine and back cover (Edit info), picked from
 * internal storage or the SD card. Copied into the app's files (covers/custom), so the book file
 * and its original cover are never touched: resetting just deletes the copy, and the original
 * cover / generated spine / back come back.
 *
 * Front: used wherever the cover is shown (library, Edit info, Inspect, open / close animation).
 * Spine and back: Inspect only.
 */
object CustomCovers {
    enum class Part(val label: String, val file: String) {
        FRONT("Front cover", "front"),
        SPINE("Spine", "spine"),
        BACK("Back cover", "back"),
    }

    /** Longest side kept (plenty for the full-screen cover and Inspect's textures). */
    private const val MAX_SIDE = 2000

    /** (book id, part) -> its picture. A state map: covers on screen update when it changes. */
    private val files = mutableStateMapOf<Pair<Long, Part>, File>()
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "covers/custom")

    /** Read what is on disk, once. File names: "<book id>-<part>-<time>.jpg". */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        dir(context).listFiles()?.forEach { f ->
            val bits = f.nameWithoutExtension.split('-')
            val id = bits.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val part = Part.entries.firstOrNull { it.file == bits.getOrNull(1) } ?: return@forEach
            val old = files[id to part]
            if (old == null || old.lastModified() < f.lastModified()) {
                old?.delete()
                files[id to part] = f
            } else {
                f.delete()
            }
        }
    }

    fun file(bookId: Long, part: Part): File? = files[bookId to part]?.takeIf { it.exists() }

    fun has(bookId: Long, part: Part): Boolean = file(bookId, part) != null

    /** The front cover to show: the user's picture, else the book's own cover (or null). */
    fun frontPath(book: Book): String? = file(book.id, Part.FRONT)?.path ?: book.coverPath

    /**
     * Copy the picture at [uri] in as [part] of [bookId] (photos are turned upright, large ones
     * scaled down). False if it can't be read as an image.
     */
    suspend fun set(context: Context, bookId: Long, part: Part, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        load(context)
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val side = max(info.size.width, info.size.height)
                if (side > MAX_SIDE) {
                    val k = MAX_SIDE.toFloat() / side
                    d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.onFailure { Log.w(TAG, "Custom ${part.file} for book $bookId: not a readable image", it) }.getOrNull()
            ?: return@withContext false
        val d = dir(context).apply { mkdirs() }
        val out = File(d, "$bookId-${part.file}-${System.currentTimeMillis()}.jpg")
        val ok = runCatching { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }.getOrDefault(false)
        bmp.recycle()
        if (!ok) {
            out.delete()
            return@withContext false
        }
        withContext(Dispatchers.Main) {
            files.put(bookId to part, out)?.takeIf { it != out }?.delete()
        }
        Log.i(TAG, "Custom ${part.file} set for book $bookId (${out.length() / 1024} KB)")
        true
    }

    /** Back to the original cover / the generated spine or back. */
    fun reset(bookId: Long, part: Part) {
        files.remove(bookId to part)?.delete()
        Log.i(TAG, "Custom ${part.file} reset for book $bookId")
    }

    /** The book was removed from the library: drop its pictures. */
    fun removeAll(bookId: Long) {
        Part.entries.forEach { p -> files.remove(bookId to p)?.delete() }
    }
}
