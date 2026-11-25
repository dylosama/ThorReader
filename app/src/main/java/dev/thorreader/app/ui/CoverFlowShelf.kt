package dev.thorreader.app.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.engine.Book3D
import dev.thorreader.app.engine.FlowGeometry
import dev.thorreader.app.importer.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---- spines -------------------------------------------------------------------------------------

/**
 * The cover flow's spines, drawn like Inspect's (title · author in the cover's dominant colour,
 * or the user's own spine picture), at the size they are shown; a small memory cache.
 */
object FlowSpines {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    private fun key(book: Book, w: Int, h: Int, colourFrom: Boolean): String {
        val own = dev.thorreader.app.io.CustomCovers.file(book.id, dev.thorreader.app.io.CustomCovers.Part.SPINE)?.lastModified() ?: 0L
        return "${book.id}:$w:$h:${book.title.hashCode()}:${book.author.hashCode()}:$own:$colourFrom"
    }

    fun cached(book: Book, w: Int, h: Int, colourFrom: Boolean): Bitmap? = cache.get(key(book, w, h, colourFrom))

    /** Memory is short: keep only the most recently shown half. */
    fun trim() = cache.trimToSize(cache.size() / 2)

    suspend fun get(book: Book, w: Int, h: Int, front: Bitmap?): Bitmap = cached(book, w, h, front != null) ?: withContext(Dispatchers.Default) {
        val base = front?.let { dominantColor(it) } ?: android.graphics.Color.rgb(58, 58, 64)
        (customSpine(book, w, h) ?: generatedSpine(book, w, h, base, shade(base, 0.8f)))
            .also { cache.put(key(book, w, h, front != null), it) }
    }
}

// ---- the view -----------------------------------------------------------------------------------

/**
 * The library as a cover flow: one row of books standing turned, so each shows its spine and its
 * front cover at an angle (right-to-left manga turn the other way: their spine is on the right).
 * Tap / A = Inspect (lifting the book from its angled cover), hold / X = the book's popup; a
 * series with 2+ books is one book with a "Name · N" tab that opens the collection.
 */
