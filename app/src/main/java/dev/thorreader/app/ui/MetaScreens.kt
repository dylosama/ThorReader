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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.data.saveMeta
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.importer.formatNumber
import dev.thorreader.app.metadata.Candidate
import dev.thorreader.app.metadata.Genres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val COMICVINE_KEY_URL = "https://comicvine.gamespot.com/api"

/** A full-screen overlay panel in the house style (library only). */
@Composable
private fun OverlayPanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
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
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("✕ Close") { onClose() }
            }
            content()
        }
    }
}

/** One labelled text field. */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    hidden: Boolean = false,
    hint: String = "",
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = HouseStyle.Subtle, fontSize = HouseStyle.TextSize)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2A2A))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = Color(0xFF777777), fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().then(if (!singleLine) Modifier.heightIn(min = 80.dp) else Modifier),
            )
        }
    }
}

// ---- Settings --------------------------------------------------------------------------------

@Composable
fun SettingsPanel() {
    val app = ThorApp.instance
    val settings = app.settings
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(app.lookup.secrets.comicVineKey ?: "") }
    var show by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    OverlayPanel("Settings", onClose = { app.settingsOpen = false }) {
        SectionLabel("Book info")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Look up book info online", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Chip("On", settings.lookupOnline) {
                settings.updateLookupOnline(true)
                scope.launch(Dispatchers.IO) { app.lookup.enqueue(app.db.books().all().filter { it.lookupState == "off" }.map { it.id }, "turned on") }
            }
            Chip("Off", !settings.lookupOnline) { settings.updateLookupOnline(false) }
        }
        Text(
            "Only book details (title, authors, series, genres, description, cover) are looked up: manga on AniList, " +
                "books on Open Library, comics on ComicVine. Your books themselves never leave the device.",
            color = HouseStyle.Subtle, fontSize = 13.sp,
        )

        SectionLabel("ComicVine key (for comic info)")
        Field("API key", key, { key = it.trim(); message = null }, hidden = !show, hint = "Paste your ComicVine API key")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (show) "Hide" else "Show") { show = !show }
            Chip("Save key", true) {
                app.lookup.secrets.comicVineKey = key
                message = if (key.isEmpty()) "Key removed." else "Key saved. Comics without info will be looked up."
                if (key.isNotEmpty()) scope.launch(Dispatchers.IO) {
                    val ids = app.db.books().all().filter { it.lookupState in setOf("nokey", "badkey", "review", "nomatch", "done (ComicInfo / file name)") && runCatching { BookFormat.valueOf(it.format).comic }.getOrDefault(false) }.map { it.id }
                    app.lookup.enqueue(ids, "ComicVine key saved")
                }
            }
            Chip(if (testing) "Testing…" else "Test key") {
                if (testing || key.isEmpty()) {
                    if (key.isEmpty()) message = "Paste a key first."
                    return@Chip
                }
                testing = true
                scope.launch {
                    val err = app.lookup.testKey(key)
                    message = err ?: "Key works"
                    testing = false
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Get a free key") { app.openWebPage(COMICVINE_KEY_URL) }
            Text("Free account, then copy the key from that page.", color = HouseStyle.Subtle, fontSize = 13.sp)
        }
        message?.let { HouseBadge(it, color = if (it == "Key works" || it.startsWith("Key saved") || it == "Key removed.") Color.White else HouseStyle.Warning) }
        Text(
            "Each person needs their own key: ComicVine limits how often one key may be used. " +
                "The key is stored encrypted on this device only.",
            color = HouseStyle.Subtle, fontSize = 13.sp,
        )

        SectionLabel("Library lists")
        HiddenItemsSection()

        SectionLabel("Help")
        Chip("Show tutorial again") {
            app.restartTutorial()
            app.settingsOpen = false
        }

        SectionLabel("All books")
        Chip("Look up all books again") {
            scope.launch(Dispatchers.IO) {
                val all = app.db.books().all()
                all.forEach { app.db.books().setLookupState(it.id, "pending", it.needsReview, it.lookupAt) }
                app.lookup.enqueue(all.map { it.id }, "look up all again")
            }
        }
    }
}

