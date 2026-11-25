package dev.thorreader.app.io

import android.content.Context
import android.util.Log
import dev.thorreader.app.TAG
import java.io.File

/**
 * One size limit for everything the app can re-create: unpacked comics, layout caches, page
 * sizes, converted EPUBs and the online-lookup cache. Covers and the library are never counted
 * or deleted. Least recently used first; anything used in the last [RECENT_MS] is kept (it may
 * belong to the open book).
 */
object CacheBudget {
    const val DEFAULT_MB = 500
    val CHOICES_MB = listOf(250, 500, 1000, 2000)
    private const val RECENT_MS = 30 * 60_000L

    /** One deletable thing (a file, or an unpacked comic folder). */
    data class Item(val path: String, val bytes: Long, val lastUsed: Long)

    /** Which items to delete so the rest fits [budgetBytes]: oldest first, never recent ones. */
    fun toDelete(items: List<Item>, budgetBytes: Long, now: Long): List<Item> {
        var total = items.sumOf { it.bytes }
        if (total <= budgetBytes) return emptyList()
        val out = ArrayList<Item>()
        for (it in items.sortedBy { it.lastUsed }) {
            if (total <= budgetBytes) break
            if (now - it.lastUsed < RECENT_MS) continue
            out += it
            total -= it.bytes
        }
        return out
    }

    private fun dirs(context: Context): List<File> = listOf(
        File(context.cacheDir, "comics"),
        File(context.cacheDir, "lookup"),
        File(context.filesDir, "layouts"),
        File(context.filesDir, "pagesizes"),
        File(context.filesDir, "converted"),
    )

    private fun sizeOf(f: File): Long = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()

    /** Everything currently in the budget. */
    fun items(context: Context): List<Item> = dirs(context).flatMap { d ->
        d.listFiles()?.map { f -> Item(f.path, sizeOf(f), f.lastModified()) }.orEmpty()
    }

    fun totalBytes(context: Context): Long = items(context).sumOf { it.bytes }

    /** Delete the least recently used items until the caches fit [limitMb]. Background thread. */
    fun trim(context: Context, limitMb: Int) {
        val items = items(context)
        val del = toDelete(items, limitMb * 1024L * 1024L, System.currentTimeMillis())
        if (del.isEmpty()) return
        del.forEach { File(it.path).deleteRecursively() }
        Log.i(TAG, "Cache over $limitMb MB: removed ${del.size} item(s), ${del.sumOf { it.bytes } / (1024 * 1024)} MB")
    }

    /** "Clear cache" (from the library, so no book is open). Covers and the library stay. */
    fun clear(context: Context): Long {
        val bytes = totalBytes(context)
        dirs(context).forEach { d -> d.listFiles()?.forEach { it.deleteRecursively() } }
        Log.i(TAG, "Cache cleared: ${bytes / (1024 * 1024)} MB")
        return bytes
    }
}
