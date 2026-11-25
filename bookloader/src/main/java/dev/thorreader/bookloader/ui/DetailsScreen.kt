package dev.thorreader.bookloader.ui

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.LocalHost
import dev.thorreader.bookloader.Screen
import dev.thorreader.bookloader.TAG
import dev.thorreader.bookloader.source.BookMeta
import dev.thorreader.bookloader.source.CatalogItem
import dev.thorreader.bookloader.source.ContentType
import dev.thorreader.bookloader.source.Entry
import dev.thorreader.bookloader.source.ItemDetails
import dev.thorreader.bookloader.work.JobAction
import dev.thorreader.bookloader.work.JobSpec
import dev.thorreader.bookloader.work.Jobs
import dev.thorreader.bookloader.work.ThorImportClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the user changed on the "Details for Thor Reader" screen. Blank = keep the catalogue's
 * value. Applied to every selected chapter / edition; [startNumber] numbers them in order.
 */
data class MetaOverride(
    val title: String = "",
    val authors: String = "",
    val series: String = "",
    val startNumber: String = "",
    val type: ContentType? = null,
    val genres: String = "",
    /** null = automatic. */
    val rtl: Boolean? = null,
    val mode: Int? = null,
) {
    fun apply(base: BookMeta, index: Int, single: Boolean): BookMeta {
        fun list(s: String) = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return base.copy(
            title = if (single && title.isNotBlank()) title.trim() else base.title,
            authors = list(authors).ifEmpty { base.authors },
            series = series.trim().ifEmpty { base.series },
            seriesNumber = startNumber.trim().toFloatOrNull()?.let { it + index } ?: base.seriesNumber,
            type = type ?: base.type,
            genres = list(genres).ifEmpty { base.genres },
            rtl = rtl ?: base.rtl,
            mode = mode ?: base.mode,
        )
    }
}

class DetailsState(val item: CatalogItem) {
    var details by mutableStateOf<ItemDetails?>(null)
    var error by mutableStateOf<String?>(null)
    val selected = mutableStateListOf<String>()
    var override by mutableStateOf(MetaOverride())
    var warning by mutableStateOf<String?>(null)
    var queued by mutableStateOf<String?>(null)
    /** Show whole volumes instead of chapters (MangaDex). */
    var byVolume by mutableStateOf(false)

    fun chosen(): List<Entry> = details?.let { d -> if (byVolume && d.volumes.isNotEmpty()) d.volumes else d.entries }.orEmpty().filter { it.id in selected }

    /** The metadata each chosen entry will be saved / imported with. */
    fun metas(): List<Pair<Entry, BookMeta>> {
        val d = details ?: return emptyList()
        val list = chosen()
        return list.mapIndexed { i, e -> e to override.apply(BookMeta.defaults(d, e, i), i, list.size == 1) }
    }
}

@Composable
fun DetailsScreen(s: DetailsState) {
    val host = LocalHost.current
    val ctx = LocalContext.current
    val app = BookloaderApp.instance
    LaunchedEffect(s.item) {
        if (s.details == null) {
            try {
                val src = app.sources.get(s.item.sourceId) ?: error("This source was removed")
                val d = withContext(Dispatchers.IO) { src.details(s.item) }
                s.details = d
                // Volumes first: whole volumes are the usual way to take manga to Thor Reader.
                s.byVolume = d.volumes.isNotEmpty()
                // One edition (or a book's first format): pick it; chapters start unselected.
                val shown = if (s.byVolume) d.volumes else d.entries
                if (d.item.type == ContentType.BOOK || shown.size == 1) shown.firstOrNull()?.let { s.selected += it.id }
            } catch (e: Exception) {
                Log.w(TAG, "Details of '${s.item.title}' failed", e)
                s.error = e.message ?: "Couldn't load the details"
            }
        }
    }

    fun enqueue(action: JobAction) {
        val list = s.metas()
        list.forEach { (e, m) -> Jobs.enqueue(ctx, JobSpec(action, s.item.sourceId, s.item, e, m)) }
        s.queued = "${list.size} queued - see Queue"
        s.selected.clear()
        host.go(Screen.Queue)
    }

    fun importNow() {
        when {
            !ThorImportClient.installed(ctx) -> s.warning = "Thor Reader isn't installed on this device."
            !ThorImportClient.permitted(ctx) -> s.warning = "Thor Reader won't accept imports from this copy of Bookloader (it must be signed with the same key, and installed after Thor Reader)."
            else -> enqueue(JobAction.IMPORT)
        }
    }

    fun saveToStorage() {
        if (app.downloadFolder == null) host.pickDownloadFolder { enqueue(JobAction.SAVE) } else enqueue(JobAction.SAVE)
    }

    val d = s.details
    Column {
    TopBar {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton()
            Text(s.item.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp))
            if (app.inThorReader(s.item.sourceId, s.item.id)) Badge("In Thor Reader")
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Cover(s.item.coverUrl, Modifier.size(140.dp, 210.dp), s.item.altCoverUrl)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.item.authors.joinToString(", ").ifEmpty { "Unknown author" }, color = House.Subtle, fontSize = 16.sp)
                    Text("${s.item.type.label}  ·  ${d?.formatLabel ?: "…"}", color = House.Faint, fontSize = 14.sp)
                    if (d != null && d.tags.isNotEmpty()) {
                        Text(d.tags.joinToString("  ·  "), color = House.Accent, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    (d?.synopsis ?: s.item.summary)?.let { Text(it, color = Color.White, fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                    d?.note?.let { Text(it, color = House.Faint, fontSize = 12.sp) }
                }
            }
        }
        s.error?.let { e -> item { Message(e, House.Warning) } }
        if (d == null && s.error == null) item { Message("Loading…") }
        if (d != null) {
            item {
                // --- actions ---
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val n = s.selected.size
                    Chip("Import to Thor Reader${if (n > 1) " ($n)" else ""}", selected = n > 0, enabled = n > 0) { importNow() }
                    Chip("Download to storage", enabled = n > 0) { saveToStorage() }
                    Chip("Details for Thor Reader…", enabled = n > 0) { host.go(Screen.Meta(s)) }
                }
            }
            s.warning?.let { w ->
                item {
                    Panel {
                        Message(w, House.Warning)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Save to storage instead", selected = true) { s.warning = null; saveToStorage() }
                            Chip("Cancel") { s.warning = null }
                        }
                    }
                }
            }
            val list = if (s.byVolume && d.volumes.isNotEmpty()) d.volumes else d.entries
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.volumes.isNotEmpty()) {
                        // Thor Reader's sort-row chips: one chapter per file, or a whole volume per file.
                        RowChip("${d.entriesLabel} (${d.entries.size})", !s.byVolume) { s.byVolume = false; s.selected.clear() }
                        RowChip("Volumes (${d.volumes.size})", s.byVolume) { s.byVolume = true; s.selected.clear() }
                    } else {
                        SectionLabel("${d.entriesLabel} (${d.entries.size})")
                    }
                    if (list.size > 1) {
                        Chip("Select all") { s.selected.clear(); s.selected += list.map { it.id } }
                        Chip("Select none") { s.selected.clear() }
                    }
                }
            }
            if (s.byVolume && d.volumes.isNotEmpty()) item { Message("Each volume is downloaded chapter by chapter and saved as one book.", House.Faint) }
            if (list.isEmpty()) item { Message("Nothing downloadable here.") }
            items(list, key = { it.id }) { e ->
                val on = e.id in s.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .thorClick(RoundedCornerShape(10.dp)) { if (on) s.selected -= e.id else s.selected += e.id }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(if (on) "☑" else "☐", color = if (on) House.Accent else House.Subtle, fontSize = 20.sp)
                    Text(e.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(e.format, color = House.Faint, fontSize = 13.sp)
                }
            }
        }
    }
    }
}

