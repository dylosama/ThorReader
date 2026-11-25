package dev.thorreader.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.engine.MangaCollection
import dev.thorreader.app.engine.MangaCollection.ChapterRow
import dev.thorreader.app.engine.MangaCollection.GapRow
import dev.thorreader.app.engine.MangaCollection.VolumeRow
import dev.thorreader.app.engine.MangaSeriesMap
import dev.thorreader.app.engine.MangaUnits
import kotlin.math.abs
import kotlin.math.min

// ---- the collection (shared by the library's collection screen and Inspect) --------------------

/** The collection of [series] from [books]; its chapter map is fetched (once) in the background. */
@Composable
fun rememberMangaCollection(series: String, books: List<Book>): Pair<MangaCollection.Collection, Boolean> {
    val app = ThorApp.instance
    val key = seriesKey(series)
    val manga = remember(books, series) { MangaShelf.isManga(series, books) }
    if (!manga) {
        // A book series (Dune 1-6): its numbered books from Wikidata.
        LaunchedEffect(key) { app.bookSeries.list(series) }
        val list = key?.let { app.bookSeries.lists[it] }
        val loading = key != null && !app.bookSeries.lists.containsKey(key) && app.settings.lookupOnline
        val owned = remember(books, series) { MangaShelf.ownedBooks(series, books) }
        return remember(owned, list) { dev.thorreader.app.engine.BookCollection.build(owned, list) } to loading
    }
    LaunchedEffect(key) { app.mangaSeries.map(series) }
    val map = key?.let { app.mangaSeries.maps[it] }
    val loading = key != null && !app.mangaSeries.maps.containsKey(key) && app.settings.lookupOnline
    val owned = remember(books, series) { MangaShelf.owned(series, books) }
    return remember(owned, map) { MangaCollection.build(owned, map) } to loading
}

private val Ongoing = Color(0xFF5BC36B)
private val Ended = Color(0xFFE5534B)
private val Paused = Color(0xFFE0A84A)

/** "Chapters 1– Ongoing" (green) or "Chapters 1–97 Completed" (red), and what the user has. */
@Composable
fun CollectionInfoLine(c: MangaCollection.Collection, loading: Boolean) {
    if (c.isBooks) {
        // "Books 1–6" and what the user has: "In your library: 1 of 6".
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Books 1–", color = Color.White, fontSize = 15.sp)
                Text(
                    c.seriesBooks?.toString() ?: if (loading) "looking up…" else "?",
                    color = if (c.seriesBooks != null) Color.White else SubtleText, fontSize = 15.sp,
                    fontWeight = if (c.seriesBooks != null) FontWeight.Bold else FontWeight.Normal,
                )
            }
            val of = c.seriesBooks?.let { " of $it" } ?: ""
            Text("In your library: ${c.books}$of book${if (c.books == 1 && of.isEmpty()) "" else "s"}", color = SubtleText, fontSize = 13.sp)
        }
        return
    }
    val last = c.lastChapter?.let(MangaUnits::fmt)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chapters 1–", color = Color.White, fontSize = 15.sp)
            when (c.status) {
                MangaSeriesMap.Status.ONGOING -> {
                    Text("Ongoing", color = Ongoing, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    last?.let { Text("   · newest Ch. $it", color = SubtleText, fontSize = 13.sp) }
                }
                MangaSeriesMap.Status.COMPLETED -> Text("${last ?: "?"} Completed", color = Ended, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                MangaSeriesMap.Status.HIATUS -> Text("On hiatus", color = Paused, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                MangaSeriesMap.Status.CANCELLED -> Text("${last ?: "?"} Cancelled", color = Ended, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                null -> Text(if (loading) "looking up…" else (last ?: "?"), color = SubtleText, fontSize = 15.sp)
            }
        }
        val parts = listOfNotNull(
            c.volumes.takeIf { it > 0 }?.let { "$it volume${if (it == 1) "" else "s"}" },
            c.chapters.takeIf { it > 0 }?.let { "$it chapter${if (it == 1) "" else "s"}" },
        )
        Text("In your library: " + parts.joinToString(" · ").ifEmpty { "nothing yet" }, color = SubtleText, fontSize = 13.sp)
    }
}

private fun rangeText(from: Float?, to: Float?): String = when {
    from == null && to != null -> "Ch. 1–${MangaUnits.fmt(to)}"
    from != null && to != null && from == to -> "Ch. ${MangaUnits.fmt(from)}"
    from != null && to != null -> "Ch. ${MangaUnits.fmt(from)}–${MangaUnits.fmt(to)}"
    else -> "Chapters"
}

private fun volumesText(v: IntRange?) = v?.let { if (it.first == it.last) "Vol. ${it.first}" else "Vol. ${it.first}–${it.last}" }

/** The title and the line under it for a row. */
private fun texts(r: MangaCollection.Row, status: MangaSeriesMap.Status?): Pair<String, String> = when (r) {
    is VolumeRow -> "Vol. ${r.volume}" to (r.chapters?.takeIf { it.isNotEmpty() }?.let { rangeText(it.first(), it.last()) } ?: "Chapters not listed yet")
    is ChapterRow -> "Ch. ${MangaUnits.fmt(r.chapter)}" to (r.volume?.let { "in Vol. $it" } ?: "Single chapter")
    is GapRow -> {
        val head = listOfNotNull(volumesText(r.volumes), if (r.from == null) "Earlier chapters" else rangeText(r.from, r.to)).joinToString(" · ")
        val more = if (r.tail && status == MangaSeriesMap.Status.ONGOING) " · more to come" else ""
        head to ("not in your library" + (r.count?.let { " · $it chapter${if (it == 1) "" else "s"}" } ?: "") + more)
    }
    is MangaCollection.BookRow -> r.title to (r.number?.let { "Book $it" } ?: "In the series")
    is MangaCollection.MissingBooksRow -> {
        val n = if (r.from == r.to) "Book ${r.from}" else "Books ${r.from}–${r.to}"
        (r.title ?: n) to (if (r.title != null) "$n · not in your library" else "not in your library")
    }
}

private fun ownedId(r: MangaCollection.Row): Long? = when (r) {
    is VolumeRow -> r.bookId
    is ChapterRow -> r.bookId
    is MangaCollection.BookRow -> r.bookId
    is GapRow, is MangaCollection.MissingBooksRow -> null
}

/** A small cover for [book] (about [widthPx] wide), the user's own picture first. */
@Composable
private fun SmallCover(book: Book, widthPx: Int, modifier: Modifier) {
    val path = dev.thorreader.app.io.CustomCovers.frontPath(book)
    val img by produceState<ImageBitmap?>(path?.let { CoverThumbs.cached(it, widthPx) }?.asImageBitmap(), path, widthPx) {
        value = path?.let { CoverThumbs.load(it, widthPx)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A))) {
        img?.let { Image(it, book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: BookCover(book, Modifier.fillMaxSize())
    }
}

/** "⋯": the book's own library menu (opens in, edit info, refresh, remove), beside the button. */
@Composable
private fun BookMenuButton(book: Book) {
    val app = ThorApp.instance
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("⋯") { open = true }
        if (open) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = BesideAnchor,
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                BookPopup(book, book.readingMode ?: app.settings.lastBookMode) { open = false }
            }
        }
    }
}

