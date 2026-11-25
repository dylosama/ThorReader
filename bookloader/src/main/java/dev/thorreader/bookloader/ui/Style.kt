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

/** Thor Reader's house style, Bookloader's copy: black, translucent panels, hairlines, one accent. */
object House {
    val Accent = Color(0xFFE0A050)
    val Gold = Color(0xFFF2B544)
    val Panel = Color(0xFF141414)
    val Chip = Color(0xFF262626)
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
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(House.Pill)
            .background(if (selected) House.Accent else House.Chip)
            .border(1.dp, House.Hairline, House.Pill)
            .thorClick(House.Pill, enabled, onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> Color(0x66FFFFFF)
                selected -> Color.Black
                else -> Color.White
            },
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
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
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(House.PanelShape)
            .background(House.Panel)
            .border(1.dp, House.Hairline, House.PanelShape)
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
) {
    var focused by remember { mutableStateOf(false) }
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
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(House.Chip)
            .border(if (focused) 2.dp else 1.dp, if (focused) House.Accent else House.Hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
    private val cache = object : LruCache<String, ImageBitmap>(48) {}

    suspend fun load(url: String): ImageBitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Http.bytes(url, max = 8L shl 20)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outHeight / (s * 2) >= 240) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

@Composable
fun Cover(url: String?, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(null, url) { value = url?.let { Covers.load(it) } }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(House.Chip)) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
fun Message(t: String, color: Color = House.Subtle) {
    Text(t, color = color, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
}
