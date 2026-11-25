package dev.thorreader.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.Opds
import dev.thorreader.app.opds.Catalog
import kotlinx.coroutines.launch

/**
 * Browsing an OPDS catalog over the library: folders (› opens), books with their cover, author,
 * a line of description and a Download button (the best format; it lands in Download/Thor
 * Reader and in the library). Search when the catalog offers it; "Load more" for further pages.
 * Back / B goes up a folder, then closes.
 */
@Composable
fun CatalogBrowser(c: Catalog) {
    val app = ThorApp.instance
    val scope = rememberCoroutineScope()
    data class Page(val url: String, val title: String)
    val stack = remember(c.id) { mutableStateListOf(Page(c.url, c.name)) }
    var entries by remember { mutableStateOf<List<Opds.Entry>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var template by remember(c.id) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // Per book: 0..1 while downloading (-1 size unknown), DONE when added.
    val progress = remember { mutableStateMapOf<String, Float>() }
    val failed = remember { mutableStateMapOf<String, String>() }
    val listState = rememberLazyListState()

    fun close() {
        app.browsing = null
    }

    // Back / B: up one folder, then close.
    DisposableEffect(stack.size) {
        val prev = app.libraryBack
        app.libraryBack = {
            if (app.typing == null) {
                if (stack.size > 1) stack.removeAt(stack.size - 1) else close()
            }
            true
        }
        onDispose { app.libraryBack = prev }
    }

    val page = stack.last()
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(page, stack.size, retry) {
        loading = true
        error = null
        entries = emptyList()
        next = null
        try {
            val f = app.catalogs.feed(c, page.url)
            entries = f.entries
            next = f.next
            if (template == null) template = app.catalogs.searchTemplate(c, f)
            listState.scrollToItem(0)
        } catch (e: java.io.IOException) {
            error = e.message
        } finally {
            loading = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .clip(HouseStyle.PanelShape)
                .background(HouseStyle.PanelBackground)
                .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stack.size > 1) Chip("‹ Back") { stack.removeAt(stack.size - 1) }
                Column(Modifier.weight(1f)) {
                    Text(page.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.name + " · " + c.host, color = HouseStyle.Subtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Chip("✕ Close") { close() }
            }
            template?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    fun search() {
                        if (query.isNotBlank()) stack += Page(Opds.searchUrl(t, query), "Search: ${query.trim()}")
                    }
                    TypingField(
                        query, { query = it },
                        Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A2A))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        hint = "Search this catalog", onDone = ::search,
                    )
                    Chip("Search", query.isNotBlank()) { search() }
                }
            }
            when {
                loading && entries.isEmpty() -> HouseProgressBadge("Loading…")
                error != null -> {
                    HouseBadge(error!!, color = HouseStyle.Warning)
                    Chip("Try again") { retry++ }
                }
                entries.isEmpty() -> Text("Nothing here.", color = HouseStyle.Subtle, fontSize = 14.sp)
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(entries, key = { it.id + it.title + (it.navigation?.href ?: "") }) { e ->
                    if (e.isBook) {
                        BookEntryRow(c, e, progress[e.id], failed[e.id]) {
                            val link = e.best ?: return@BookEntryRow
                            failed.remove(e.id)
                            progress[e.id] = 0f
                            scope.launch {
                                val err = app.catalogs.download(c, e, link) { p -> progress[e.id] = p }
                                if (err == null) progress[e.id] = DONE else {
                                    progress.remove(e.id)
                                    failed[e.id] = err
                                }
                            }
                        }
                    } else {
                        e.navigation?.let { nav -> FolderRow(e) { stack += Page(nav.href, e.title) } }
                    }
                }
                next?.let { more ->
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                            Chip(if (loading) "Loading…" else "Load more") {
                                if (loading) return@Chip
                                loading = true
                                scope.launch {
                                    try {
                                        val f = app.catalogs.feed(c, more)
                                        entries = entries + f.entries
                                        next = f.next
                                    } catch (ex: java.io.IOException) {
                                        error = ex.message
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DONE = 2f

@Composable
private fun FolderRow(e: Opds.Entry, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .focusClick(RoundedCornerShape(10.dp), onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.summary?.let { Text(it, color = SubtleText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text("›", color = SubtleText, fontSize = 22.sp)
    }
}

@Composable
private fun BookEntryRow(c: Catalog, e: Opds.Entry, progress: Float?, failed: String?, onDownload: () -> Unit) {
    val app = ThorApp.instance
    val widthPx = with(LocalDensity.current) { 52.dp.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, e.thumbnail) {
        value = e.thumbnail?.let { app.catalogs.thumbnail(c, it, widthPx)?.asImageBitmap() }
    }
    // Downloaded before and still in the library (removing it brings the Download button back).
    val inLibrary by produceState(false, e.id, progress) { value = app.catalogs.inLibrary(c, e) }
    val got = progress == DONE || (progress == null && inLibrary)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp, 76.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333))) {
            thumb?.let { Image(it, e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.authors.isNotEmpty()) Text(e.authors.joinToString(", "), color = SubtleText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.summary?.let { Text(it, color = Color(0xFF8A8A8A), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            failed?.let { Text(it, color = HouseStyle.Warning, fontSize = 12.sp, maxLines = 2) }
        }
        val fmt = e.best?.let { Opds.extensionFor(it)?.uppercase() } ?: ""
        when {
            progress != null && progress != DONE ->
                Text(if (progress < 0f) "Downloading…" else "Downloading ${(progress * 100).toInt()} %", color = SubtleText, fontSize = 13.sp)
            got -> Chip("In library ✓", false) {}
            else -> Chip("Download $fmt".trim(), true, onDownload)
        }
    }
}
