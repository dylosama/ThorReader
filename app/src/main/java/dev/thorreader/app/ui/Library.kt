package dev.thorreader.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.thorreader.app.LibraryChip
import dev.thorreader.app.Shelf
import dev.thorreader.app.SortOrder
import dev.thorreader.app.TutorialStep
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The library (always on the top screen, mode 3; the real bookshelf is milestone 3).
 * Top: "Library | Completed" switch + search (centre), "+" and settings (right). Below: one row
 * that sorts the list (Recently read, Recently added, Progress, Title) or groups it (Author,
 * Series, Genre, Type: names with counts; tapping one shows its books, back arrow or B returns).
 * Works with the d-pad and A/B.
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
    // The flat list and the books inside a group use the last flat sort chosen.
    val sort = settings.flatSort
    val asc = settings.flatAsc
    var category by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var addOpen by remember { mutableStateOf(false) }
    var reviewOnly by remember { mutableStateOf(false) }
    // List management: the entry showing "Hide?", the Add author/series flow, the bottom pill.
    var revealed by remember { mutableStateOf<String?>(null) }
    var addFlow by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, (() -> Unit)?>?>(null) }
    var noticeSeq by remember { mutableStateOf(0) }
    LaunchedEffect(noticeSeq) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }
    LaunchedEffect(settings.libraryChip, category) { revealed = null }

    // Leaving a category / search with Back or B.
    DisposableEffect(Unit) {
        app.libraryBack = {
            when {
                revealed != null -> { revealed = null; true }
                app.editInfoBook != null -> { app.editInfoBook = null; true }
                app.settingsOpen -> { app.settingsOpen = false; true }
                reviewOnly -> { reviewOnly = false; true }
                addOpen -> { addOpen = false; true }
                category != null -> { category = null; true }
                searchOpen -> { searchOpen = false; query = ""; true }
                else -> false
            }
        }
        onDispose { app.libraryBack = null }
    }
    // Upgraders and anyone who already has books skip "Add your books here".
    LaunchedEffect(loaded?.isNotEmpty()) { if (loaded?.isNotEmpty() == true) app.tutorialDone(TutorialStep.ADD_BOOKS) }

    // Library shows every book (completed ones too, with their star); Completed only the finished ones.
    val onShelf = if (shelf == Shelf.COMPLETED) books.filter { it.completed } else books
    val q = query.trim()
    val searched = if (q.isEmpty()) onShelf else onShelf.filter { b ->
        b.title.contains(q, true) || b.author?.contains(q, true) == true || b.series?.contains(q, true) == true
    }
    // "Needs review": books the online lookup couldn't match confidently.
    val reviewCount = searched.count { it.needsReview }
    if (reviewCount == 0) reviewOnly = false
    val matching = if (reviewOnly) searched.filter { it.needsReview } else searched
    val sorted = LibraryLogic.sort(matching, sort, asc)

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101010)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loaded != null && books.isEmpty()) {
            // First start: nothing but the two ways to add books, in the centre.
            EmptyLibrary()
            return@Column
        }
        // --- top bar: status (left) · Library | Completed + search (centre) · + and gear (right) ---
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Column(Modifier.align(Alignment.CenterStart).widthIn(max = 230.dp)) {
                app.importer.status?.let { HouseBadge(it) }
                if (app.importer.status == null) app.lookup.status?.let { HouseProgressBadge(it) }
            }
            AnimatedContent(
                targetState = searchOpen,
                modifier = Modifier.align(Alignment.Center),
                transitionSpec = {
                    (fadeIn(tween(220)) + expandHorizontally(tween(260), expandFrom = Alignment.CenterHorizontally)) togetherWith
                        (fadeOut(tween(160)) + shrinkHorizontally(tween(220), shrinkTowards = Alignment.CenterHorizontally)) using
                        SizeTransform(clip = false)
                },
                label = "search",
            ) { open ->
                if (open) {
                    SearchField(query, onQuery = { query = it }, onClose = {
                        searchOpen = false
                        query = ""
                    }, modifier = Modifier.width(420.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShelfSwitch(shelf, books.size, books.count { it.completed }) {
                            settings.updateShelf(it)
                            category = null
                        }
                        RoundIcon(onClick = { searchOpen = true }) { MagnifierIcon() }
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    RoundIcon(onClick = { addOpen = true }) { Text("+", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light) }
                    if (addOpen) {
                        Popup(popupPositionProvider = BelowEnd, onDismissRequest = { addOpen = false }, properties = PopupProperties(focusable = true)) {
                            AddPopup { addOpen = false }
                        }
                    }
                }
                RoundIcon(onClick = { app.settingsOpen = true }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))

        // --- one row: sort the list, or group it (both shelves) ---
        LibraryRow(reviewCount, reviewOnly, onReview = { reviewOnly = !reviewOnly }) {
            settings.tapChip(it)
            category = null
        }
        Spacer(Modifier.height(8.dp))
        if (app.lookup.showKeyHint) {
            ComicVineHint()
            Spacer(Modifier.height(8.dp))
        }

        val cat = category
        when {
            loaded == null -> Unit
            onShelf.isEmpty() -> EmptyMessage(
                if (shelf == Shelf.COMPLETED) "No completed books yet.\nA book moves here when you reach its last page,\nor with \"Mark as completed\" in its popup."
                else "No books yet.",
            )
            matching.isEmpty() -> EmptyMessage(if (reviewOnly) "Nothing needs review." else "Nothing matches \"$q\".")
            view == LibraryView.ALL -> BookList(sorted, app.tutorialShows(TutorialStep.OPEN_BOOK))
            cat != null -> {
                val inCat = LibraryLogic.booksIn(matching, view, cat, sort, asc)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Chip("←") { category = null }
                    Spacer(Modifier.width(10.dp))
                    // The group's name as a small header.
                    Text(cat, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ${inCat.size} ${if (inCat.size == 1) "book" else "books"}", color = SubtleText, fontSize = 13.sp)
                }
                BookList(inCat, app.tutorialShows(TutorialStep.OPEN_BOOK))
            }
            else -> CategoryList(
                LibraryLogic.categories(matching, view, settings.groupAsc, hidden = settings.hiddenFor(view)),
                view,
                revealed,
                onReveal = { revealed = it },
                onHide = { name ->
                    revealed = null
                    settings.hide(view, name)
                    android.util.Log.i(dev.thorreader.app.TAG, "Hidden ${view.label} '$name' from its list")
                    notice = "Hidden" to { settings.unhide(view, name); notice = null }
                    noticeSeq++
                },
                onAdd = if (view == LibraryView.AUTHORS || view == LibraryView.SERIES) ({ addFlow = true }) else null,
            ) { category = it }
        }
    }
    // "Hidden · Undo" / result of Add author/series, for about 4 s.
    notice?.let { (text, undo) ->
        LibraryNotice(text, undo, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
    }
    if (addFlow) {
        AddGroupFlow(view, books) { msg ->
            addFlow = false
            if (msg != null) {
                notice = msg to null
                noticeSeq++
            }
        }
    }
    if (app.settingsOpen) SettingsPanel()
    app.editInfoBook?.let { b -> androidx.compose.runtime.key(b.id) { EditInfoPanel(b) } }
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

/**
 * The Author / Series / Genre / Type list: "Frank Herbert · 1" rows. Author, Series and Genre
 * entries can be hidden (swipe left, hold, or X); Type can't. Author and Series end with a faint
 * "+ Add author/series" line, and tapping the empty space below the list does the same.
 */
@Composable
private fun CategoryList(
    categories: List<Pair<String, Int>>,
    view: LibraryView,
    revealed: String?,
    onReveal: (String?) -> Unit,
    onHide: (String) -> Unit,
    onAdd: (() -> Unit)?,
    open: (String) -> Unit,
) {
    val canHide = view != LibraryView.TYPE
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(categories, key = { it.first }) { (name, count) ->
                ManagedRow(
                    name, count, canHide,
                    revealed = revealed == name,
                    anyRevealed = revealed != null,
                    onReveal = { show -> onReveal(if (show) name else null) },
                    onHide = { onHide(name) },
                    onOpen = { open(name) },
                )
            }
            if (onAdd != null) item(key = "+add") {
                Text(
                    if (view == LibraryView.SERIES) "+ Add series" else "+ Add author",
                    color = Color(0xFF777777),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .focusClick { if (revealed != null) onReveal(null) else onAdd() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        // The empty space below the list: cancels a "Hide?", or starts "Add author/series".
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (revealed != null) onReveal(null) else onAdd?.invoke()
                },
        )
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

/** The search field the round search button expands into (title, author, series; live). */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
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
        // Typing happens on the bottom screen; the list here filters live as you type.
        TypingField(
            query, onQuery, Modifier.weight(1f).padding(vertical = 8.dp),
            hint = "Title, author or series", fontSize = 14, autoStart = true,
        )
        Text(
            "✕",
            color = SubtleText,
            fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).focusClick(RoundedCornerShape(50)) {
                ThorApp.instance.stopTyping("search closed")
                onClose()
            }.padding(horizontal = 10.dp, vertical = 6.dp),
        )
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
            // Author: grey, semi-bold, between the title (17 sp) and the third line (13 sp). The library
            // is always dark (#1A1A1A rows), where #B8B8B8 reads well; themes only colour the pages.
            if (byline.isNotEmpty()) Text(byline, color = Color(0xFFB8B8B8), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = LibraryLogic.detailLine(book)
            // A finished book shows "22 / 22" with the page in green; otherwise the bookmark colour.
            val pageColor = if (line.completed) CompletedGreen else Color(app.settings.bookmarkColor)
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(line.prefix)
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

/** Page number of a completed book in the library ("22 / 22"). */
val CompletedGreen = Color(0xFF5CBF6A)

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
            app.editInfoBook = book
            close()
        }) {
            PopupText("Edit info", Modifier.weight(1f))
            if (book.needsReview) HouseBadge("Needs review", color = HouseStyle.Warning)
        }
        PopupItem(onClick = {
            app.lookup.refresh(book)
            close()
        }) { PopupText("Refresh info") }
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

/** First start, no books: one line and the two big ways to add books, in the centre. */
@Composable
private fun EmptyLibrary() {
    val app = ThorApp.instance
    val host = LocalHost.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Add your books to get started", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            if (app.tutorialShows(TutorialStep.ADD_BOOKS)) {
                TutorialBadge(TutorialStep.ADD_BOOKS, "Add your books here", ArrowDir.DOWN)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigAddButton("Add files") { host?.pickFiles() }
                BigAddButton("Add folder") { host?.pickFolder() }
            }
            // While the first import runs.
            app.importer.status?.let { HouseBadge(it) }
        }
    }
}

