package dev.thorreader.app.engine

import org.json.JSONObject

/**
 * A book series' numbered books (Dune: 1 Dune, 2 Dune Messiah, ...), from Wikidata: books that
 * are "part of the series" (P179) with a "series ordinal" (P1545). Pure (unit-tested).
 */
data class BookSeriesList(val books: List<Entry>, val source: String = "Wikidata") {
    data class Entry(val number: Int, val title: String?)

    val last: Int? get() = books.maxOfOrNull { it.number }

    fun titleOf(n: Int): String? = books.firstOrNull { it.number == n }?.title

    /** The entry whose title is [title] (ignoring case, punctuation and a leading "The"/"A"). */
    fun numberOfTitle(title: String): Int? {
        val t = norm(title)
        if (t.isEmpty()) return null
        books.firstOrNull { it.title != null && norm(it.title) == t }?.let { return it.number }
        // "Dune Messiah (Dune 2)", "Mistborn: The Final Empire" vs "The Final Empire".
        return books.filter { it.title != null && norm(it.title).length >= 4 }
            .filter { t.startsWith(norm(it.title!!)) || norm(it.title).endsWith(t) && t.length >= 6 }
            .maxByOrNull { norm(it.title!!).length }?.number
    }

    companion object {
        fun norm(s: String): String = s.lowercase()
            .replace(Regex("^(the|a|an)\\s+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

        /** The SPARQL query for the books of the series items [ids] (Q numbers). */
        fun query(ids: List<String>): String {
            val values = ids.filter { it.matches(Regex("Q\\d{1,12}")) }.joinToString(" ") { "wd:$it" }
            return """
                SELECT ?series ?book ?bookLabel ?ord ?links WHERE {
                  VALUES ?series { $values }
                  ?book p:P179 ?st . ?st ps:P179 ?series . ?st pq:P1545 ?ord .
                  ?book wdt:P31 ?type .
                  VALUES ?type { wd:Q7725634 wd:Q8261 wd:Q47461344 wd:Q571 wd:Q1238720 wd:Q277759 }
                  OPTIONAL { ?book wikibase:sitelinks ?links . }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul,en-gb". }
                }
            """.trimIndent()
        }

        /** The SPARQL query for the series (and number in it) of the book items [ids]. */
        fun bookQuery(ids: List<String>): String {
            val values = ids.filter { it.matches(Regex("Q\\d{1,12}")) }.joinToString(" ") { "wd:$it" }
            return """
                SELECT ?book ?bookLabel ?series ?seriesLabel ?ord ?aLabel WHERE {
                  VALUES ?book { $values }
                  ?book wdt:P31 ?type .
                  VALUES ?type { wd:Q7725634 wd:Q8261 wd:Q47461344 wd:Q571 wd:Q1238720 wd:Q277759 }
                  ?book p:P179 ?st . ?st ps:P179 ?series .
                  OPTIONAL { ?st pq:P1545 ?ord . }
                  OPTIONAL { ?book wdt:P50 ?a . }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul,en-gb". }
                }
            """.trimIndent()
        }

        /**
         * The series of the book titled [title] in a [bookQuery] answer: (series name, number in
         * it). Only a book whose own title is [title] (or [title] starts with it, "Dune (Dune
         * Chronicles 1)"); the best search match ([order]) first; a series with a whole number
         * for it over one without (Harry Potter #1, not "Wizarding World").
         */
        fun seriesOfBook(json: String, title: String, order: List<String> = emptyList(), author: String? = null): Pair<String, Int?>? {
            val rows = runCatching { JSONObject(json).getJSONObject("results").getJSONArray("bindings") }.getOrNull() ?: return null
            val want = norm(title)
            if (want.isEmpty()) return null
            // A same-titled book by someone else isn't it: an author's surname must be in the book's author.
            val authorWords = author?.lowercase()?.split(Regex("[^\\p{L}]+"))?.filter { it.length >= 3 }?.toSet().orEmpty()
            val authorsOf = HashMap<String, MutableSet<String>>()
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val book = r.optJSONObject("book")?.optString("value")?.substringAfterLast('/') ?: continue
                val a = r.optJSONObject("aLabel")?.optString("value")?.lowercase()?.split(Regex("[^\\p{L}]+"))?.lastOrNull { it.length >= 3 } ?: continue
                authorsOf.getOrPut(book) { HashSet() } += a
            }
            fun byAuthor(book: String) = authorWords.isEmpty() || authorsOf[book].isNullOrEmpty() || authorsOf[book]!!.any { it in authorWords }
            class Hit(val book: String, val series: String, val number: Int?)
            val hits = (0 until rows.length()).mapNotNull { i ->
                val r = rows.optJSONObject(i) ?: return@mapNotNull null
                fun v(k: String) = r.optJSONObject(k)?.optString("value")?.trim()
                val book = v("book")?.substringAfterLast('/') ?: return@mapNotNull null
                val label = v("bookLabel")?.let(::norm)?.takeIf { it.length >= 3 } ?: return@mapNotNull null
                if (label != want && !(want.startsWith(label) && label.length >= 4)) return@mapNotNull null
                if (!byAuthor(book)) return@mapNotNull null
                val series = v("seriesLabel")?.takeIf { it.isNotEmpty() && !it.matches(Regex("Q\\d+")) }?.take(120) ?: return@mapNotNull null
                Hit(book, series, v("ord")?.toIntOrNull()?.takeIf { it in 1..999 })
            }
            fun rank(id: String) = order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
            val best = hits.minWithOrNull(compareBy<Hit> { rank(it.book) }.thenBy { if (it.number != null) 0 else 1 }) ?: return null
            return best.series to best.number
        }

        /** Item ids from a wbsearchentities answer, best match first. */
        fun searchIds(json: String): List<String> = runCatching {
            val a = JSONObject(json).optJSONArray("search") ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { id -> id.matches(Regex("Q\\d+")) } }
        }.getOrDefault(emptyList())

        /**
         * The best-matching series (earliest in [order], the search's ranking) that has 2+
         * numbered books in a SPARQL answer. Whole numbers only (no "0.5" prequel
         * novellas); one book per number - the best-known one (most Wikipedia links), so a
         * translation's own item doesn't replace the original; a book with no English title
         * keeps its number with no title. Null if no series has 2+ numbered books.
         */
        fun fromSparql(json: String, order: List<String> = emptyList()): BookSeriesList? {
            val rows = runCatching { JSONObject(json).getJSONObject("results").getJSONArray("bindings") }.getOrNull() ?: return null
            class Hit(val series: String, val number: Int, val title: String?, val links: Int)
            val hits = (0 until rows.length()).mapNotNull { i ->
                val r = rows.optJSONObject(i) ?: return@mapNotNull null
                fun v(k: String) = r.optJSONObject(k)?.optString("value")
                val series = v("series")?.substringAfterLast('/') ?: return@mapNotNull null
                val ord = v("ord")?.trim()?.toIntOrNull()?.takeIf { it in 1..999 } ?: return@mapNotNull null
                val label = v("bookLabel")?.trim()?.takeIf { it.isNotEmpty() && !it.matches(Regex("Q\\d+")) }
                Hit(series, ord, label?.take(200), v("links")?.toIntOrNull() ?: 0)
            }
            val bySeries = hits.groupBy { it.series }.mapValues { (_, h) ->
                h.groupBy { it.number }.map { (n, same) ->
                    val best = same.sortedWith(compareByDescending<Hit> { it.title != null }.thenByDescending { it.links }).first()
                    Entry(n, best.title)
                }.sortedBy { it.number }
            }.filterValues { it.size >= 2 }
            // A franchise item further down the search (every spin-off) doesn't replace the series itself.
            val pick = bySeries.entries.minWithOrNull(
                compareBy<Map.Entry<String, List<Entry>>> { e -> order.indexOf(e.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenByDescending { it.value.size },
            ) ?: return null
            return BookSeriesList(pick.value)
        }
    }
}
