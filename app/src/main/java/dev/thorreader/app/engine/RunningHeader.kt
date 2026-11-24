package dev.thorreader.app.engine

/**
 * Chooses the running-header text for a page of a reflowable book:
 *  1. the DEEPEST table-of-contents entry containing the page (a chapter beats its part/book),
 *     unless that entry is only a part/book name;
 *  2. otherwise the heading (h1-h3) at the start of the current section (EPUB spine item);
 *  3. otherwise "Chapter N", numbering the real sections within the current part.
 * The book's own title is never shown.
 */
object RunningHeader {

    /** A TOC entry located in the laid-out book: spine item [chapter], tall page [page], [y] pt. */
    class Entry(
        val title: String,
        val depth: Int,
        val chapter: Int,
        val page: Int,
        val y: Float,
        val hasChildren: Boolean,
    )

    enum class Source { TOC, HEADING, NUMBERED, NONE }

    data class Result(val text: String?, val source: Source, val detail: String)

    private val partWords = Regex("""^\s*(part|book|volume|vol\.?|tome|livre|teil|parte|deel)\b""", RegexOption.IGNORE_CASE)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** True if [text] repeats the book's title (e.g. "Book 1 DUNE" in "Dune"). */
    fun containsTitle(text: String, bookTitle: String): Boolean {
        val t = norm(bookTitle)
        return t.length >= 3 && norm(text).contains(t)
    }

    /** A part / book / volume grouping rather than a chapter. */
    fun isPartLike(e: Entry, bookTitle: String): Boolean =
        e.hasChildren || partWords.containsMatchIn(e.title) || containsTitle(e.title, bookTitle)

    /** The chain of TOC entries containing position (ch, page, y), outermost first. */
    fun containing(toc: List<Entry>, ch: Int, page: Int, y: Float): List<Entry> {
        val stack = ArrayList<Entry>()
        for (e in toc) {
            if (e.chapter < 0) continue
            val before = e.chapter < ch || (e.chapter == ch && (e.page < page || (e.page == page && e.y <= y + 1f)))
            if (!before) continue
            while (stack.isNotEmpty() && stack.last().depth >= e.depth) stack.removeAt(stack.size - 1)
            stack += e
        }
        return stack
    }

    /**
     * [heading] gives the opening heading of a spine item (null if none). [isRealSection] says
     * whether a spine item is a real text section (null = not known yet, still laying out).
     */
    fun choose(
        toc: List<Entry>,
        bookTitle: String,
        ch: Int,
        page: Int,
        y: Float,
        heading: (Int) -> String?,
        isRealSection: (Int) -> Boolean?,
    ): Result {
        val chain = containing(toc, ch, page, y)
        val deepest = chain.lastOrNull()
        if (deepest != null && !isPartLike(deepest, bookTitle)) {
            return Result(ChapterTitle.format(deepest.title), Source.TOC, "TOC entry '${deepest.title}' (depth ${deepest.depth})")
        }
        val h = heading(ch)
        if (h != null && !containsTitle(h, bookTitle) && !partWords.containsMatchIn(h)) {
            return Result(ChapterTitle.format(h), Source.HEADING, "section heading '$h'")
        }
        // Number the real text sections within the current part (or the whole book).
        val start = deepest?.chapter ?: 0
        val real = isRealSection(ch) ?: return Result(null, Source.NONE, "waiting for layout")
        if (!real) return Result(null, Source.NONE, "short section (title page / front matter)")
        var n = 0
        for (c in start..ch) {
            when (isRealSection(c)) {
                true -> n++
                false -> Unit
                null -> return Result(null, Source.NONE, "waiting for layout")
            }
        }
        val part = deepest?.let { "part '${it.title}'" } ?: "the book"
        return Result("Chapter $n", Source.NUMBERED, "section $n of $part")
    }
}
