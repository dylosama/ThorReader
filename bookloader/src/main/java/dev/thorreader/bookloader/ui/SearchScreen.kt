package dev.thorreader.bookloader.ui

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.LocalHost
import dev.thorreader.bookloader.TAG
import dev.thorreader.bookloader.source.CatalogItem
import dev.thorreader.bookloader.source.ContentType
import dev.thorreader.bookloader.source.SourceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The search screen's state; kept by the activity, so it is still there after Back. */
class SearchState {
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    /** Start typing as soon as the search pill appears (only right after the search button). */
    var autoType = false
    /** The text of the last search that ran (typing ending without changes doesn't search again). */
    var lastQuery = ""
    var type by mutableStateOf<ContentType?>(null)
    var sourceId by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<CatalogItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** OPDS folders opened so far (title, url). */
    var folders by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var loadedOnce = false
    private var job: Job? = null

    fun sources(): List<SourceProvider> {
        val t = type
        return BookloaderApp.instance.sources.enabled().filter { t == null || t in it.types }
    }

    fun active(): SourceProvider? = sources().let { list -> list.firstOrNull { it.id == sourceId } ?: list.firstOrNull() }

    fun run(scope: CoroutineScope) {
        val src = active() ?: run {
            results = emptyList()
            error = "No source for this type is switched on (⚙ → Sources)."
            return
        }
        sourceId = src.id
        job?.cancel()
        loading = true
        error = null
        loadedOnce = true
        val q = query
        lastQuery = q.trim()
        results = emptyList() // no old results under "Loading…"
        val t = type
        val folder = folders.lastOrNull()?.second
        job = scope.launch {
            try {
                results = withContext(Dispatchers.IO) { src.search(q, t, folder) }
                if (results.isEmpty()) error = if (q.isBlank()) "Nothing here." else "Nothing found for \"$q\" in ${src.name}."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search in ${src.name} failed", e)
                results = emptyList()
                error = "${src.name}: ${e.message ?: "couldn't connect"}"
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun SearchScreen(s: SearchState) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val app = BookloaderApp.instance
    @Suppress("UNUSED_VARIABLE") val v = app.sources.version // recompose when sources change
    LaunchedEffect(Unit) { if (!s.loadedOnce) s.run(scope) }
    fun fresh() {
        s.folders = emptyList()
        s.run(scope)
    }

    /** "All" in the search pill: back to the home state. */
    fun home() {
        app.stopTyping("All (home)")
        s.searchOpen = false
        s.autoType = false
        s.query = ""
        s.type = null
        fresh()
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Logo()
        Spacer(Modifier.height(6.dp))
        // --- top bar: status · All | Books | Manga | Comics + search · Queue | ♪ | gear ---
        TopBar {
            AnimatedContent(
                targetState = s.searchOpen,
                transitionSpec = {
                    (fadeIn(tween(220)) + expandHorizontally(tween(260), expandFrom = Alignment.CenterHorizontally)) togetherWith
                        (fadeOut(tween(160)) + shrinkHorizontally(tween(220), shrinkTowards = Alignment.CenterHorizontally)) using
                        SizeTransform(clip = false)
                },
                label = "search",
            ) { open ->
                if (open) {
                    SearchPill(s, onSearch = {
                        // Typing ended (Done, Enter, a tap outside...): search if the text changed.
                        s.autoType = false
                        if (s.query.trim() != s.lastQuery) fresh()
                    }, onAll = { home() })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TypeSwitch(s.type) { s.type = it; fresh() }
                        RoundIcon({ s.searchOpen = true; s.autoType = true }) { MagnifierIcon() }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // --- the source row (Thor Reader's sort row) ---
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            items(s.sources(), key = { it.id }) { src -> RowChip(src.name, s.active()?.id == src.id) { s.sourceId = src.id; fresh() } }
        }
        Spacer(Modifier.height(8.dp))
        if (s.folders.isNotEmpty() || s.query.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.folders.isNotEmpty()) {
                    Chip("←") { s.folders = s.folders.dropLast(1); s.run(scope) }
                    Text(s.folders.joinToString(" › ") { it.first }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else {
                    Text("Results for \"${s.query}\"", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                if (!s.loading) Text("${s.results.size} found", color = House.Faint, fontSize = 13.sp)
            }
        }
        when {
            s.loading -> Message("Loading…")
            s.error != null -> Message(s.error!!, House.Warning)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(s.results, key = { it.sourceId + it.id }) { item ->
                ResultRow(item, app.inThorReader(item.sourceId, item.id), s.active()?.name ?: "") {
                    if (item.folderUrl != null) {
                        s.folders = s.folders + (item.title to item.folderUrl)
                        s.run(scope)
                    } else {
                        host.openItem(item)
                    }
                }
            }
        }
    }
}

/** Thor Reader's Library | Completed switch, here for the content type. */
@Composable
private fun TypeSwitch(current: ContentType?, onSelect: (ContentType?) -> Unit) {
    Row(
        Modifier
            .clip(House.Pill)
            .background(Color(0xFF1E1E1E))
            .border(1.dp, House.Hairline, House.Pill)
            .padding(3.dp),
    ) {
        (listOf<ContentType?>(null) + ContentType.entries).forEach { t ->
            val selected = t == current
            Text(
                t?.label ?: "All",
                color = if (selected) House.OnAccent else Color.White,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(House.Pill)
                    .background(if (selected) House.Accent else Color.Transparent)
                    .thorClick(House.Pill) { onSelect(t) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Search mode: one pill (the type switch's look) - "All" on the left goes back home, the search
 * text on the right (tap it to type again).
 */
@Composable
private fun SearchPill(s: SearchState, onSearch: () -> Unit, onAll: () -> Unit) {
    Row(
        Modifier
            .clip(House.Pill)
            .background(Color(0xFF1E1E1E))
            .border(1.dp, House.Hairline, House.Pill)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "All",
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(House.Pill)
                .thorClick(House.Pill, onClick = onAll)
                .padding(horizontal = 16.dp, vertical = 7.dp),
        )
        Row(
            Modifier.width(420.dp).clip(House.Pill).background(House.Chip).padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MagnifierIcon()
            Spacer(Modifier.width(8.dp))
            Field(
                s.query, { s.query = it },
                placeholder = "Search ${s.active()?.name ?: ""}: title or author",
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Search,
                onDone = onSearch,
                bare = true,
                autoFocus = s.autoType,
            )
        }
    }
}

/** "Thor Bookloader": the app's mark and wordmark, above the top bar on the home screen. */
@Composable
private fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF17181C)).border(1.dp, House.Hairline, RoundedCornerShape(9.dp))) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(dev.thorreader.bookloader.R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f),
            )
        }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, fontWeight = FontWeight.Bold))
                append("Thor ")
                pop()
                pushStyle(androidx.compose.ui.text.SpanStyle(color = House.Accent, fontWeight = FontWeight.SemiBold))
                append("Bookloader")
                pop()
            },
            fontSize = 22.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

/** Thor Reader's book row: cover, bold title, grey author line, small detail line, square button. */
@Composable
private fun ResultRow(item: CatalogItem, inThor: Boolean, sourceName: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(House.Row)
            .thorClick(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.folderUrl != null) {
            Box(Modifier.size(56.dp, 80.dp).clip(RoundedCornerShape(6.dp)).background(House.Chip), contentAlignment = Alignment.Center) {
                Text("▤", color = House.Accent, fontSize = 26.sp)
            }
        } else {
            Cover(item.coverUrl, Modifier.size(56.dp, 80.dp), item.altCoverUrl)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.folderUrl == null) {
                val by = item.authors.take(3).joinToString(", ")
                if (by.isNotEmpty()) Text(by, color = House.Byline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.type.label, sourceName, item.summary?.replace('\n', ' ')).joinToString(" · "),
                    color = House.Faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text("Folder · $sourceName", color = House.Faint, fontSize = 13.sp)
            }
        }
        // The square button on the right (where Thor Reader shows a book's mode).
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x99000000))
                .border(1.dp, if (inThor) House.Ok else House.Hairline, RoundedCornerShape(12.dp))
                .thorClick(RoundedCornerShape(12.dp), onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                item.folderUrl != null -> Text("›", color = Color.White, fontSize = 26.sp)
                inThor -> Text("✓", color = House.Ok, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                else -> QueueIcon()
            }
        }
    }
}

/** WorkManager's flow as Compose state (empty until the first value). */
@Composable
fun kotlinx.coroutines.flow.Flow<List<WorkInfo>>.collectAsStateSafe() =
    androidx.compose.runtime.produceState(emptyList<WorkInfo>(), this) { this@collectAsStateSafe.collect { value = it } }
