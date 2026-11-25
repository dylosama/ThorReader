package dev.thorreader.bookloader.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.bookloader.source.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit

/** Thor Reader's house style, Bookloader's copy: black, translucent panels, hairlines, one accent. */
object House {
    val Accent = Color(0xFFE0A050)
    val Gold = Color(0xFFF2B544)
    val Panel = Color(0xFF1A1A1A)
    val Chip = Color(0xFF2E2E2E)
    /** Thor Reader's library: screen, rows, author line, text on the accent, the round buttons. */
    val Screen = Color(0xFF101010)
    val Row = Color(0xFF1A1A1A)
    val Byline = Color(0xFFB8B8B8)
    val OnAccent = Color(0xFF1A1A1A)
    val Round = Color(0xFF262626)
    val Hairline = Color(0x33FFFFFF)
    val Subtle = Color(0xB3FFFFFF)
    val Faint = Color(0xFF888888)
    val Warning = Color(0xFFFF8A80)
    val Ok = Color(0xFF5CBF6A)
    val PanelShape = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(50)
}

/**
 * Tap, d-pad centre / Enter, or the controller's A activates; the focused item gets the accent
 * outline. Everything clickable in Bookloader uses this, so the whole app works with a controller.
 */
fun Modifier.thorClick(shape: Shape = RoundedCornerShape(10.dp), enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) House.Accent else Color.Transparent, shape)
        .onKeyEvent {
            if (enabled && it.key == Key.ButtonA) {
                if (it.type == KeyEventType.KeyUp) onClick()
                true
            } else false
        }
        .clip(shape)
        .clickable(enabled = enabled, onClick = onClick)
}

@Composable
fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Thor Reader's Chip: 14 sp on dark grey; the selected one filled with the accent, bold.
    Text(
        label,
        color = when {
            !enabled -> Color(0x66FFFFFF)
            selected -> House.OnAccent
            else -> Color.White
        },
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .clip(House.Pill)
            .background(if (selected) House.Accent else House.Chip)
            .thorClick(House.Pill, enabled, onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Thor Reader's sort-row chip: quiet text; the active one in the accent on a faint accent pill. */
@Composable
fun RowChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) House.Accent else House.Subtle,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(House.Pill)
            .background(if (active) House.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .thorClick(House.Pill, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Thor Reader's round top-bar button (44 dp, quiet, hairline). */
@Composable
fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(House.Round)
            .border(1.dp, House.Hairline, androidx.compose.foundation.shape.CircleShape)
            .thorClick(androidx.compose.foundation.shape.CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The house badge: one line of small white text on a translucent black pill (top-left status). */
@Composable
fun HouseBadge(t: String, dot: Color? = null) {
    Row(
        Modifier.clip(House.Pill).background(Color(0x99000000)).border(1.dp, House.Hairline, House.Pill).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot?.let { Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(it)); Spacer(Modifier.width(6.dp)) }
        Text(t, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun SectionLabel(t: String) {
    Text(t.uppercase(), color = House.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
fun Badge(t: String, color: Color = House.Ok) {
    Text(
        t,
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(House.Pill).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), plain: Boolean = false, content: @Composable () -> Unit) {
    // plain = Thor Reader's library row (rounded 10 dp, #1A1A1A, no outline); else a hairlined panel.
    val shape = if (plain) RoundedCornerShape(10.dp) else House.PanelShape
    Column(
        modifier
            .clip(shape)
            .background(House.Panel)
            .then(if (plain) Modifier else Modifier.border(1.dp, House.Hairline, shape))
            .padding(padding),
    ) { content() }
}

/** One-line text field in the house style. */
@Composable
fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    /** No box of its own (inside the search pill). */
    bare: Boolean = false,
    autoFocus: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(House.Accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onDone() }),
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (bare) Modifier.padding(vertical = 10.dp)
                else Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(House.Chip)
                    .border(if (focused) 2.dp else 1.dp, if (focused) House.Accent else House.Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = House.Faint, fontSize = 16.sp)
                inner()
            }
        },
    )
}

/** Cover thumbnails: fetched once, decoded small (about 240 px tall), kept in a small cache. */
object Covers {
    private val cache = object : LruCache<String, ImageBitmap>(64) {}
    /** At most 4 pictures downloading at once (no pause between them, unlike searches). */
    private val slots = kotlinx.coroutines.sync.Semaphore(4)

    /** The first of [urls] that loads ("openlibrary:title|author" = look the cover up on Open Library). */
    suspend fun load(urls: List<String>): ImageBitmap? {
        for (u in urls) {
            cache.get(u)?.let { return it }
            val img = slots.withPermit { withContext(Dispatchers.IO) { fetch(u) } }
            if (img != null) {
                cache.put(u, img)
                return img
            }
        }
        return null
    }

    private fun fetch(url: String): ImageBitmap? = runCatching {
        val real = if (url.startsWith(OPEN_LIBRARY)) openLibraryCover(url.removePrefix(OPEN_LIBRARY)) ?: return null else url
        val bytes = Http.bytes(real, max = 8L shl 20, polite = false)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outHeight / (s * 2) >= 240) s *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
    }.onFailure { android.util.Log.d(dev.thorreader.bookloader.TAG, "Cover not loaded: ${it.message}") }.getOrNull()

    const val OPEN_LIBRARY = "openlibrary:"

    /** Open Library's cover for "title|author" (one small search, then the picture). */
    private fun openLibraryCover(q: String): String? {
        val (title, author) = q.split('|').let { it[0] to it.getOrNull(1).orEmpty() }
        val url = "https://openlibrary.org/search.json?limit=1&fields=cover_i&title=${Http.enc(title)}" +
            (if (author.isNotBlank()) "&author=${Http.enc(author)}" else "")
        val o = org.json.JSONObject(Http.text(url, accept = "application/json").first)
        val id = o.optJSONArray("docs")?.optJSONObject(0)?.optLong("cover_i", 0L) ?: 0L
        return if (id > 0) "https://covers.openlibrary.org/b/id/$id-M.jpg" else null
    }
}

@Composable
fun Cover(url: String?, modifier: Modifier, alt: String? = null) {
    val img by produceState<ImageBitmap?>(null, url, alt) { value = Covers.load(listOfNotNull(url, alt)) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(House.Chip)) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
fun Message(t: String, color: Color = House.Subtle) {
    Text(t, color = color, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
}
