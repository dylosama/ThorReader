package dev.thorreader.app.importer

import android.content.Context

/**
 * SHA-256 of each book's file (book id -> hex), for "Already in library". Kept in its own small
 * preferences file rather than a new database column, so the library database stays exactly as
 * on main (a main-branch build can still open it). Old books are hashed lazily, only when a file
 * of the same size arrives.
 */
class ContentHashes(context: Context) {
    private val prefs = context.getSharedPreferences("content_hashes", Context.MODE_PRIVATE)

    fun get(bookId: Long): String? = prefs.getString(bookId.toString(), null)

    fun set(bookId: Long, sha: String) = prefs.edit().putString(bookId.toString(), sha).apply()

    /** Ids whose stored hash is [sha] (the caller checks the book still exists). */
    fun idsWith(sha: String): List<Long> = prefs.all.filter { it.value == sha }.keys.mapNotNull { it.toLongOrNull() }

    fun forget(bookId: Long) = prefs.edit().remove(bookId.toString()).apply()
}
