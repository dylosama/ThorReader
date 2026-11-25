package dev.thorreader.app.metadata

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** What we know about a book, for searching. */
data class Query(
    val title: String,
    val series: String?,
    val volume: Int?,
    val issue: Float?,
    val year: Int?,
    val author: String?,
    val publisher: String?,
    val isbn: String?,
) {
    /** The name to search for: the series if known, else the title. */
    val name: String get() = series?.takeIf { it.isNotBlank() } ?: title
}

/** One possible match from an online source. [score] 0..1 (see [Match]). */
data class Candidate(
    val source: String,
    val id: String,
    val title: String,
    val series: String? = null,
    val number: Float? = null,
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val year: Int? = null,
    val publisher: String? = null,
    val coverUrl: String? = null,
    val isManga: Boolean? = null,
    val country: String? = null,
    val storyArc: String? = null,
    val score: Float = 0f,
    /** ComicVine: id of the issue to fetch details for when this candidate is used. */
    val detailId: String? = null,
    /** Open Library: the work key, for its description. */
    val workKey: String? = null,
)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Plain text from HTML-ish descriptions, shortened to a readable length. */
fun cleanDescription(s: String?): String? {
    if (s.isNullOrBlank()) return null
    val t = s.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
    return if (t.length > 1500) t.take(1500).substringBeforeLast(' ') + "…" else t.ifEmpty { null }
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

// ---- AniList (manga, no key) ---------------------------------------------------------------

object AniList {
    const val URL = "https://graphql.anilist.co"
    private const val GQL = "query (\$s: String) { Page(perPage: 6) { media(search: \$s, type: MANGA) { " +
        "id title { romaji english native } synonyms format countryOfOrigin volumes startDate { year } genres " +
        "tags { name rank isMediaSpoiler } description(asHtml: false) coverImage { large } " +
        "staff(perPage: 10) { edges { role node { name { full } } } } } } }"

    fun body(term: String): String = JSONObject().put("query", GQL).put("variables", JSONObject().put("s", term)).toString()

    fun parse(json: String, q: Query): List<Candidate> {
        val media = JSONObject(json).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        return (0 until media.length()).map { i ->
            val m = media.getJSONObject(i)
            val t = m.optJSONObject("title") ?: JSONObject()
            val english = t.str("english")
            val romaji = t.str("romaji")
            val titles = listOf(english, romaji, t.str("native"))
            val synonyms = m.optJSONArray("synonyms").strings()
            val format = m.str("format")
            val country = m.str("countryOfOrigin")
            val volumes = if (m.isNull("volumes")) null else m.optInt("volumes")
            val staff = m.optJSONObject("staff")?.optJSONArray("edges")
            val people = ArrayList<String>()
            if (staff != null) for (j in 0 until staff.length()) {
                val e = staff.getJSONObject(j)
                val role = e.str("role")?.lowercase() ?: continue
                if ("story" in role || "art" in role || "original creator" in role) {
                    e.optJSONObject("node")?.optJSONObject("name")?.str("full")?.let { people += it }
                }
            }
            val tags = m.optJSONArray("tags")
            val tagNames = ArrayList<String>()
            if (tags != null) for (j in 0 until tags.length()) {
                val tg = tags.getJSONObject(j)
                if (!tg.optBoolean("isMediaSpoiler") && tg.optInt("rank") >= 75) tg.str("name")?.let { tagNames += it }
            }
            val genres = Genres.normalize(m.optJSONArray("genres").strings(), trusted = true) + Genres.normalize(tagNames)
            val isManga = format == "MANGA" || format == "ONE_SHOT" || country in setOf("JP", "KR", "CN")
            // A work whose own title matches beats one that only lists the name among its
            // alternative titles (AniList's top hit for "Blue Period" is "A Bitter Fruit").
            var score = maxOf(Match.best(q.name, titles) * 0.95f, Match.best(q.name, synonyms) * 0.9f)
            if (format == "MANGA" || format == "ONE_SHOT") score += 0.05f
            if (q.volume != null && volumes != null && volumes > 0 && q.volume > volumes) score -= 0.2f
            Candidate(
                source = "AniList", id = m.optInt("id").toString(), title = english ?: romaji ?: "?", series = english ?: romaji,
                number = q.volume?.toFloat() ?: q.issue, authors = people.distinct(), genres = genres.distinct(),
                description = cleanDescription(m.str("description")), year = m.optJSONObject("startDate")?.let { if (it.isNull("year")) null else it.optInt("year") },
                coverUrl = m.optJSONObject("coverImage")?.str("large"), isManga = isManga, country = country, score = Match.clamp(score),
            )
        }.sortedByDescending { it.score }
    }
}

// ---- ComicVine (comics, the user's own key) -------------------------------------------------

object ComicVine {
    private const val BASE = "https://comicvine.gamespot.com/api"

    /** URL with the key, and the same URL without it (for the cache and the log). */
    fun volumeSearch(key: String, series: String): Pair<String, String> {
        val rest = "/search/?format=json&resources=volume&limit=10&query=${enc(series)}" +
            "&field_list=id,name,start_year,publisher,count_of_issues,image,deck"
        return "$BASE$rest&api_key=${enc(key)}" to "$BASE$rest"
    }

    fun issueSearch(key: String, volumeId: String, issue: String): Pair<String, String> {
        val rest = "/issues/?format=json&filter=volume:$volumeId,issue_number:${enc(issue)}" +
            "&field_list=id,name,issue_number,cover_date,image,deck"
        return "$BASE$rest&api_key=${enc(key)}" to "$BASE$rest"
    }

    fun issueDetails(key: String, issueId: String): Pair<String, String> {
        val rest = "/issue/4000-$issueId/?format=json" +
            "&field_list=name,issue_number,cover_date,image,deck,description,person_credits,story_arc_credits,concept_credits,volume"
        return "$BASE$rest&api_key=${enc(key)}" to "$BASE$rest"
    }

    /** ComicVine reports problems inside the JSON (status_code 100 = bad key, 107 = rate limit). */
    fun check(json: String): JSONObject {
        val o = JSONObject(json)
        when (o.optInt("status_code", 1)) {
            1 -> Unit
            100 -> throw BadKeyException("ComicVine: invalid API key")
            107 -> throw RetryLaterException("ComicVine rate limit")
            else -> throw java.io.IOException("ComicVine: ${o.str("error") ?: "error ${o.optInt("status_code")}"}")
        }
        return o
    }

    fun parseVolumes(json: String, q: Query): List<Candidate> {
        val r = check(json).optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { i ->
            val v = r.getJSONObject(i)
            val name = v.str("name") ?: "?"
            val year = v.str("start_year")?.toIntOrNull()
            val count = v.optInt("count_of_issues", 0)
            val publisher = v.optJSONObject("publisher")?.str("name")
            var score = Match.similarity(q.name, name) * 0.75f + 0.15f * Match.yearScore(q.year, year)
            val issue = q.issue
            if (issue != null && count > 0) score += if (issue <= count) 0.1f else -0.15f
            if (q.publisher != null && publisher != null && Match.similarity(q.publisher, publisher) > 0.8f) score += 0.05f
            Candidate(
                source = "ComicVine", id = v.optInt("id").toString(),
                title = name + (issue?.let { " #" + fmt(it) } ?: ""), series = name, number = issue,
                year = year, publisher = publisher, coverUrl = v.optJSONObject("image")?.str("small_url"),
                description = cleanDescription(v.str("deck")), isManga = false, score = Match.clamp(score),
                genres = superhero(publisher),
            )
        }.sortedWith(
            // "v1" in the file name: among equally good matches, the earliest series (volume 1).
            compareByDescending<Candidate> { it.score }.thenBy { if (q.volume == 1) it.year ?: 9999 else 0 },
        )
    }

    fun firstIssueId(json: String): String? = check(json).optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()

    /** Fill writer/artist, story arc, genres and description from an issue's details. */
    fun withDetails(c: Candidate, json: String): Candidate {
        val r = check(json).optJSONObject("results") ?: return c
        val writers = ArrayList<String>()
        val artists = ArrayList<String>()
        r.optJSONArray("person_credits")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                val name = p.str("name") ?: continue
                val role = p.str("role")?.lowercase() ?: ""
                if ("writer" in role) writers += name
                if ("penciler" in role || "penciller" in role || "artist" in role) artists += name
            }
        }
        val arc = r.optJSONArray("story_arc_credits")?.optJSONObject(0)?.str("name")
        val concepts = r.optJSONArray("concept_credits")?.let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).str("name") } }.orEmpty()
        return c.copy(
            authors = (writers + artists).distinct().ifEmpty { c.authors },
            storyArc = arc ?: c.storyArc,
            genres = (c.genres + Genres.normalize(concepts)).distinct(),
            description = cleanDescription(r.str("description")) ?: cleanDescription(r.str("deck")) ?: c.description,
            year = r.str("cover_date")?.take(4)?.toIntOrNull() ?: c.year,
            coverUrl = r.optJSONObject("image")?.str("small_url") ?: c.coverUrl,
        )
    }

    /** Marvel and DC comics are superhero comics unless told otherwise. */
    private fun superhero(publisher: String?): List<String> =
        if (publisher != null && Regex("(?i)\\b(marvel|dc comics)\\b").containsMatchIn(publisher)) listOf("Superhero") else emptyList()

    private fun fmt(f: Float) = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
}

