package dev.thorreader.app.metadata

/**
 * Genres are short tags from a clean list. Messy subjects ("Fiction / Fantasy / General",
 * "Juvenile Fiction -- Magic") are mapped to it; long text is a description, never a genre.
 */
object Genres {

    /** Keyword (lowercase, matched as whole words) -> genre. Checked in this order. */
    private val RULES: List<Pair<String, String>> = listOf(
        "science fiction" to "Science Fiction", "sci-fi" to "Science Fiction", "scifi" to "Science Fiction",
        "dystopia" to "Science Fiction", "dystopian" to "Science Fiction", "space opera" to "Science Fiction",
        "cyberpunk" to "Science Fiction", "sci fi" to "Science Fiction",
        "fantasy" to "Fantasy", "magic" to "Fantasy", "wizards" to "Fantasy", "dragons" to "Fantasy",
        "romance" to "Romance", "love stories" to "Romance", "romantic" to "Romance",
        "mystery" to "Mystery", "mysteries" to "Mystery", "detective" to "Mystery", "crime" to "Crime",
        "thriller" to "Thriller", "thrillers" to "Thriller", "suspense" to "Thriller",
        "horror" to "Horror", "ghost stories" to "Horror",
        "historical" to "Historical", "history" to "Historical",
        "literary fiction" to "Literary Fiction", "literary" to "Literary Fiction", "classics" to "Classics",
        "nonfiction" to "Non-fiction", "non-fiction" to "Non-fiction", "non fiction" to "Non-fiction",
        "biography" to "Biography", "autobiography" to "Biography", "memoir" to "Biography", "memoirs" to "Biography",
        "young adult" to "Young Adult", "ya" to "Young Adult", "teen" to "Young Adult", "juvenile fiction" to "Young Adult",
        "children's" to "Children's", "childrens" to "Children's", "children" to "Children's", "kids" to "Children's",
        "picture books" to "Children's",
        "superhero" to "Superhero", "superheroes" to "Superhero", "super heroes" to "Superhero", "super-heroes" to "Superhero",
        "slice of life" to "Slice of Life",
        "drama" to "Drama",
        "comedy" to "Comedy", "humor" to "Comedy", "humour" to "Comedy", "humorous" to "Comedy",
        "action" to "Action",
        "adventure" to "Adventure", "adventure stories" to "Adventure",
        "sports" to "Sports", "sport" to "Sports",
        "art" to "Art", "artists" to "Art", "painting" to "Art",
        "psychological" to "Psychological",
        "supernatural" to "Supernatural", "paranormal" to "Supernatural",
        "mecha" to "Mecha",
        "music" to "Music",
        "war" to "War", "military" to "War",
        "western" to "Western", "westerns" to "Western",
        "poetry" to "Poetry",
        "philosophy" to "Philosophy",
        "self-help" to "Self-help", "self help" to "Self-help",
        "science" to "Science", "mathematics" to "Science",
        "cooking" to "Cooking", "travel" to "Travel", "religion" to "Religion", "business" to "Business",
        "politics" to "Politics", "true crime" to "True Crime", "essays" to "Essays", "short stories" to "Short Stories",
        "ecchi" to "Ecchi", "mahou shoujo" to "Magical Girl", "magical girl" to "Magical Girl", "isekai" to "Isekai",
    )

    /** Already-clean genre names a curated source (AniList) may send as they are. */
    private val CANONICAL: Set<String> = RULES.map { it.second }.toSet()

    /**
     * Is [s] a plausible tag (max 3 words, no sentence punctuation)? Long text and sentences
     * are descriptions.
     */
    fun isTag(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 40) return false
        if (t.split(Regex("\\s+")).size > 3) return false
        return !Regex("[.!?;:\"]").containsMatchIn(t.trimEnd('.'))
    }

    /** Does [s] read like a description rather than a tag? */
    fun isDescription(s: String): Boolean = s.trim().length >= 60 && !isTag(s)

    /** Canonical genres found in one raw subject ("Fiction / Fantasy / General" -> [Fantasy]). */
    fun map(raw: String): List<String> {
        if (isDescription(raw)) return emptyList()
        val out = LinkedHashSet<String>()
        for (part in raw.split('/', ',', ';', '>', '|').map { it.replace("--", " ").trim().lowercase() }) {
            if (part.isEmpty()) continue
            val words = " " + part.replace(Regex("[^a-z0-9'\\- ]"), " ").replace(Regex("\\s+"), " ") + " "
            // Longest keywords first so "science fiction" doesn't also count as "science".
            val hits = RULES.filter { (k, _) -> words.contains(" $k ") }.sortedByDescending { it.first.length }
            val taken = ArrayList<String>()
            for ((k, g) in hits) {
                if (taken.any { it.contains(k) }) continue
                taken += k
                out += g
            }
        }
        return out.toList()
    }

    /**
     * Clean genre list from raw subjects. [trusted] (curated sources like AniList / ComicInfo
     * genre fields) may also keep short tags that aren't in the list.
     */
    fun normalize(raw: List<String>, trusted: Boolean = false): List<String> {
        val out = LinkedHashSet<String>()
        for (r in raw) {
            val mapped = map(r)
            if (mapped.isNotEmpty()) out += mapped
            else if (trusted && isTag(r)) out += r.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
        return out.toList()
    }

    /** The first long text among [raw], as a description. */
    fun descriptionFrom(raw: List<String>): String? = raw.firstOrNull { isDescription(it) }?.trim()

    fun join(genres: List<String>): String? = genres.distinct().joinToString("; ").ifEmpty { null }

    fun split(stored: String?): List<String> = stored?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun isCanonical(g: String) = g in CANONICAL
}
