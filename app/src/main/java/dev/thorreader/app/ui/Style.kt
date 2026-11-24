package dev.thorreader.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.data.Book
import dev.thorreader.app.ThorApp
import dev.thorreader.app.TutorialStep
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The house style, taken from the "▾ more – drag up" overflow badge: small, quiet, translucent.
 * Everything small (labels, status, toasts, running header, page numbers) uses [HouseBadge].
 * See PLAN.md "Visual style".
 */
object HouseStyle {
    val TextSize = 11.sp
    val TextWeight = FontWeight.Normal
    val TextColor = Color.White
    val Background = Color(0x99000000)
    val Shape = RoundedCornerShape(50)
    val PaddingH = 10.dp
    val PaddingV = 3.dp
    val EdgeGap = 6.dp

    /** Larger surfaces (popups, menu panel): same translucent family. */
    val PanelBackground = Color(0xE0141414)
    val PanelShape = RoundedCornerShape(14.dp)
    val Hairline = Color(0x33FFFFFF)
    val ItemTextSize = 13.sp
    val ItemMinHeight = 40.dp
    val Focus = Color(0x33FFFFFF)
    val Warning = Color(0xFFFF8A80)
    val Subtle = Color(0xB3FFFFFF)

    /** Height reserved above/below reflowable text for the running header and page number. */
    val HeaderBand = 30.dp
}

/** The house badge: one line of small white text on a translucent black pill. */
@Composable
fun HouseBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = HouseStyle.TextColor,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .clip(HouseStyle.Shape)
            .background(HouseStyle.Background)
            .padding(horizontal = HouseStyle.PaddingH, vertical = HouseStyle.PaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(
            text,
            color = color,
            fontSize = HouseStyle.TextSize,
            fontWeight = HouseStyle.TextWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** House badge with a tiny spinner: background work in progress. */
@Composable
fun HouseProgressBadge(text: String, modifier: Modifier = Modifier) {
    HouseBadge(text, modifier) {
        CircularProgressIndicator(color = HouseStyle.TextColor, strokeWidth = 1.5.dp, modifier = Modifier.size(9.dp))
    }
}

/** A book's cached cover, or a generated placeholder from its title and author. */
@Composable
fun BookCover(book: Book, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, book.coverPath) {
        value = book.coverPath?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp))) {
        val img = image
        if (img != null) {
            Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            PlaceholderCover(book.title, book.author)
        }
    }
}

@Composable
private fun PlaceholderCover(title: String, author: String?) {
    // A muted colour picked from the title, so each book looks different but calm.
    val hue = ((title.hashCode() and 0x7FFFFFFF) % 360).toFloat()
    val top = Color.hsv(hue, 0.35f, 0.38f)
    val bottom = Color.hsv(hue, 0.45f, 0.22f)
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))).padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = HouseStyle.TextColor,
            fontSize = HouseStyle.TextSize,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        if (author != null) {
            Text(
                author,
                color = HouseStyle.Subtle,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Clickable that also works with the d-pad: focusable, a thin accent outline while focused,
 * and A / Enter / centre press activate it (as does a tap).
 */
fun Modifier.focusClick(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp), onClick: () -> Unit): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) Accent else Color.Transparent, shape)
        .onKeyEvent {
            if (it.key == Key.ButtonA) {
                if (it.type == KeyEventType.KeyUp) onClick()
                true
            } else {
                false
            }
        }
        .clickable(onClick = onClick)
}

/** Which way a tutorial badge's little arrow points. */
enum class ArrowDir { UP, DOWN, NONE }

/**
 * First-run tutorial badge: the house pill with a small arrow toward what it explains. While
 * shown it is registered in [ThorApp.tutorialVisible], so a tap anywhere dismisses it.
 */
@Composable
fun TutorialBadge(
    step: TutorialStep,
    text: String,
    arrow: ArrowDir,
    modifier: Modifier = Modifier,
    arrowAlign: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    val app = ThorApp.instance
    DisposableEffect(step) {
        app.tutorialVisible += step
        onDispose { app.tutorialVisible -= step }
    }
    Column(modifier, horizontalAlignment = arrowAlign) {
        if (arrow == ArrowDir.UP) BadgeArrow(up = true)
        HouseBadge(text)
        if (arrow == ArrowDir.DOWN) BadgeArrow(up = false)
    }
}

@Composable
private fun BadgeArrow(up: Boolean) {
    Canvas(Modifier.padding(horizontal = 16.dp).size(12.dp, 6.dp)) {
        val p = Path().apply {
            if (up) {
                moveTo(0f, size.height); lineTo(size.width / 2f, 0f); lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f); lineTo(size.width / 2f, size.height); lineTo(size.width, 0f)
            }
            close()
        }
        drawPath(p, HouseStyle.Background)
    }
}

/** Observes every touch on a screen without taking it: dismisses tutorial badges. */
fun Modifier.tutorialTapCatcher(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        ThorApp.instance.tutorialTapped()
    }
}
