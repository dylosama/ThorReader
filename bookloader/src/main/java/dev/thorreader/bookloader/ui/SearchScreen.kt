package dev.thorreader.bookloader.ui

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.LocalHost
import dev.thorreader.bookloader.Screen
import dev.thorreader.bookloader.TAG
import dev.thorreader.bookloader.source.CatalogItem
import dev.thorreader.bookloader.source.ContentType
import dev.thorreader.bookloader.source.SourceProvider
import dev.thorreader.bookloader.work.Jobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The search screen's state; kept by the activity, so it is still there after Back. */
class SearchState {
    var query by mutableStateOf("")
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
            error = "No source for this type is switched on (see Sources)."
            return
        }
        sourceId = src.id
        job?.cancel()
        loading = true
        error = null
        loadedOnce = true
        val q = query
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
    val jobsFlow = androidx.compose.runtime.remember { WorkManager.getInstance(app).getWorkInfosByTagFlow(Jobs.TAG_JOB) }
    val jobs by jobsFlow.collectAsStateSafe()
    val active = jobs.count { !it.state.isFinished }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // --- title row ---
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Thor Bookloader", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(if (active > 0) "Queue · $active" else "Queue", selected = active > 0) { host.go(Screen.Queue) }
            Chip("Sources") { host.go(Screen.Sources) }
        }
        // --- search bar ---
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(
                s.query, { s.query = it },
                placeholder = "Search ${s.active()?.name ?: ""} (title or author)",
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Search,
                onDone = { s.folders = emptyList(); s.run(scope) },
            )
            Chip("Search", selected = true) { s.folders = emptyList(); s.run(scope) }
        }
        // --- type filter ---
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Type", color = House.Faint, fontSize = 13.sp)
            Chip("All", s.type == null) { s.type = null; s.folders = emptyList(); s.run(scope) }
            ContentType.entries.forEach { t -> Chip(t.label, s.type == t) { s.type = t; s.folders = emptyList(); s.run(scope) } }
            Spacer(Modifier.width(12.dp))
            Text("Source", color = House.Faint, fontSize = 13.sp)
            s.sources().forEach { src ->
                Chip(src.name, s.active()?.id == src.id) { s.sourceId = src.id; s.folders = emptyList(); s.run(scope) }
            }
        }
        // --- OPDS folder path ---
        if (s.folders.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("←") { s.folders = s.folders.dropLast(1); s.run(scope) }
                Text(s.folders.joinToString(" › ") { it.first }, color = House.Subtle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            s.loading -> Message("Loading…")
            s.error != null -> Message(s.error!!, House.Warning)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(s.results, key = { it.sourceId + it.id }) { item ->
                ResultRow(item, app.inThorReader(item.sourceId, item.id), s.active()?.name ?: "") {
                    if (item.folderUrl != null) {
                        s.folders = s.folders + (item.title to item.folderUrl)
                        s.query = ""
                        s.run(scope)
                    } else {
                        host.openItem(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: CatalogItem, inThor: Boolean, sourceName: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .thorClick(RoundedCornerShape(12.dp), onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (item.folderUrl != null) {
            Text("▸", color = House.Accent, fontSize = 22.sp, modifier = Modifier.width(54.dp).padding(start = 18.dp))
        } else {
            Cover(item.coverUrl, Modifier.size(54.dp, 80.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (inThor) Badge("In Thor Reader")
            }
            if (item.folderUrl == null) {
                Text(
                    (item.authors.take(3).joinToString(", ").ifEmpty { "Unknown author" }) + "  ·  ${item.type.label}  ·  $sourceName",
                    color = House.Subtle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                item.summary?.let { Text(it.replace('\n', ' '), color = House.Faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else {
                Text("Open", color = House.Faint, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}

/** WorkManager's flow as Compose state (empty until the first value). */
@Composable
fun kotlinx.coroutines.flow.Flow<List<WorkInfo>>.collectAsStateSafe() =
    androidx.compose.runtime.produceState(emptyList<WorkInfo>(), this) { this@collectAsStateSafe.collect { value = it } }
