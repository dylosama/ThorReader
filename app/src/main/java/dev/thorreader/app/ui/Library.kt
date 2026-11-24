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
import dev.thorreader.app.LibraryView
import dev.thorreader.app.Shelf
import dev.thorreader.app.SortOrder
import dev.thorreader.app.TutorialStep
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

/**
 * The library (always on the top screen, mode 3; the real bookshelf is milestone 3).
 * Top row: "Library | Completed" switch, search, sort, add buttons. Below: view chips
 * All · Authors · Series · Type · Genre. Authors/Series/Type/Genre list categories with book
 * counts; tapping one shows its books (back arrow or B returns). Works with the d-pad and A/B.
 */
@Composable
fun LibraryScreen() {
    val app = ThorApp.instance
    val settings = app.settings
    val host = LocalHost.current
    val flow = remember { app.db.books().observeAll() }
    val loaded by flow.collectAsStateWithLifecycle(initialValue = null)
    val books = loaded ?: emptyList()

    val shelf = settings.shelf
    val view = settings.libraryView
    val sort = settings.sortFor(shelf)
    var category by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortOpen by remember { mutableStateOf(false) }

    // Leaving a category / search with Back or B.
    DisposableEffect(Unit) {
        app.libraryBack = {
            when {
                sortOpen -> { sortOpen = false; true }
                category != null -> { category = null; true }
                searchOpen -> { searchOpen = false; query = ""; true }
                else -> false
            }
        }
        onDispose { app.libraryBack = null }
    }
    // Upgraders and anyone who already has books skip "Add your books here".
    LaunchedEffect(loaded?.isNotEmpty()) { if (loaded?.isNotEmpty() == true) app.tutorialDone(TutorialStep.ADD_BOOKS) }

    val onShelf = books.filter { it.completed == (shelf == Shelf.COMPLETED) }
    val q = query.trim()
    val matching = if (q.isEmpty()) onShelf else onShelf.filter { b ->
        b.title.contains(q, true) || b.author?.contains(q, true) == true || b.series?.contains(q, true) == true
    }
    val sorted = LibraryLogic.sort(matching, sort)

    Column(Modifier.fillMaxSize().background(Color(0xFF101010)).padding(16.dp)) {
        // --- top row ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShelfSwitch(shelf, books.count { !it.completed }, books.count { it.completed }) {
                settings.updateShelf(it)
                category = null
            }
            Spacer(Modifier.width(10.dp))
            SearchBox(searchOpen, query, onOpen = { searchOpen = true }, onQuery = { query = it }, onClose = {
                searchOpen = false
                query = ""
            }, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(0.01f))
            app.importer.status?.let { HouseBadge(it, Modifier.padding(horizontal = 8.dp).widthIn(max = 260.dp)) }
            Box {
                Chip("⇅ ${sort.label}") { sortOpen = true }
                if (sortOpen) {
                    Popup(popupPositionProvider = BesideAnchor, onDismissRequest = { sortOpen = false }, properties = PopupProperties(focusable = true)) {
                        SortPopup(shelf, sort) {
                            settings.updateSort(shelf, it)
                            sortOpen = false
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Chip("+ Add files", true) { host?.pickFiles() }
            Spacer(Modifier.width(8.dp))
            Chip("+ Add folder", true) { host?.pickFolder() }
        }
        if (loaded != null && books.isEmpty() && app.tutorialShows(TutorialStep.ADD_BOOKS)) {
            TutorialBadge(
                TutorialStep.ADD_BOOKS, "Add your books here", ArrowDir.UP,
                Modifier.align(Alignment.End).padding(top = 4.dp, end = 60.dp), arrowAlign = Alignment.End,
            )
        }
        Spacer(Modifier.height(10.dp))

        // --- view chips (both shelves) ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryView.entries.forEach { v ->
                Chip(v.label, v == view) {
                    settings.updateLibraryView(v)
                    category = null
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        val cat = category
        when {
            loaded == null -> Unit
            books.isEmpty() -> EmptyMessage(
                "No books yet.\nTap \"Add files\" to pick books, or \"Add folder\" to add everything in a folder.\n" +
                    "Supported: ${BookFormat.summary}.",
            )
            onShelf.isEmpty() -> EmptyMessage(
                if (shelf == Shelf.COMPLETED) "No completed books yet.\nA book moves here when you reach its last page,\nor with \"Mark as completed\" in its popup."
                else "Every book is completed. They're in \"Completed\".",
            )
            matching.isEmpty() -> EmptyMessage("Nothing matches \"$q\".")
            view == LibraryView.ALL -> BookList(sorted, app.tutorialShows(TutorialStep.OPEN_BOOK))
            cat != null -> {
                val inCat = LibraryLogic.booksIn(matching, view, cat, sort)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Chip("←") { category = null }
                    Spacer(Modifier.width(10.dp))
                    Text(cat, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ${inCat.size} ${if (inCat.size == 1) "book" else "books"}", color = SubtleText, fontSize = 13.sp)
                }
                BookList(inCat, app.tutorialShows(TutorialStep.OPEN_BOOK))
            }
            else -> CategoryList(LibraryLogic.categories(matching, view)) { category = it }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = SubtleText, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BookList(books: List<Book>, showTutorial: Boolean) {
    Column {
        if (showTutorial && books.isNotEmpty()) {
            TutorialBadge(TutorialStep.OPEN_BOOK, "Tap a book to start reading", ArrowDir.DOWN, Modifier.padding(start = 24.dp, bottom = 2.dp), arrowAlign = Alignment.Start)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(books, key = { it.id }) { BookRow(it) }
        }
    }
}

@Composable
private fun CategoryList(categories: List<Pair<String, Int>>, open: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(categories, key = { it.first }) { (name, count) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A))
                    .focusClick { open(name) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                HouseBadge(if (count == 1) "1 book" else "$count books")
                Text("  ›", color = SubtleText, fontSize = 18.sp)
            }
        }
    }
}

/** Segmented "Library | Completed" switch. */
@Composable
private fun ShelfSwitch(current: Shelf, libraryCount: Int, completedCount: Int, onSelect: (Shelf) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1E1E1E))
            .border(1.dp, HouseStyle.Hairline, RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        Shelf.entries.forEach { s ->
            val selected = s == current
            val count = if (s == Shelf.LIBRARY) libraryCount else completedCount
            Text(
                "${s.label}  $count",
                color = if (selected) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Accent else Color.Transparent)
                    .focusClick(RoundedCornerShape(50)) { onSelect(s) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** A magnifier that expands into a search field (title, author, series). */
@Composable
private fun SearchBox(open: Boolean, query: String, onOpen: () -> Unit, onQuery: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    if (!open) {
        Chip("🔍") { onOpen() }
        return
    }
    val focus = remember { FocusRequester() }
    Row(
        modifier
            .widthIn(min = 180.dp, max = 360.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF2E2E2E))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔍", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (query.isEmpty()) Text("Title, author or series", color = Color(0xFF888888), fontSize = 14.sp)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Text(
            "✕",
            color = SubtleText,
            fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).focusClick(RoundedCornerShape(50)) { onClose() }.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun SortPopup(shelf: Shelf, current: SortOrder, choose: (SortOrder) -> Unit) {
    val first = remember { FocusRequester() }
    Column(
        Modifier
            .width(220.dp)
            .clip(HouseStyle.PanelShape)
            .background(HouseStyle.PanelBackground)
            .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PopupLabel("Sort by")
        LibraryLogic.sortsFor(shelf).forEach { o ->
            PopupItem(selected = o == current, focusRequester = if (o == current) first else null, onClick = { choose(o) }) {
                PopupText(o.label, color = if (o == current) Accent else HouseStyle.TextColor)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun BookRow(book: Book) {
    val app = ThorApp.instance
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1A1A))
            .focusClick { app.openBook(book.id) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.size(56.dp, 80.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    book.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (book.completed) Text(" ★", color = Gold, fontSize = 15.sp)
            }
            val byline = listOfNotNull(
                book.author,
                book.series?.let { s -> s + (book.seriesNumber?.let { " #" + formatNumber(it) } ?: "") },
            ).joinToString("  ·  ")
            if (byline.isNotEmpty()) Text(byline, color = SubtleText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = LibraryLogic.detailLine(book)
            val pageColor = Color(app.settings.bookmarkColor)
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    line.current?.let { c ->
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = pageColor, fontWeight = FontWeight.SemiBold))
                        append(c)
                        pop()
                    }
                    append(line.rest)
                },
                color = Color(0xFF888888), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        BookModeButton(book)
    }
}

/** The completed star. */
val Gold = Color(0xFFE8B84A)

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
                .focusClick(RoundedCornerShape(12.dp)) { open = true },
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
        PopupItem(onClick = {
            app.setCompleted(book, !book.completed)
            close()
        }) {
            if (book.completed) PopupText("Mark as unread") else {
                PopupText("Mark as completed", Modifier.weight(1f))
                Text("★", color = Gold, fontSize = HouseStyle.ItemTextSize)
            }
        }
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