@Composable
private fun BigAddButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(220.dp, 72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Accent)
            .focusClick(RoundedCornerShape(14.dp), onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Round icon button of the top bar (house style: quiet, translucent, hairline). */
@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFF262626))
            .border(1.dp, HouseStyle.Hairline, CircleShape)
            .focusClick(CircleShape, onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A simple drawn magnifier (crisp at any size, matches the other white icons). */
@Composable
private fun MagnifierIcon() {
    Canvas(Modifier.size(20.dp)) {
        val r = size.minDimension * 0.32f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(Color.White, r, c, style = Stroke(width = size.minDimension * 0.1f))
        val d = r * 0.72f
        drawLine(Color.White, Offset(c.x + d, c.y + d), Offset(size.width * 0.9f, size.height * 0.9f), strokeWidth = size.minDimension * 0.12f, cap = StrokeCap.Round)
    }
}

/** The "+" popup: right-aligned under its button. */
private object BelowEnd : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, anchorBounds.bottom + 10)
    }
}

@Composable
private fun AddPopup(close: () -> Unit) {
    val host = LocalHost.current
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
        PopupItem(focusRequester = first, onClick = { close(); host?.pickFiles() }) { PopupText("Add files") }
        PopupItem(onClick = { close(); host?.pickFolder() }) { PopupText("Add folder") }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * The library's one row: Recently read · Recently added · Progress · Title (sort the list) ·
 * Author · Series · Genre · Type (group it) [· Needs review (N)]. The active chip shows ↑/↓;
 * tapping it again flips the direction. Scrolls sideways if it doesn't fit, keeping the active
 * chip in view. (Chip look unchanged from the step-14 sort row.)
 */
@Composable
private fun LibraryRow(reviewCount: Int, reviewOnly: Boolean, onReview: () -> Unit, onChip: (LibraryChip) -> Unit) {
    val settings = ThorApp.instance.settings
    val current = settings.libraryChip
    val asc = settings.chipAscending
    val state = rememberLazyListState()
    val chips = LibraryChip.entries
    LaunchedEffect(current) {
        val i = chips.indexOf(current)
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == i }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) state.animateScrollToItem((i - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(chips) { c ->
            val active = c == current
            RowChip(if (active) "${c.label} ${if (asc) "↑" else "↓"}" else c.label, active) { onChip(c) }
        }
        if (reviewCount > 0) item { RowChip("Needs review ($reviewCount)", reviewOnly, onReview) }
    }
}

/** One chip of the library row (the step-14 sort-row style, unchanged). */
@Composable
private fun RowChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) Accent else SubtleText,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .focusClick(RoundedCornerShape(50), onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
