package dev.thorreader.bookloader.source

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/**
 * All sources: the built-in ones (each can be switched off) and the OPDS feeds the user added,
 * typed in or loaded from a JSON source list. Only data is loaded at runtime (feed addresses),
 * never code.
 */
class SourceRegistry(private val prefs: SharedPreferences) {

    /** Bumped on every change, so screens recompose. */
    var version by mutableIntStateOf(0)
        private set

    private val builtIns: List<SourceProvider> = listOf(
        GutendexSource(),
        MangaDexSource(),
        PepperCarrotSource(),
        OpdsSource(EXAMPLE_FEED),
    )

    fun builtIn(): List<SourceProvider> = builtIns

    fun feeds(): List<OpdsFeed> = runCatching {
        JSONArray(prefs.getString("opds_feeds", "[]")).objects().map(OpdsFeed::fromJson)
    }.getOrDefault(emptyList())

    private val custom = HashMap<String, OpdsSource>()

    /** Every source, enabled or not. */
    fun all(): List<SourceProvider> = builtIns + feeds().map { f -> custom[f.id]?.takeIf { it.feed == f } ?: OpdsSource(f).also { custom[f.id] = it } }

    fun enabled(): List<SourceProvider> = all().filter { isEnabled(it.id) }

    fun get(id: String): SourceProvider? = all().firstOrNull { it.id == id }

    fun isEnabled(id: String) = id !in prefs.getStringSet("disabled_sources", emptySet())!!

    fun setEnabled(id: String, on: Boolean) {
        val s = HashSet(prefs.getStringSet("disabled_sources", emptySet())!!)
        if (on) s -= id else s += id
        prefs.edit().putStringSet("disabled_sources", s).apply()
        version++
    }

    fun addFeed(f: OpdsFeed) {
        val list = feeds().filter { it.url != f.url } + f
        prefs.edit().putString("opds_feeds", JSONArray(list.map { it.toJson() }).toString()).apply()
        version++
    }

    fun removeFeed(id: String) {
        prefs.edit().putString("opds_feeds", JSONArray(feeds().filter { it.id != id }.map { it.toJson() }).toString()).apply()
        custom -= id
        version++
    }

    companion object {
        val EXAMPLE_FEED = OpdsFeed(
            id = "opds-readium-test",
            name = "OPDS 2.0 test catalog (Readium)",
            url = "https://test.opds.io/2.0/home.json",
            type = ContentType.BOOK,
        )

        /** Checks a feed address a user typed: https only, a real host. Null = OK, else why not. */
        fun checkFeedUrl(url: String): String? {
            val u = runCatching { URL(url.trim()) }.getOrNull() ?: return "That isn't a web address"
            if (u.protocol != "https") return "Only https:// addresses are allowed"
            if (u.host.isNullOrBlank()) return "That address has no server name"
            return null
        }

        fun newFeed(name: String, url: String, type: ContentType, user: String? = null, password: String? = null) = OpdsFeed(
            id = "opds-" + UUID.randomUUID().toString().take(8),
            name = name.trim().take(60).ifEmpty { runCatching { URL(url).host }.getOrDefault("OPDS feed") },
            url = url.trim(),
            type = type,
            user = user?.trim()?.ifEmpty { null },
            password = password?.ifEmpty { null },
        )

        /**
         * A JSON source list: {"sources": [{"type": "opds", "name": "...", "url": "https://...",
         * "contentType": "books" | "manga" | "comics"}]} (or just the array). Returns the feeds it
         * defines and how many entries were skipped (unknown type, bad address).
         */
        fun parseSourceList(json: String): Pair<List<OpdsFeed>, Int> {
            val t = json.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("sources") ?: JSONArray()
            var skipped = 0
            val feeds = arr.objects().mapNotNull { s ->
                val url = s.optString("url")
                if (s.optString("type", "opds").lowercase() != "opds" || checkFeedUrl(url) != null) {
                    skipped++
                    return@mapNotNull null
                }
                val type = when (s.optString("contentType").lowercase()) {
                    "book", "books" -> ContentType.BOOK
                    "manga" -> ContentType.MANGA
                    else -> ContentType.COMIC
                }
                newFeed(s.optString("name"), url, type)
            }.take(50)
            return feeds to skipped
        }
    }
}
