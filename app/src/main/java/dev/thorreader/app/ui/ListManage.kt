package dev.thorreader.app.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.LibraryView
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.data.saveMeta
import dev.thorreader.app.importer.formatNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The eye-with-a-line "hide" icon, drawn so it matches the other white icons. */
@Composable
fun EyeOffIcon(color: Color = Color.White) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.08f)
        // Eye outline: two arcs.
        drawArc(color, 200f, 140f, false, Offset(w * 0.05f, h * 0.25f), androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.9f), style = stroke)
        drawArc(color, 20f, 140f, false, Offset(w * 0.05f, -h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.9f), style = stroke)
        drawCircle(color, w * 0.13f, Offset(w / 2f, h / 2f))
        drawLine(color, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.88f, h * 0.12f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    }
}

/**
 * One row of the Author / Series / Genre list: "Frank Herbert · 1". When [canHide], swiping
 * left on its right half, holding it (~0.5 s) or pressing X reveals "Hide?"; tapping that (or
 * A) hides the entry after a short collapse. Tapping the row while something is revealed
 * cancels instead of opening.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManagedRow(
    name: String,
    count: Int,
    canHide: Boolean,
    revealed: Boolean,
    anyRevealed: Boolean,
    onReveal: (Boolean) -> Unit,
    onHide: () -> Unit,
    onOpen: () -> Unit,
) {
    val revealPx = with(LocalDensity.current) { 132.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var collapsing by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) { offset.animateTo(if (revealed) -revealPx else 0f, tween(180)) }
    LaunchedEffect(collapsing) {
        if (collapsing) {
            delay(240) // let the row collapse, then hide it for real
            onHide()
        }
    }
    AnimatedVisibility(visible = !collapsing, exit = shrinkVertically(tween(220)) + fadeOut(tween(200))) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF141414))) {
            // Behind the row: the eye-with-a-line and the "Hide?" pill.
            if (canHide && (revealed || offset.value < -1f)) {
                Row(
                    Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EyeOffIcon(HouseStyle.Subtle)
                    Text(
                        "Hide?",
                        color = HouseStyle.TextColor,
                        fontSize = HouseStyle.ItemTextSize,
                        modifier = Modifier
                            .clip(HouseStyle.Shape)
                            .background(HouseStyle.Background)
                            .border(1.dp, HouseStyle.Hairline, HouseStyle.Shape)
                            .focusClick(HouseStyle.Shape) { collapsing = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Row(
                Modifier
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent {
                        when {
                            it.key == Key.ButtonX && canHide -> {
                                if (it.type == KeyEventType.KeyDown) {
                                    Log.i(TAG, "X button (code ${it.key.nativeKeyCode}) on '$name': Hide?")
                                    onReveal(true)
                                }
                                true
                            }
                            it.key == Key.ButtonA -> {
                                if (it.type == KeyEventType.KeyUp) { if (revealed) collapsing = true else onOpen() }
                                true
                            }
                            else -> false
                        }
                    }
                    .pointerInput(canHide) {
                        if (!canHide) return@pointerInput
                        var fromRight = false
                        detectHorizontalDragGestures(
                            onDragStart = { fromRight = revealed || it.x > size.width / 2f },
                            onDragEnd = {
                                val show = offset.value < -revealPx / 2f
                                scope.launch { offset.animateTo(if (show) -revealPx else 0f, tween(160)) }
                                onReveal(show)
                            },
                            onDragCancel = { scope.launch { offset.animateTo(if (revealed) -revealPx else 0f) } },
                        ) { change, dx ->
                            if (!fromRight) return@detectHorizontalDragGestures
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + dx).coerceIn(-revealPx * 1.2f, 0f)) }
                        }
                    }
                    .combinedClickable(
                        onClick = { if (anyRevealed) onReveal(false) else onOpen() },
                        onLongClick = if (canHide) ({ onReveal(true) }) else null,
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(name)
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = SubtleText))
                        append(" · $count")
                        pop()
                    },
                    color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text("›", color = SubtleText, fontSize = 18.sp)
            }
        }
    }
}

/** "Hidden · Undo" (or a short notice) at the bottom of the library. */
@Composable
fun LibraryNotice(text: String, undo: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(HouseStyle.Shape)
            .background(HouseStyle.Background)
            .border(1.dp, HouseStyle.Hairline, HouseStyle.Shape)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = HouseStyle.TextColor, fontSize = HouseStyle.ItemTextSize)
        if (undo != null) {
            Text("·", color = HouseStyle.Subtle, fontSize = HouseStyle.ItemTextSize)
            Text(
                "Undo", color = Accent, fontSize = HouseStyle.ItemTextSize, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(HouseStyle.Shape).focusClick(HouseStyle.Shape, undo).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

// ---- Settings: hidden items ------------------------------------------------------------------

/** Collapsible "Hidden items (N)" for the Settings screen. */
@Composable
fun HiddenItemsSection() {
    val settings = ThorApp.instance.settings
    var open by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val n = settings.hiddenCount
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("${if (open) "▾" else "▸"} Hidden items ($n)", open) { open = !open }
    }
    if (!open) return
    if (n == 0) {
        Text("Nothing is hidden. Swipe left on a name in the Author, Series or Genre list (or hold it) to hide it.", color = HouseStyle.Subtle, fontSize = 13.sp)
        return
    }
    listOf(
        "Authors" to LibraryView.AUTHORS,
        "Series" to LibraryView.SERIES,
        "Genres" to LibraryView.GENRE,
    ).forEach { (title, view) ->
        val items = settings.hiddenFor(view).sortedWith { a, b -> dev.thorreader.app.io.NaturalOrder.compare(a, b) }
        if (items.isEmpty()) return@forEach
        Text(title, color = HouseStyle.Subtle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        items.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Chip("Unhide") { settings.unhide(view, name) }
            }
        }
    }
    if (!confirmAll) {
        Chip("Unhide all") { confirmAll = true }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show all $n hidden items again?", color = Color.White, fontSize = 14.sp)
            Chip("Unhide all", true) { settings.unhideAll(); confirmAll = false }
            Chip("Cancel") { confirmAll = false }
        }
    }
}