/** "Details for Thor Reader": tweak what the selected downloads are saved / imported with. */
@Composable
fun MetaScreen(s: DetailsState) {
    val host = LocalHost.current
    val first = s.metas().firstOrNull()?.second ?: BookMeta()
    val single = s.selected.size == 1
    var o by androidx.compose.runtime.remember { mutableStateOf(s.override) }
    Column {
    TopBar {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton()
            BarTitle("Details for Thor Reader")
            Chip("Save", selected = true) { s.override = o; host.back() }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Message(
                (if (single) "For this download. " else "For all ${s.selected.size} selected downloads. ") +
                    "Leave a field empty to keep what the catalogue says. Thor Reader treats these as your own edits (its online lookup won't change them).",
                House.Faint,
            )
        }
        if (single) item { Labeled("Title") { Field(o.title, { o = o.copy(title = it) }, first.title ?: "", Modifier.fillMaxWidth()) } }
        item { Labeled("Authors (comma-separated)") { Field(o.authors, { o = o.copy(authors = it) }, first.authors.joinToString(", "), Modifier.fillMaxWidth()) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Labeled("Series", Modifier.weight(1f)) { Field(o.series, { o = o.copy(series = it) }, first.series ?: "(none)", Modifier.fillMaxWidth()) }
                Labeled(if (single) "Number" else "First number (then +1 each)", Modifier.width(220.dp)) {
                    Field(o.startNumber, { o = o.copy(startNumber = it) }, first.seriesNumber?.let { n -> if (n % 1f == 0f) n.toInt().toString() else n.toString() } ?: "auto", Modifier.fillMaxWidth(), keyboard = KeyboardType.Decimal)
                }
            }
        }
        item { Labeled("Genres / tags (comma-separated)") { Field(o.genres, { o = o.copy(genres = it) }, first.genres.joinToString(", "), Modifier.fillMaxWidth()) } }
        item {
            Labeled("Type") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("As found (${first.type?.label ?: "auto"})", o.type == null) { o = o.copy(type = null) }
                    ContentType.entries.forEach { t -> Chip(t.label, o.type == t) { o = o.copy(type = t) } }
                }
            }
        }
        item {
            Labeled("Reading direction") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Automatic", o.rtl == null) { o = o.copy(rtl = null) }
                    Chip("Left to right", o.rtl == false) { o = o.copy(rtl = false) }
                    Chip("Right to left", o.rtl == true) { o = o.copy(rtl = true) }
                }
            }
        }
        item {
            Labeled("Thor Reader mode") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Thor Reader decides", o.mode == null) { o = o.copy(mode = null) }
                    listOf(1 to "1 · Book", 2 to "2 · Book reversed", 3 to "3 · Top", 4 to "4 · Bottom").forEach { (n, l) ->
                        Chip(l, o.mode == n) { o = o.copy(mode = n) }
                    }
                }
            }
        }
        item {
            SectionLabel("Preview")
        }
        val preview = s.chosen().take(6).mapIndexed { i, e -> o.apply(BookMeta.defaults(s.details!!, e, i), i, single) }
        items(preview) { m ->
            Text(
                "${m.title}" + (m.series?.let { "  ·  $it #${m.seriesNumber?.let { n -> if (n % 1f == 0f) n.toInt() else n } ?: "?"}" } ?: ""),
                color = House.Subtle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    }
}

@Composable
private fun Labeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = House.Faint, fontSize = 13.sp)
        content()
    }
}