/** The list: owned volumes / chapters (cover, range, Read), the gaps dimmed. */
@Composable
fun CollectionList(
    c: MangaCollection.Collection,
    books: Map<Long, Book>,
    highlight: Long?,
    onOpen: (Book) -> Unit,
    onRead: (Book) -> Unit,
    modifier: Modifier = Modifier,
    /** The book's own menu (Remove, Edit info, Opens in…) behind a "⋯" on each row; off in Inspect. */
    withMenu: Boolean = false,
) {
    val coverPx = with(LocalDensity.current) { 44.dp.roundToPx() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(c.rows, key = { i, r -> "$i:$r" }) { _, r ->
            val (title, sub) = texts(r, c.status)
            val book = ownedId(r)?.let { books[it] }
            if (book != null) {
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (book.id == highlight) Accent.copy(alpha = 0.16f) else Color(0xFF1E1E1E))
                        .border(if (book.id == highlight) 2.dp else 0.dp, if (book.id == highlight) Accent else Color.Transparent, shape)
                        .focusClick(shape) { onOpen(book) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SmallCover(book, coverPx, Modifier.size(44.dp, 64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(sub, color = SubtleText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (book.progress > 0f && !book.completed) Text("${(book.progress * 100).toInt()} % read", color = SubtleText, fontSize = 12.sp)
                        if (book.completed) Text("Finished", color = Gold, fontSize = 12.sp)
                    }
                    if (withMenu) BookMenuButton(book)
                    Chip("Read", true) { onRead(book) }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x14FFFFFF))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp, 22.dp), contentAlignment = Alignment.Center) { Text("⋯", color = Color(0xFF6A6A6A), fontSize = 18.sp) }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color(0xFF9A9A9A), fontSize = 14.sp)
                        Text(sub, color = Color(0xFF6E6E6E), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Cover flow: the series left to right, the middle cover large and facing you, the others
 * smaller and turned away; missing stretches as empty cards. Swipe or d-pad; tap the middle one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverFlow(c: MangaCollection.Collection, books: Map<Long, Book>, onOpen: (Book) -> Unit, onRead: (Book) -> Unit) {
    val state = rememberLazyListState()
    val cardW = 150.dp
    val cardH = 225.dp
    val coverPx = with(LocalDensity.current) { cardW.roundToPx() }
    val centered by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: 0
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(cardH + 24.dp)) {
            val side = ((maxWidth - cardW) / 2).coerceAtLeast(0.dp)
            LazyRow(
                state = state,
                flingBehavior = rememberSnapFlingBehavior(state),
                contentPadding = PaddingValues(horizontal = side),
                horizontalArrangement = Arrangement.spacedBy((-28).dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(c.rows, key = { i, r -> "$i:$r" }) { i, r ->
                    val book = ownedId(r)?.let { books[it] }
                    val (title, sub) = texts(r, c.status)
                    Box(
                        Modifier
                            .size(cardW, cardH)
                            .graphicsLayer {
                                // Distance from the middle, in cards (read at draw time: no recomposition).
                                val info = state.layoutInfo
                                val it = info.visibleItemsInfo.firstOrNull { v -> v.index == i }
                                val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                val d = if (it == null) 0f else ((it.offset + it.size / 2f) - mid) / it.size
                                val ad = min(abs(d), 2f)
                                scaleX = 1f - 0.22f * min(ad, 1f)
                                scaleY = scaleX
                                rotationY = -d.coerceIn(-1f, 1f) * 42f
                                cameraDistance = 14f * density
                                alpha = 1f - 0.35f * min(ad, 1.5f) / 1.5f
                                // The middle card on top of its neighbours.
                                translationX = -d.coerceIn(-1f, 1f) * 6.dp.toPx()
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (book != null) Color(0xFF222222) else Color(0xFF151515))
                            .border(1.dp, if (book != null) Color(0x33FFFFFF) else Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (book != null) onOpen(book)
                            },
                    ) {
                        if (book != null) {
                            SmallCover(book, coverPx, Modifier.fillMaxSize())
                            Text(
                                title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        } else {
                            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(title, color = Color(0xFF8A8A8A), fontSize = 15.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                                Text(sub, color = Color(0xFF666666), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
        // The middle one: what it is, and Read.
        c.rows.getOrNull(centered)?.let { r ->
            val (title, sub) = texts(r, c.status)
            val book = ownedId(r)?.let { books[it] }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(title, color = if (book != null) Color.White else Color(0xFF9A9A9A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(sub, color = SubtleText, fontSize = 13.sp)
                }
                if (book != null) {
                    BookMenuButton(book)
                    Chip("Inspect") { onOpen(book) }
                    Chip("Read", true) { onRead(book) }
                }
            }
        }
    }
    // Start at the first thing the user has.
    LaunchedEffect(Unit) {
        val first = c.rows.indexOfFirst { ownedId(it) != null }
        if (first > 0) state.scrollToItem(first)
    }
}

// ---- the library's collection screen --------------------------------------------------------------

/** A manga series' collection over the library (from its series tile): cover flow or list. */
@Composable
fun CollectionScreen(series: String, all: List<Book>) {
    val app = ThorApp.instance
    val (c, loading) = rememberMangaCollection(series, all)
    val byId = remember(all) { all.associateBy { it.id } }
    var flow by remember(series) { mutableStateOf<Boolean?>(null) }
    val showFlow = flow ?: c.mixed // cover flow by default when volumes and chapters are mixed
    fun close() { app.collection = null }
    DisposableEffect(Unit) {
        val prev = app.libraryBack
        app.libraryBack = { if (app.typing == null) close(); true }
        onDispose { app.libraryBack = prev }
    }
    fun inspect(b: Book) {
        close()
        app.startInspect(b, series)
    }
    fun read(b: Book) {
        close()
        app.openBookAnimated(b.id)
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
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .clip(HouseStyle.PanelShape)
                .background(HouseStyle.PanelBackground)
                .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(series, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CollectionInfoLine(c, loading)
                }
                Chip("Cover flow", showFlow) { flow = true }
                Chip("List", !showFlow) { flow = false }
                Chip("✕ Close") { close() }
            }
            if (showFlow) {
                CoverFlow(c, byId, ::inspect, ::read)
            } else {
                CollectionList(c, byId, null, ::inspect, ::read, Modifier.weight(1f, fill = false), withMenu = true)
            }
        }
    }
}

// ---- Inspect, manga: the collection on the top screen (the 3D book is on the bottom one) ----------

@Composable
fun InspectCollectionTop(s: InspectState) {
    val app = ThorApp.instance
    val series = s.collection ?: return
    val flow = remember { app.db.books().observeAll() }
    val all by flow.collectAsState(initial = emptyList())
    val (c, loading) = rememberMangaCollection(series, all)
    val byId = remember(all) { all.associateBy { it.id } }
    val unit = remember(s.book) { MangaShelf.unitOf(s.book) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E0E))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(series, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CollectionInfoLine(c, loading)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("On the bottom screen: ${unit?.label ?: s.book.title}", color = SubtleText, fontSize = 13.sp)
                    Chip("↺") { app.resetInspectView("↺") }
                    Chip("Read", true) { app.readFromInspect() }
                    Chip("✕") { app.closeInspect("X") }
                }
            }
        }
        CollectionList(
            c, byId, highlight = s.book.id,
            onOpen = { b -> app.switchInspect(b) },
            onRead = { b -> if (b.id == s.book.id) app.readFromInspect() else { app.switchInspect(b); app.readFromInspect() } },
            modifier = Modifier.weight(1f),
        )
        Text((if (c.isBooks) "Tap a book" else "Tap a volume or chapter") + " to see it below · " + INSPECT_HINT, color = Color(0xFF777777), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