// ---- Add author / Add series ----------------------------------------------------------------

private enum class Choice { MOVE, ADD, SKIP }

/**
 * Add author / Add series: 1) pick books, 2) decide for books that already have one,
 * 3) type the name (and numbers for a series). Saved as hand edits.
 */
@Composable
fun AddGroupFlow(view: LibraryView, books: List<Book>, onDone: (String?) -> Unit) {
    val app = ThorApp.instance
    val scope = rememberCoroutineScope()
    val isSeries = view == LibraryView.SERIES
    val what = if (isSeries) "series" else "author"
    var step by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>() }
    val choices = remember { mutableStateMapOf<Long, Choice>() }
    var conflictAt by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    val numbers = remember { mutableStateMapOf<Long, String>() }

    val pickedBooks = books.filter { it.id in picked }
    fun hasOne(b: Book) = if (isSeries) !b.series.isNullOrBlank() else !b.author.isNullOrBlank()
    val conflicts = pickedBooks.filter(::hasOne)
    val included = pickedBooks.filter { choices[it.id] != Choice.SKIP }
    val existing = LibraryLogic.names(books, view)

    // Back / B: one step back, or close.
    androidx.compose.runtime.DisposableEffect(step, conflictAt) {
        val prev = app.libraryBack
        app.libraryBack = {
            when {
                step == 1 && conflictAt > 0 -> { conflictAt--; true }
                step > 0 -> { step--; true }
                else -> { onDone(null); true }
            }
        }
        onDispose { app.libraryBack = prev }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone(null) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .clip(HouseStyle.PanelShape)
                .background(HouseStyle.PanelBackground)
                .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add $what · " + listOf("Pick books", "Books that already have one", "Name")[step],
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Chip("✕ Cancel") { onDone(null) }
            }
            when (step) {
                // ---- 1. pick books ----
                0 -> {
                    InputField(query, { query = it }, "Search title, author or series")
                    val q = query.trim()
                    val shown = LibraryLogic.sort(books, dev.thorreader.app.SortOrder.TITLE).filter { b ->
                        q.isEmpty() || b.title.contains(q, true) || b.author?.contains(q, true) == true || b.series?.contains(q, true) == true
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.id }) { b ->
                            val on = b.id in picked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) Accent.copy(alpha = 0.14f) else Color(0xFF1E1E1E))
                                    .focusClick { if (on) picked.remove(b.id) else picked.add(b.id) }
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BookCover(b, Modifier.size(32.dp, 46.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(b.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = listOfNotNull(b.author, b.series?.let { s -> s + (b.seriesNumber?.let { " #" + formatNumber(it) } ?: "") }).joinToString("  ·  ")
                                    if (sub.isNotEmpty()) Text(sub, color = SubtleText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(if (on) "✓" else "", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${picked.size} picked", color = SubtleText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Chip("Next", picked.isNotEmpty()) {
                            if (picked.isEmpty()) return@Chip
                            choices.clear()
                            conflictAt = 0
                            pickedBooks.forEach { b -> if (isSeries) numbers[b.id] = b.seriesNumber?.let { formatNumber(it) } ?: "" }
                            step = if (conflicts.isEmpty()) 2 else 1
                        }
                    }
                }
                // ---- 2. books that already have an author / series ----
                1 -> {
                    val b = conflicts.getOrNull(conflictAt)
                    if (b == null) {
                        LaunchedEffect(Unit) { step = 2 }
                    } else {
                        val question = if (isSeries) "Move '${b.title}' from series '${b.series}' to the new series?"
                        else "'${b.title}' is by '${b.author}'. Move it to the new author, or add the new author alongside?"
                        Text(question, color = Color.White, fontSize = 16.sp)
                        Text("${conflictAt + 1} of ${conflicts.size}", color = SubtleText, fontSize = 12.sp)
                        fun choose(c: Choice) {
                            choices[b.id] = c
                            if (conflictAt + 1 < conflicts.size) conflictAt++ else step = 2
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Chip("Move", true) { choose(Choice.MOVE) }
                            if (!isSeries) Chip("Add") { choose(Choice.ADD) }
                            Chip("Cancel") { choose(Choice.SKIP) }
                        }
                        Text("Cancel leaves this book out.", color = SubtleText, fontSize = 12.sp)
                    }
                }
                // ---- 3. the name (and numbers for a series) ----
                else -> {
                    if (included.isEmpty()) {
                        Text("No books left to add.", color = SubtleText, fontSize = 14.sp)
                        Chip("Back") { step = 0 }
                    } else {
                        InputField(name, { name = it }, if (isSeries) "Series name" else "Author name", autoFocus = true)
                        val typed = name.trim()
                        val suggestions = if (typed.isEmpty()) emptyList() else existing.filter { it.contains(typed, true) && !LibraryLogic.sameName(it, typed) }.take(6)
                        if (suggestions.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                suggestions.forEach { s -> Chip(s) { name = s } }
                            }
                        }
                        LibraryLogic.matchExisting(typed, existing)?.let {
                            Text("'$it' already exists: the books will be added to it.", color = Accent, fontSize = 13.sp)
                        }
                        if (isSeries) {
                            Text("Number in the series (volume or issue)", color = SubtleText, fontSize = 12.sp)
                            included.forEach { b ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(b.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Box(Modifier.width(90.dp)) {
                                        InputField(numbers[b.id] ?: "", { v -> numbers[b.id] = v.filter { c -> c.isDigit() || c == '.' } }, "#")
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Chip("Save", typed.isNotEmpty()) {
                                if (typed.isEmpty()) return@Chip
                                val merged = LibraryLogic.matchExisting(typed, existing)
                                val target = merged ?: typed
                                val updated = included.map { b ->
                                    if (isSeries) LibraryLogic.withSeries(b, target, numbers[b.id]?.toFloatOrNull())
                                    else LibraryLogic.withAuthor(b, target, move = choices[b.id] != Choice.ADD)
                                }
                                scope.launch(Dispatchers.IO) {
                                    updated.forEach { app.db.books().saveMeta(it) }
                                    Log.i(TAG, "Add $what '$target' (${if (merged != null) "merged into existing" else "new"}): ${updated.size} book(s)")
                                }
                                val n = updated.size
                                onDone(
                                    if (merged != null) "Added $n book${if (n == 1) "" else "s"} to the existing $what '$merged'"
                                    else "New $what '$target' with $n book${if (n == 1) "" else "s"}",
                                )
                            }
                            Chip("Back") { step = if (conflicts.isEmpty()) 0 else 1; conflictAt = 0 }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputField(value: String, onChange: (String) -> Unit, hint: String, autoFocus: Boolean = false, number: Boolean = false) {
    // Typing happens on the bottom screen (keyboard there); this shows the text live.
    TypingField(
        value, onChange,
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2A2A2A))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        hint = hint,
        kind = if (number) TypingSession.Kind.NUMBER else TypingSession.Kind.TEXT,
        autoStart = autoFocus,
    )
}
