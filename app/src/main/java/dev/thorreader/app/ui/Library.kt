package dev.thorreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.importer.formatNumber

/** Temporary library list (the real bookshelf is milestone 3). Always on the top screen, mode 3. */
@Composable
fun LibraryScreen() {
    val app = ThorApp.instance
    val host = LocalHost.current
    val flow = remember { app.db.books().observeAll() }
    val books by flow.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize().background(Color(0xFF101010)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("  ${books.size} books", color = SubtleText, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            app.importer.status?.let { HouseBadge(it, Modifier.padding(end = 10.dp)) }
            Chip("+ Add files", true) { host?.pickFiles() }
            Spacer(Modifier.width(8.dp))
            Chip("+ Add folder", true) { host?.pickFolder() }
        }
        Spacer(Modifier.height(12.dp))
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No books yet.\nTap \"Add files\" to pick books, or \"Add folder\" to add everything in a folder.\n" +
                        "Supported: EPUB, PDF, MOBI, FB2, XPS, TXT, CBZ, CBR, CB7.",
                    color = SubtleText,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(books, key = { it.id }) { BookRow(it) }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book) {
    val app = ThorApp.instance
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { app.openBook(book.id) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.size(56.dp, 80.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(book.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let { Text(it, color = SubtleText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val details = listOfNotNull(
                book.series?.let { s -> s + (book.seriesNumber?.let { " #" + formatNumber(it) } ?: "") },
                runCatching { BookFormat.valueOf(book.format).label }.getOrDefault(book.format),
                if (book.pageCount > 0) "${book.pageCount} pages" else null,
                if (book.rtl) "right-to-left" else null,
                if (book.lastOpenedAt > 0) "${(book.progress * 100).toInt()}% read" else null,
            ).joinToString("  ·  ")
            Text(details, color = Color(0xFF888888), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BookModeButton(book)
    }
}

/** Square button showing the book's mode; opens the book's little popup. */
@Composable
private fun BookModeButton(book: Book) {
    val app = ThorApp.instance
    var open by remember { mutableStateOf(false) }
    val mode = book.readingMode ?: app.settings.lastBookMode
    Box {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(HouseStyle.Background)
                .border(1.dp, HouseStyle.Hairline, RoundedCornerShape(12.dp))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            ModeIcon(mode, false, Modifier.size(32.dp, 26.dp))
        }
        if (open) {
            Popup(
                popupPositionProvider = BesideAnchor,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                BookPopup(book, mode) { open = false }
            }
        }
    }
}

/** Places the popup to the right of its button (to the left if there isn't room), kept on screen. */
private object BesideAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val gap = 12
        var x = anchorBounds.right + gap
        if (x + popupContentSize.width > windowSize.width) x = anchorBounds.left - gap - popupContentSize.width
        val y = anchorBounds.top.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x.coerceAtLeast(0), y)
    }
}

@Composable
private fun BookPopup(book: Book, mode: ReadingMode, close: () -> Unit) {
    val app = ThorApp.instance
    var confirmRemove by remember { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }
    Column(
        Modifier
            .width(250.dp)
            .clip(HouseStyle.PanelShape)
            .background(HouseStyle.PanelBackground)
            .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PopupLabel("Opens in")
        ReadingMode.entries.forEach { m ->
            PopupItem(
                selected = m == mode,
                focusRequester = if (m == mode) initialFocus else null,
                onClick = {
                    app.setBookMode(book, m)
                    close()
                },
            ) {
                ModeIcon(m, m == mode, Modifier.size(24.dp, 19.dp))
                PopupText("${m.number} · ${m.title}", color = if (m == mode) Accent else HouseStyle.TextColor)
            }
        }
        PopupDivider()
        PopupItem(onClick = { app.setReadingDirection(book, !book.rtl) }) {
            PopupText("Manga (right-to-left)", Modifier.weight(1f))
            HouseBadge(if (book.rtl) "On" else "Off", color = if (book.rtl) Accent else HouseStyle.Subtle)
        }
        if (book.series != null) PopupLabel("Applies to the whole series")
        PopupDivider()
        if (!confirmRemove) {
            PopupItem(onClick = { confirmRemove = true }) {
                PopupText("Remove from library", color = HouseStyle.Warning)
            }
        } else {
            PopupLabel("Remove \"${book.title}\"? The file itself stays on your device.")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PopupItem(Modifier.weight(1f), onClick = { confirmRemove = false }) { PopupText("Cancel") }
                PopupItem(Modifier.weight(1f), onClick = {
                    app.removeBook(book)
                    close()
                }) { PopupText("Remove", color = HouseStyle.Warning) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }
}

/**
 * One popup row. Always focusable, so the d-pad moves between rows; A / Enter / centre press
 * activates it, as does a tap.
 */
@Composable
private fun PopupItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = HouseStyle.ItemMinHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> HouseStyle.Focus
                    selected -> Accent.copy(alpha = 0.15f)
                    else -> Color.Transparent
                }
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                val activate = it.key == Key.ButtonA || it.key == Key.Enter || it.key == Key.DirectionCenter || it.key == Key.NumPadEnter
                if (activate && it.type == KeyEventType.KeyUp) {
                    onClick()
                    true
                } else {
                    activate // swallow the key-down too
                }
            }
            .focusable()
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun PopupText(text: String, modifier: Modifier = Modifier, color: Color = HouseStyle.TextColor) {
    Text(text, modifier = modifier, color = color, fontSize = HouseStyle.ItemTextSize, fontWeight = HouseStyle.TextWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PopupLabel(text: String) {
    Text(
        text,
        color = HouseStyle.Subtle,
        fontSize = HouseStyle.TextSize,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PopupDivider() {
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(HouseStyle.Hairline))
}
