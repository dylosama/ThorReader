package dev.thorreader.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- cover thumbnails ---------------------------------------------------------------------------

/**
 * Screen-sized cover thumbnails for the grid: decoded once at about the cell size (not the full
 * cover), kept in a small memory cache (~16 MB), and only for covers that are on screen.
 */
object CoverThumbs {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    fun cached(path: String, widthPx: Int): Bitmap? = cache.get("$path@$widthPx")

    suspend fun load(path: String, widthPx: Int): Bitmap? = cached(path, widthPx) ?: withContext(Dispatchers.IO) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= widthPx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { cache.put("$path@$widthPx", it) }
    }
}

// ---- the grid -------------------------------------------------------------------------------------

/** The library as a grid of covers (grid view). Tap = Inspect; hold or X = the book's popup. */
@Composable
fun BookGrid(books: List<Book>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cols = gridColumns(maxWidth.value)
        val cellPx = with(LocalDensity.current) { ((maxWidth - LibraryGap * (cols - 1)) / cols).roundToPx() }
        val state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        ShowLandingBook(books) { i ->
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == i }
            val shown = item != null && item.offset.y >= info.viewportStartOffset && item.offset.y + item.size.height <= info.viewportEndOffset
            if (!shown) state.scrollToItem(i)
        }
        FillGrid(cols, state = state) {
            items(books, key = { it.id }) { GridCover(it, cellPx) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCover(book: Book, cellPx: Int) {
    val app = ThorApp.instance
    var popup by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val path = dev.thorreader.app.io.CustomCovers.frontPath(book) // the user's own picture first
    val thumb by produceState<ImageBitmap?>(
        path?.let { CoverThumbs.cached(it, cellPx) }?.asImageBitmap(), path, cellPx,
    ) {
        val p = path ?: return@produceState
        value = CoverThumbs.load(p, cellPx)?.asImageBitmap()
    }
    val img = thumb
    // Cells share one height (2:3); each cover keeps its own shape, standing on the cell's bottom.
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.BottomCenter) {
        val aspect = img?.let { it.width.toFloat() / it.height } ?: (2f / 3f)
        val coverMod = Modifier
            .then(if (aspect >= 2f / 3f) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
            .aspectRatio(aspect.coerceIn(0.4f, 1.5f), matchHeightConstraintsFirst = aspect < 2f / 3f)
            .shadow(6.dp, shape)
            .clip(shape)
            .onGloballyPositioned { c ->
                // Where Inspect lifts the cover from (and where closing a book lands it).
                val r = c.boundsInWindow()
                app.coverBounds[book.id] = android.graphics.RectF(r.left, r.top, r.right, r.bottom)
            }
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onKeyEvent {
                when {
                    it.key == Key.ButtonA && it.type == KeyEventType.KeyUp -> { app.startInspect(book); true }
                    it.key == Key.ButtonX && it.type == KeyEventType.KeyUp -> { popup = true; true }
                    it.key == Key.ButtonA || it.key == Key.ButtonX -> true
                    else -> false
                }
            }
            .combinedClickable(onClick = { app.startInspect(book) }, onLongClick = { popup = true })
        Box(coverMod) {
            if (img != null) {
                Image(img, book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                // No cover: the generated placeholder, as in the list.
                BookCover(book, Modifier.fillMaxSize())
            }
            if (book.completed) {
                Text("★", color = Gold, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd).background(Color(0x88000000), RoundedCornerShape(bottomStart = 8.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        if (popup) {
            Popup(popupPositionProvider = BesideAnchor, onDismissRequest = { popup = false }, properties = PopupProperties(focusable = true)) {
                BookPopup(book, book.readingMode ?: app.settings.lastBookMode) { popup = false }
            }
        }
    }
}


// ---- the top-right pill ---------------------------------------------------------------------------

/**
 * One pill with three icon buttons, divided by hairlines: view toggle | + | gear. The toggle
 * shows the view you'll switch TO. Each button is 52 × 48 dp; pressing shows the theme colour.
 */
@Composable
fun LibraryPill(onAdd: () -> Unit, addPopup: @Composable () -> Unit) {
    val app = ThorApp.instance
    val grid = app.settings.libraryGrid
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF262626))
            .border(1.dp, HouseStyle.Hairline, RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(onClick = { app.settings.updateLibraryGrid(!grid) }) { if (grid) ListIcon() else GridIcon() }
        PillDivider()
        Box {
            PillButton(onClick = onAdd) { Text("+", color = Color.White, fontSize = 24.sp) }
            addPopup()
        }
        PillDivider()
        PillButton(onClick = { app.settingsOpen = true }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
    }
}

@Composable
private fun PillDivider() = Box(Modifier.width(1.dp).height(26.dp).background(HouseStyle.Hairline))

@Composable
private fun PillButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(52.dp, 48.dp)
            .background(if (pressed) Accent.copy(alpha = 0.35f) else Color.Transparent)
            .pillFocus(onClick, source),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Focusable for the d-pad (accent outline), A / Enter / tap activate it. */
private fun Modifier.pillFocus(onClick: () -> Unit, source: MutableInteractionSource): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(50))
        .onKeyEvent {
            if (it.key == Key.ButtonA) {
                if (it.type == KeyEventType.KeyUp) onClick()
                true
            } else false
        }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/** Four small squares: "show as a grid". */
@Composable
private fun GridIcon() {
    Canvas(Modifier.size(20.dp)) {
        val g = size.width * 0.14f
        val s = (size.width - g) / 2f
        for (i in 0..1) for (j in 0..1) {
            drawRoundRect(Color.White, Offset(i * (s + g), j * (s + g)), androidx.compose.ui.geometry.Size(s, s), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

/** Three lines: "show as a list". */
@Composable
private fun ListIcon() {
    Canvas(Modifier.size(20.dp)) {
        for (i in 0..2) {
            val y = size.height * (0.2f + i * 0.3f)
            drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.5.dp.toPx())
        }
    }
}

