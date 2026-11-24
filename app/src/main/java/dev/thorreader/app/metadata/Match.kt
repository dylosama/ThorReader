package dev.thorreader.app.metadata

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Scoring online candidates against what we know about a book. */
object Match {
    /** Apply automatically at or above this score; below it the book "Needs review". */
    const val THRESHOLD = 0.75f

    /** Comic archives NOT flagged as manga only count as manga with a near-exact AniList title. */
    const val STRICT_THRESHOLD = 0.9f

    private val STOP = setOf("the", "a", "an", "of", "and", "vol", "volume", "v")

    /** "The Spectacular Spider-Man!" -> "spectacular spider man". */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ').filter { it.isNotEmpty() && it !in STOP }.joinToString(" ")
    }

    /** 0..1: how alike two titles are (word overlap and spelling). */
    fun similarity(a: String, b: String): Float {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        val wa = x.split(' ').toSet()
        val wb = y.split(' ').toSet()
        val jaccard = (wa intersect wb).size.toFloat() / (wa union wb).size
        val lev = 1f - levenshtein(x, y).toFloat() / max(x.length, y.length)
        return max(jaccard, lev)
    }

    /** Best similarity of [query] to any of [titles]. */
    fun best(query: String, titles: List<String?>): Float = titles.filterNotNull().maxOfOrNull { similarity(query, it) } ?: 0f

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Year bonus: same year full, one year off half. Unknown -> 0. */
    fun yearScore(want: Int?, got: Int?): Float = when {
        want == null || got == null -> 0f
        want == got -> 1f
        abs(want - got) <= 1 -> 0.5f
        else -> -0.5f
    }

    fun clamp(f: Float) = f.coerceIn(0f, 1f)
}
