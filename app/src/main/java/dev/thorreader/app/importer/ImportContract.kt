package dev.thorreader.app.importer

import dev.thorreader.app.ReadingMode
import dev.thorreader.app.data.Book
import dev.thorreader.app.metadata.Genres
import dev.thorreader.app.ui.LibraryLogic

/**
 * The trusted import API used by Thor Bookloader (same signing key). The companion keeps an
 * identical copy of these names (bookloader/.../ThorImportContract.kt).
 *
 * Bind to the service with action [ACTION_IMPORT_BOOKS] (package dev.thorreader.app); the bind
 * needs [PERMISSION_IMPORT] (signature). Send a Message with what = [MSG_IMPORT], replyTo set and
 * data = Bundle {
 *   [EXTRA_URIS]: ArrayList<Uri> (content:// only; the caller grants read access first),
 *   [EXTRA_METAS]: ArrayList<Bundle> (optional, same order; keys [M_TITLE]...),
 * }. Progress comes back as [MSG_PROGRESS] (arg1 = index, arg2 = count), the end as
 * [MSG_RESULT] with [EXTRA_RESULTS]: ArrayList<Bundle> {[R_URI], [R_STATUS], [R_BOOK_ID], [R_MESSAGE]}.
 */
object ImportContract {
    const val ACTION_IMPORT_BOOKS = "dev.thorreader.action.IMPORT_BOOKS"
    const val ACTION_CHOOSE_FOLDER = "dev.thorreader.action.CHOOSE_IMPORT_FOLDER"
    const val PERMISSION_IMPORT = "dev.thorreader.permission.IMPORT"

    const val MSG_IMPORT = 1
    const val MSG_PROGRESS = 2
    const val MSG_RESULT = 3

    const val EXTRA_URIS = "dev.thorreader.extra.URIS"
    const val EXTRA_METAS = "dev.thorreader.extra.METAS"
    const val EXTRA_RESULTS = "dev.thorreader.extra.RESULTS"

    // Per-book metadata keys (all optional).
    const val M_TITLE = "title"
    const val M_AUTHORS = "authors" // String[]
    const val M_SERIES = "series"
    const val M_SERIES_NUMBER = "series_number" // float
    const val M_TYPE = "type" // "Book" / "Manga" / "Comic"
    const val M_GENRES = "genres" // String[]
    const val M_DIRECTION = "direction" // "ltr" / "rtl"
    const val M_MODE = "mode" // int 1..4

    // Result keys.
    const val R_URI = "uri"
    const val R_STATUS = "status" // ImportStatus name
    const val R_BOOK_ID = "book_id" // long, -1 if none
    const val R_MESSAGE = "message"
}

enum class ImportStatus { IMPORTED, DUPLICATE, UNSUPPORTED, FAILED, NEEDS_FOLDER }

/**
 * Metadata the caller passed for one book. Parsed through [get] (a Bundle's get in the app, a
 * map in tests) and checked: wrong types, blank text and out-of-range values are dropped.
 */
data class IncomingMeta(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesNumber: Float? = null,
    /** LibraryLogic type name: "Books" / "Manga" / "Comics". */
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val rtl: Boolean? = null,
    val mode: ReadingMode? = null,
) {
    val isEmpty get() = this == IncomingMeta()

    /**
     * The book with these fields applied. Each one counts as a hand edit, so the online lookup
     * never overwrites it (it still fills the fields that were not given).
     */
    fun applyTo(b: Book): Book {
        val edited = LinkedHashSet(b.editedFields?.split(',')?.filter { it.isNotEmpty() }.orEmpty())
        var r = b
        title?.let { r = r.copy(title = it); edited += "title" }
        if (authors.isNotEmpty()) { r = r.copy(author = authors.joinToString(", ")); edited += "authors" }
        series?.let { r = r.copy(series = it); edited += "series" }
        seriesNumber?.let { r = r.copy(seriesNumber = it); edited += "number" }
        type?.let { r = r.copy(typeOverride = it, isManga = it == LibraryLogic.MANGA || r.isManga); edited += "type" }
        if (genres.isNotEmpty()) {
            Genres.join(genres.filter { Genres.isTag(it) })?.let { r = r.copy(genres = it); edited += "genres" }
        }
        rtl?.let { r = r.copy(rtlOverride = it) }
        mode?.let { r = r.copy(mode = it.name) }
        return r.copy(editedFields = edited.joinToString(",").ifEmpty { null })
    }

    companion object {
        private const val MAX_TEXT = 300
        private const val MAX_LIST = 30

        fun parse(get: (String) -> Any?): IncomingMeta {
            fun text(k: String) = (get(k) as? String)?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
            fun list(k: String): List<String> = when (val v = get(k)) {
                is Array<*> -> v.toList()
                is List<*> -> v
                is String -> v.split(',', ';')
                else -> emptyList()
            }.mapNotNull { (it as? String)?.trim()?.take(MAX_TEXT)?.takeIf { s -> s.isNotEmpty() } }.distinct().take(MAX_LIST)
            val number = when (val v = get(ImportContract.M_SERIES_NUMBER)) {
                is Number -> v.toFloat()
                is String -> v.trim().toFloatOrNull()
                else -> null
            }?.takeIf { it.isFinite() && it >= 0f && it < 100_000f }
            val type = when (text(ImportContract.M_TYPE)?.lowercase()) {
                "book", "books" -> LibraryLogic.BOOKS
                "manga" -> LibraryLogic.MANGA
                "comic", "comics" -> LibraryLogic.COMICS
                else -> null
            }
            val rtl = when (text(ImportContract.M_DIRECTION)?.lowercase()) {
                "rtl", "right-to-left" -> true
                "ltr", "left-to-right" -> false
                else -> null
            }
            val modeNo = when (val v = get(ImportContract.M_MODE)) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull()
                else -> null
            }
            return IncomingMeta(
                title = text(ImportContract.M_TITLE),
                authors = list(ImportContract.M_AUTHORS),
                series = text(ImportContract.M_SERIES),
                seriesNumber = number,
                type = type,
                genres = list(ImportContract.M_GENRES),
                rtl = rtl,
                mode = ReadingMode.entries.firstOrNull { it.number == modeNo },
            )
        }
    }
}