// ---- Open Library (books, no key) -----------------------------------------------------------

object OpenLibrary {
    private const val FIELDS = "key,title,author_name,first_publish_year,subject,cover_i,publisher"

    fun search(q: Query): String = when {
        q.isbn != null -> "https://openlibrary.org/search.json?q=isbn:${enc(q.isbn)}&limit=1&fields=$FIELDS"
        q.author != null -> "https://openlibrary.org/search.json?title=${enc(q.title)}&author=${enc(q.author)}&limit=6&fields=$FIELDS"
        else -> "https://openlibrary.org/search.json?title=${enc(q.title)}&limit=6&fields=$FIELDS"
    }

    fun work(key: String) = "https://openlibrary.org$key.json"

    fun parse(json: String, q: Query): List<Candidate> {
        val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val title = d.str("title") ?: "?"
            val authors = d.optJSONArray("author_name").strings()
            val score = when {
                q.isbn != null -> 1f
                q.author != null -> Match.similarity(q.title, title) * 0.65f + Match.best(q.author, authors) * 0.35f
                else -> Match.similarity(q.title, title) * 0.8f
            }
            Candidate(
                source = "Open Library", id = d.str("key") ?: "", title = title, authors = authors.take(4),
                genres = Genres.normalize(d.optJSONArray("subject").strings().take(40)).take(5),
                year = if (d.isNull("first_publish_year")) null else d.optInt("first_publish_year"),
                publisher = d.optJSONArray("publisher").strings().firstOrNull(),
                coverUrl = if (d.isNull("cover_i")) null else "https://covers.openlibrary.org/b/id/${d.optLong("cover_i")}-M.jpg",
                isManga = false, score = Match.clamp(score), workKey = d.str("key"),
            )
        }.sortedByDescending { it.score }
    }

    /** A work's description is a string or {"value": "..."}. */
    fun description(json: String): String? {
        val o = JSONObject(json)
        val d = o.opt("description") ?: return null
        return cleanDescription(if (d is JSONObject) d.str("value") else d.toString())
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
