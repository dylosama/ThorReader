package dev.thorreader.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.ThorApp

/** Things only an Activity can do (system file pickers). */
interface HostActions {
    fun pickFiles()
    fun pickFolder()

    /** The system picker for one picture (internal storage, SD card...); [onPicked] gets it. */
    fun pickImage(onPicked: (android.net.Uri) -> Unit)
}

val LocalHost = compositionLocalOf<HostActions?> { null }

/**
 * The app's accent ("Theme colour" in Settings): highlights, active chips, toggles, rings,
 * progress, selection and buttons. One token, read from the setting, so a change applies
 * everywhere at once. Text drawn on it uses [OnAccent].
 */
val Accent: Color get() = Color(accentArgb())

/** Black or white, whichever reads better on [Accent]. */
val OnAccent: Color get() = onColorFor(accentArgb())

private fun accentArgb(): Int = if (ThorApp.ready) ThorApp.instance.settings.accentColor else dev.thorreader.app.AccentMath.DEFAULT

/** Black or white text for a background colour (the one with the higher WCAG contrast). */
fun onColorFor(argb: Int): Color = if (dev.thorreader.app.AccentMath.prefersDarkText(argb)) Color.Black else Color.White
val PanelColor = Color(0xF0181818)
val SubtleText = Color(0xFFAAAAAA)

/**
 * Lays [content] out with width and height swapped, then rotates it by [degrees] (+90 / -90)
 * around the centre so it exactly fills this box. Touch input is rotated too.
 */
@Composable
fun RotatedBox(degrees: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = { Box { content() } }, modifier = modifier.fillMaxSize()) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val child = measurables.first().measure(Constraints.fixed(h, w))
        layout(w, h) {
            child.placeWithLayer((w - child.width) / 2, (h - child.height) / 2) { rotationZ = degrees }
        }
    }
}

/** Small drawing of the Thor showing which screens a mode uses and which page goes where. */
@Composable
fun ModeIcon(mode: ReadingMode, selected: Boolean, modifier: Modifier = Modifier.size(56.dp, 44.dp)) {
    val on = if (selected) Accent else Color(0xFFDDDDDD)
    val off = Color(0xFF555555)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (mode) {
            ReadingMode.TOP, ReadingMode.BOTTOM -> {
                // Clamshell held normally: wide top screen above narrower bottom screen.
                val gap = h * 0.08f
                val sh = (h - gap) / 2
                val topW = w * 0.9f
                val botW = topW * 1240f / 1920f * 1.25f
                screen(Offset((w - topW) / 2, 0f), Size(topW, sh), if (mode == ReadingMode.TOP) on else off, mode == ReadingMode.TOP)
                screen(Offset((w - botW) / 2, sh + gap), Size(botW, sh), if (mode == ReadingMode.BOTTOM) on else off, mode == ReadingMode.BOTTOM)
                if (mode == ReadingMode.TOP) {
                    drawLine(Color.Black, Offset(w / 2, h * 0.06f), Offset(w / 2, sh - h * 0.06f), 2f)
                }
            }
            ReadingMode.BOOK, ReadingMode.BOOK_REVERSED -> {
                // Held sideways: two tall screens side by side. Top screen is the bigger one.
                val gap = w * 0.08f
                val big = Size(w * 0.5f, h)
                val small = Size(w * 0.5f - gap, h * 0.7f)
                val topOnLeft = mode == ReadingMode.BOOK
                val leftSize = if (topOnLeft) big else small
                val rightSize = if (topOnLeft) small else big
                screen(Offset(0f, (h - leftSize.height) / 2), leftSize, on, true)
                screen(Offset(w - rightSize.width, (h - rightSize.height) / 2), rightSize, on, true)
                // Text lines showing upright pages.
                for (side in 0..1) {
                    val sz = if (side == 0) leftSize else rightSize
                    val x0 = if (side == 0) 0f else w - sz.width
                    val y0 = (h - sz.height) / 2
                    for (i in 1..3) {
                        val y = y0 + sz.height * (0.25f * i)
                        drawLine(Color.Black.copy(alpha = 0.6f), Offset(x0 + sz.width * 0.2f, y), Offset(x0 + sz.width * 0.8f, y), 2f)
                    }
                }
            }
        }
    }
}

private fun DrawScope.screen(o: Offset, s: Size, c: Color, filled: Boolean) {
    if (filled) drawRoundRect(c, o, s, CornerRadius(4f)) else drawRoundRect(c, o, s, CornerRadius(4f), style = Stroke(3f))
}

/** A labelled mode button used in the reader menu and the library. */
@Composable
fun ModeButton(mode: ReadingMode, selected: Boolean, width: Dp = 104.dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f).compositeOver(Color(0xFF262626)) else Color(0xFF262626))
            .border(2.dp, if (selected) Accent else Color(0xFF3A3A3A), RoundedCornerShape(10.dp))
            .focusClick(RoundedCornerShape(10.dp), onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeIcon(mode, selected)
        Text(
            "${mode.number}. ${mode.title}",
            color = if (selected) Accent else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Brief label after a mode change or when a book opens (menu, or the Select button when turned
 * on), in the top-left corner: the screen's content is already turned for the mode, so it's the
 * top left as the Thor is held. Off in Settings -> General -> "Mode badge when reading starts".
 */
@Composable
fun ModeToastOverlay(modifier: Modifier = Modifier) {
    val toast = ThorApp.instance.modeToast
    Box(modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp), contentAlignment = Alignment.TopStart) {
        AnimatedVisibility(visible = toast != null, enter = fadeIn(), exit = fadeOut()) {
            val mode = toast?.mode ?: return@AnimatedVisibility
            // House style: one quiet translucent pill with the mode's little icon.
            // Nearly solid in the corner: it sits over the page's first line for a moment.
            HouseBadge(
                "Mode ${mode.number} · ${mode.title}",
                Modifier.clip(HouseStyle.Shape).background(Color(0xF0181818)).border(1.dp, HouseStyle.Hairline, HouseStyle.Shape),
            ) {
                ModeIcon(mode, true, Modifier.size(20.dp, 16.dp))
            }
        }
    }
}

/** Simple pill button for menus. */
@Composable
fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) OnAccent else Color.White,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Color(0xFF2E2E2E))
            .focusClick(RoundedCornerShape(50), onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Short names for the compact mode row in the reader menu. */
val ReadingMode.shortLabel: String
    get() = when (this) {
        ReadingMode.BOOK -> "Book"
        ReadingMode.BOOK_REVERSED -> "Reversed"
        ReadingMode.TOP -> "Top"
        ReadingMode.BOTTOM -> "Bottom"
    }

/** One of four equal compact mode buttons (icon + short label), current one in the theme colour. */
@Composable
fun androidx.compose.foundation.layout.RowScope.ModeTile(mode: ReadingMode, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f).compositeOver(Color(0xFF262626)) else Color(0xFF262626))
            .border(2.dp, if (selected) Accent else Color(0xFF3A3A3A), RoundedCornerShape(10.dp))
            .focusClick(RoundedCornerShape(10.dp), onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeIcon(mode, selected, Modifier.size(40.dp, 32.dp))
        Text(mode.shortLabel, color = if (selected) Accent else Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}