@Composable
private fun SectionLabel(t: String) {
    Text(t.uppercase(), color = Color(0xFF888888), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

/** One-time hint after importing comics without a ComicVine key. House style. */
@Composable
fun ComicVineHint() {
    val app = ThorApp.instance
    Row(
        Modifier
            .fillMaxWidth()
            .clip(HouseStyle.PanelShape)
            .background(HouseStyle.Background)
            .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Comic info (writer, artist, genre) needs a free ComicVine key.",
            color = HouseStyle.TextColor, fontSize = HouseStyle.ItemTextSize, modifier = Modifier.weight(1f),
        )
        Chip("Get a free key") { app.openWebPage(COMICVINE_KEY_URL) }
        Chip("Settings") { app.settingsOpen = true; app.lookup.showKeyHint = false }
        Chip("✕") { app.lookup.showKeyHint = false }
    }
}

// ---- Edit info ---------------------------------------------------------------------------------

/** Edit a book's info by hand; edited fields are never changed by the online lookup. */
@Composable
fun EditInfoPanel(book: Book) {
    val app = ThorApp.instance
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(book.title) }
    var authors by remember { mutableStateOf(book.author ?: "") }
    var series by remember { mutableStateOf(book.series ?: "") }
    var number by remember { mutableStateOf(book.seriesNumber?.let { formatNumber(it) } ?: "") }
    var published by remember { mutableStateOf(book.year?.toString() ?: "") }
    var type by remember { mutableStateOf(book.typeOverride) } // null = automatic
    var genres by remember { mutableStateOf(Genres.split(book.genres).joinToString(", ")) }
    var description by remember { mutableStateOf(book.description ?: "") }
    var candidates by remember { mutableStateOf<List<Candidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Candidate?>(null) }

    fun close() { app.editInfoBook = null }

    OverlayPanel("Edit info", onClose = ::close) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BookCover(book, Modifier.size(72.dp, 104.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.fileName, color = HouseStyle.Subtle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val source = when (book.lookupState) {
                    "done" -> "Info found online"
                    "review", "nomatch" -> "Needs review: no confident match online"
                    "nokey" -> "Comic: add a ComicVine key in Settings for online info"
                    "badkey" -> "The ComicVine key was rejected"
                    "retry" -> "Online lookup will be retried"
                    "pending" -> "Waiting for online lookup"
                    "off" -> "Online lookup is off"
                    else -> "From the file"
                }
                Text(source, color = if (book.needsReview) HouseStyle.Warning else HouseStyle.Subtle, fontSize = 13.sp)
                listOfNotNull(book.publisher, book.year?.toString(), book.storyArc?.let { "Arc: $it" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = HouseStyle.Subtle, fontSize = 13.sp)
                }
            }
        }
        Field("Title", title, { title = it })
        Field("Authors (comma between names)", authors, { authors = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(3f)) { Field("Series", series, { series = it }) }
            Box(Modifier.weight(1f)) { Field("Number", number, { number = it.filter { c -> c.isDigit() || c == '.' } }) }
            Box(Modifier.weight(1f)) { Field("Published", published, { published = it.filter { c -> c.isDigit() }.take(4) }, hint = "Year") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Type", color = HouseStyle.Subtle, fontSize = HouseStyle.TextSize)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Automatic (${LibraryLogic.typeOf(book.copy(typeOverride = null))})", type == null) { type = null }
                LibraryLogic.TYPES.forEach { t -> Chip(t, type == t) { type = t } }
            }
        }
        Field("Genres (comma between genres)", genres, { genres = it }, hint = "e.g. Fantasy, Adventure")
        Field("Description", description, { description = it }, singleLine = false)

        // --- Search online ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (searching) "Searching…" else "Search online") {
                if (searching) return@Chip
                searching = true
                searchError = null
                val probe = book.copy(
                    title = title, author = authors.ifBlank { null }, series = series.ifBlank { null },
                    seriesNumber = number.toFloatOrNull(), typeOverride = type, isManga = book.isManga || type == LibraryLogic.MANGA,
                )
                scope.launch {
                    try {
                        candidates = withContext(Dispatchers.IO) { app.lookup.candidatesFor(probe) }
                        if (candidates.isNullOrEmpty()) searchError = "Nothing found. Try changing the title or series above."
                    } catch (e: Exception) {
                        searchError = when (e) {
                            is dev.thorreader.app.metadata.OfflineException -> "No connection. Check the Wi-Fi."
                            is dev.thorreader.app.metadata.BadKeyException -> "The ComicVine key was rejected (Settings)."
                            else -> "Search failed: ${e.message}"
                        }
                    } finally {
                        searching = false
                    }
                }
            }
            Text("Pick a result to fill in the fields, then Save.", color = HouseStyle.Subtle, fontSize = 13.sp)
        }
        searchError?.let { HouseBadge(it, color = HouseStyle.Warning) }
        candidates?.forEach { c ->
            CandidateRow(c, selected = picked == c) {
                scope.launch {
                    val full = withContext(Dispatchers.IO) { runCatching { app.lookup.complete(c) }.getOrDefault(c) }
                    picked = c
                    val comic = runCatching { BookFormat.valueOf(book.format).comic }.getOrDefault(false)
                    title = if (comic && full.series != null) full.series + (full.number?.let { " #" + formatNumber(it) } ?: "") else full.title
                    if (full.authors.isNotEmpty()) authors = full.authors.joinToString(", ")
                    full.series?.let { series = it }
                    full.number?.let { number = formatNumber(it) }
                    full.year?.let { published = it.toString() }
                    if (full.genres.isNotEmpty()) genres = (Genres.split(genres.replace(',', ';')) + full.genres).distinct().joinToString(", ")
                    full.description?.let { description = it }
                    if (full.isManga == true) type = LibraryLogic.MANGA
                }
            }
        }

        // --- Save ---
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Save", true) {
                val newGenres = Genres.join(genres.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() && Genres.isTag(it) })
                val updated = book.copy(
                    title = title.trim().ifEmpty { book.title },
                    author = authors.trim().ifEmpty { null },
                    series = series.trim().ifEmpty { null },
                    seriesNumber = number.toFloatOrNull(),
                    year = published.toIntOrNull()?.takeIf { it in 1000..2999 },
                    typeOverride = type,
                    genres = newGenres,
                    description = description.trim().ifEmpty { null },
                )
                // Remember which fields were changed by hand: the lookup leaves them alone.
                val changed = buildList {
                    if (updated.title != book.title) add("title")
                    if (updated.author != book.author) add("authors")
                    if (updated.series != book.series) add("series")
                    if (updated.seriesNumber != book.seriesNumber) add("number")
                    if (updated.year != book.year) add("year")
                    if (updated.genres != book.genres) add("genres")
                    if (updated.description != book.description) add("description")
                    if (updated.typeOverride != book.typeOverride) add("type")
                }
                val edited = ((book.editedFields?.split(',').orEmpty()) + changed).filter { it.isNotEmpty() }.distinct().joinToString(",").ifEmpty { null }
                val final = updated.copy(editedFields = edited, needsReview = false, lookupState = if (changed.isNotEmpty() || picked != null) "done" else book.lookupState)
                scope.launch(Dispatchers.IO) {
                    app.db.books().saveMeta(final)
                    android.util.Log.i(dev.thorreader.app.TAG, "Edit info saved for '${final.title}': edited ${changed.ifEmpty { listOf("nothing") }}")
                }
                close()
            }
            Chip("Cancel") { close() }
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, selected: Boolean, onPick: () -> Unit) {
    val app = ThorApp.instance
    val image by produceState<ImageBitmap?>(null, c.coverUrl) {
        value = c.coverUrl?.let { app.lookup.thumbnail(it)?.asImageBitmap() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color(0xFF1E1E1E))
            .focusClick(onClick = onPick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp, 64.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333))) {
            image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(c.year?.toString(), c.authors.take(2).joinToString(", ").ifEmpty { null }, c.publisher).joinToString(" · "),
                color = SubtleText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            HouseBadge(c.source)
            Spacer(Modifier.width(1.dp))
            Text("match ${(c.score * 100).toInt()}%", color = SubtleText, fontSize = 11.sp)
        }
    }
}