@Composable
fun BookFlow(books: List<Book>, modifier: Modifier = Modifier, stackSeries: Boolean = true) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Room under each book for a series tab and the focus lift.
        val bookHPx = with(density) { ((maxHeight - 40.dp) * FlowGeometry.HEIGHT_SHARE).roundToPx() }.coerceAtLeast(120)
        val state = rememberLazyListState()
        val items = remember(books, stackSeries) { if (stackSeries) MangaShelf.group(books) else books.map { MangaShelf.Item.One(it) } }
        ShowLandingBook(items) { i ->
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == i }
            val shown = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
            if (!shown) state.scrollToItem((i - 1).coerceAtLeast(0))
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { item -> if (item is MangaShelf.Item.One) item.book.id else "series:" + (item as MangaShelf.Item.Series).name }) { item ->
                val app = ThorApp.instance
                when (item) {
                    is MangaShelf.Item.One -> FlowBook(item.book, bookHPx, null, listOf(item.book)) { app.startInspect(item.book) }
                    is MangaShelf.Item.Series -> FlowBook(item.books.first(), bookHPx, "${item.name} · ${item.books.size}", item.books, popup = false) { app.collection = item.name }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlowBook(book: Book, hPx: Int, tab: String?, boundsFor: List<Book>, popup: Boolean = true, onOpen: () -> Unit) {
    val app = ThorApp.instance
    val density = LocalDensity.current
    var showPopup by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    // The front cover (the user's own picture first), decoded at about the size it is shown.
    val coverPx = (hPx * 0.75f).roundToInt()
    val path = dev.thorreader.app.io.CustomCovers.frontPath(book)
    val front by produceState(path?.let { CoverThumbs.cached(it, coverPx) }, path, coverPx) {
        val p = path ?: return@produceState
        value = CoverThumbs.load(p, coverPx)
    }
    val img = front
    val aspect = (img?.let { it.width.toFloat() / it.height } ?: (2f / 3f)).coerceIn(0.45f, 1.0f)
    val coverW = hPx * aspect

    // The spine: as thick as the book really is (within limits), like Inspect's.
    val format = runCatching { BookFormat.valueOf(book.format) }.getOrNull()
    val printed = Book3D.printedPages(book.pageCount, format?.reflowable ?: true)
    val spineW = (hPx * FlowGeometry.spineShare(Book3D.thicknessRatio(printed, format?.comic == true && !book.rtl))).roundToInt().coerceAtLeast(8)
    val spine by produceState(FlowSpines.cached(book, spineW, hPx, img != null), book.id, book.title, book.author, spineW, hPx, img) {
        value = FlowSpines.get(book, spineW, hPx, img)
    }

    val angle = FlowGeometry.ANGLE_DEG
    // Sized as the turned book looks (with the renderer's perspective - checked against the
    // drawn covers on the Thor), so the gaps between books are even.
    val spineSeen = FlowGeometry.projected(spineW.toFloat(), 90f - angle, FlowGeometry.CAMERA_PX)
    val slotW = FlowGeometry.footprint(coverW, spineW.toFloat(), angle, FlowGeometry.CAMERA_PX)
    val rtl = book.rtl
    // Where cover and spine meet (the book's front edge on the spine side).
    val jointX = if (rtl) slotW - spineSeen else spineSeen
    val camera = FlowGeometry.CAMERA

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(with(density) { slotW.toDp() }, with(density) { hPx.toDp() })
                .graphicsLayer { translationY = if (focused) -10.dp.toPx() else 0f }
                .drawBehind {
                    // A soft shadow on the "table" under the book.
                    drawOval(
                        Brush.radialGradient(listOf(Color(0x99000000), Color.Transparent), center = Offset(size.width / 2f, size.height + 6.dp.toPx()), radius = size.width * 0.7f),
                        topLeft = Offset(-size.width * 0.15f, size.height - 8.dp.toPx()),
                        size = Size(size.width * 1.3f, 28.dp.toPx()),
                    )
                }
                .onFocusChanged { focused = it.hasFocus || it.isFocused }
                .onKeyEvent {
                    when {
                        it.key == Key.ButtonA && it.type == KeyEventType.KeyUp -> { onOpen(); true }
                        it.key == Key.ButtonX && it.type == KeyEventType.KeyUp && popup -> { showPopup = true; true }
                        it.key == Key.ButtonA || it.key == Key.ButtonX -> true
                        else -> false
                    }
                }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = onOpen, onLongClick = if (popup) ({ showPopup = true }) else null,
                )
                .semantics { contentDescription = book.title },
        ) {
            // The spine, hinged on the joint and turned away (its far edge into the screen).
            Box(
                Modifier
                    // Full size even where the flat face is wider than the slot (only the turned face fits).
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset((if (rtl) jointX else jointX - spineW).roundToInt(), 0) }
                    .size(with(density) { spineW.toDp() }, with(density) { hPx.toDp() })
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0.5f)
                        rotationY = if (rtl) 90f - angle else -(90f - angle)
                        cameraDistance = camera
                    }
                    .background(Color(0xFF3A3A40)),
            ) {
                spine?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Color(0x33000000))) // turned from the light
            }
            // The front cover, hinged on the same joint, turned by the same angle.
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset((if (rtl) jointX - coverW else jointX).roundToInt(), 0) }
                    .size(with(density) { coverW.toDp() }, with(density) { hPx.toDp() })
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                        rotationY = if (rtl) -angle else angle
                        cameraDistance = camera
                    }
                    .onGloballyPositioned { c ->
                        // Inspect lifts the book from here; closing it lands it back here.
                        val r = c.boundsInWindow()
                        boundsFor.forEach { b -> app.coverBounds[b.id] = android.graphics.RectF(r.left, r.top, r.right, r.bottom) }
                    }
                    .border(2.dp, if (focused) Accent else Color.Transparent),
            ) {
                if (img != null) Image(img.asImageBitmap(), book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else BookCover(book, Modifier.fillMaxSize())
                // Light from the front: the far edge a little darker.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(if (rtl) listOf(Color(0x2E000000), Color.Transparent) else listOf(Color.Transparent, Color(0x2E000000))),
                    ),
                )
                if (book.completed) {
                    Text("★", color = Gold, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd).background(Color(0x88000000), RoundedCornerShape(bottomStart = 8.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                val mode = book.readingMode ?: app.settings.lastBookMode
                if (app.settings.gridModeIcons && tab == null) ModeIcon(
                    mode, selected = false,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0x99000000), RoundedCornerShape(topStart = 8.dp))
                        .padding(horizontal = 5.dp, vertical = 4.dp)
                        .size(18.dp, 14.dp)
                        .semantics { contentDescription = "Opens in Mode ${mode.number}, ${mode.title}" },
                )
            }
            if (showPopup) {
                Popup(popupPositionProvider = BesideAnchor, onDismissRequest = { showPopup = false }, properties = PopupProperties(focusable = true)) {
                    BookPopup(book, book.readingMode ?: app.settings.lastBookMode) { showPopup = false }
                }
            }
        }
        // A series: its name and how many books, under the book.
        Box(Modifier.height(30.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            if (tab != null) Text(
                tab, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
