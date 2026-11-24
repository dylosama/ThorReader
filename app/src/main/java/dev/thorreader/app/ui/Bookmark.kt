package dev.thorreader.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.PageTheme
import dev.thorreader.app.Settings
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.Side

/** Ribbon size and place: hanging from the top edge, this far in from the page's side. */
private const val RIBBON_W_DP = 20
private const val RIBBON_H_DP = 54
private const val RIBBON_INSET_DP = 22
private const val RIBBON_NOTCH_DP = 9

/** How far up a swipe on the ribbon must go to remove the bookmark. */
const val RIBBON_SWIPE_DP = 24

/** Is a touch at [pos] on the ribbon (generously, so it is easy to grab)? */
fun inRibbonZone(pos: Offset, widthPx: Int, side: Side, d: Density): Boolean = with(d) {
    val pad = 16.dp.toPx()
    val w = RIBBON_W_DP.dp.toPx()
    val inset = RIBBON_INSET_DP.dp.toPx()
    val left = if (side == Side.LEFT) inset else widthPx - inset - w
    pos.x in (left - pad)..(left + w + pad) && pos.y <= RIBBON_H_DP.dp.toPx() + pad * 1.5f
}

/** The slim notched ribbon shape, [w] x [h] px, top edge at y = 0. */
private fun DrawScope.ribbon(color: Color) {
    val w = size.width
    val h = size.height
    val notch = RIBBON_NOTCH_DP.dp.toPx()
    val p = Path().apply {
        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w / 2f, h - notch); lineTo(0f, h); close()
    }
    translate(1.5f, 2f) { drawPath(p, Color.Black.copy(alpha = 0.22f)) } // soft shadow
    drawPath(p, color)
    // A faint fold line down the middle, like a satin ribbon.
    drawLine(Color.White.copy(alpha = 0.18f), Offset(w / 2f, 0f), Offset(w / 2f, h - notch), strokeWidth = 1.5f)
}

/**
 * The bookmark ribbon on a page: slides down when set and up out of view when removed.
 * Top-LEFT of a left page, top-RIGHT of a right page.
 */
@Composable
fun BookmarkRibbon(visible: Boolean, side: Side) {
    val color = Color(ThorApp.instance.settings.bookmarkColor)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(320)) { -it },
            exit = slideOutVertically(tween(420)) { -it },
            modifier = Modifier
                .align(if (side == Side.LEFT) Alignment.TopStart else Alignment.TopEnd)
                .padding(start = if (side == Side.LEFT) RIBBON_INSET_DP.dp else 0.dp, end = if (side == Side.RIGHT) RIBBON_INSET_DP.dp else 0.dp),
        ) {
            Canvas(Modifier.size(RIBBON_W_DP.dp, RIBBON_H_DP.dp)) { ribbon(color) }
        }
    }
}

/** Small round swatch showing the current bookmark colour (opens the picker). */
@Composable
fun ColorSwatch(color: Color, selected: Boolean, size: Int = 44, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else HouseStyle.Hairline, CircleShape)
            .padding(if (selected) 5.dp else 3.dp)
            .clip(CircleShape)
            .background(color)
            .focusClick(CircleShape, onClick),
    )
}

/**
 * Minimal bookmark colour picker: 8 presets, a "Custom" swatch that reveals a hue slider, and
 * a live preview of the ribbon on Paper, Sepia and Night pages. One colour for all books.
 */
@Composable
fun BookmarkColorPicker() {
    val settings = ThorApp.instance.settings
    val current = settings.bookmarkColor
    val isPreset = current in Settings.BOOKMARK_PRESETS
    var custom by remember { mutableStateOf(!isPreset) }
    var hue by remember {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(current, hsv)
        mutableFloatStateOf(hsv[0])
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(HouseStyle.PanelShape)
            .background(Color(0x33000000))
            .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Settings.BOOKMARK_PRESETS.forEach { c ->
                ColorSwatch(Color(c), selected = !custom && c == current) {
                    custom = false
                    settings.updateBookmarkColor(c)
                }
            }
            // "Custom": a rainbow swatch.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(if (custom) 3.dp else 1.dp, if (custom) Color.White else HouseStyle.Hairline, CircleShape)
                    .padding(if (custom) 5.dp else 3.dp)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(hueStops()))
                    .focusClick(CircleShape) {
                        custom = true
                        settings.updateBookmarkColor(Settings.customBookmarkColor(hue))
                    },
            )
        }
        if (custom) {
            Text("Custom colour", color = HouseStyle.Subtle, fontSize = HouseStyle.TextSize)
            HueSlider(hue) {
                hue = it
                settings.updateBookmarkColor(Settings.customBookmarkColor(it))
            }
        }
        // Live preview on the three page themes.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PageTheme.entries.forEach { t ->
                Box(
                    Modifier
                        .size(64.dp, 70.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(t.paper),
                ) {
                    Canvas(Modifier.size(64.dp, 70.dp)) {
                        for (i in 0..3) {
                            val y = 30.dp.toPx() + i * 9.dp.toPx()
                            drawLine(t.ink.copy(alpha = 0.6f), Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), 2f)
                        }
                    }
                    Canvas(Modifier.offset(x = 10.dp).size(12.dp, 30.dp)) { ribbon(Color(current)) }
                    Text(t.label, color = t.ink, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
            }
        }
    }
}

private fun hueStops(): List<Color> = (0..6).map { Color(Settings.customBookmarkColor(it * 60f % 360f)) }

/** A hue strip: drag/tap to choose, d-pad left/right in steps of 10 degrees. */
@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .border(2.dp, if (focused) Accent else HouseStyle.Hairline, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                val step = when (it.key) {
                    Key.DirectionLeft -> -10f
                    Key.DirectionRight -> 10f
                    else -> return@onKeyEvent false
                }
                if (it.type == KeyEventType.KeyDown) onChange(((hue + step) % 360f + 360f) % 360f)
                true
            }
            .focusable(),
    ) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient((0..12).map { Color(Settings.customBookmarkColor(it * 30f)) }))
                .pointerInput(wPx) {
                    detectTapGestures { onChange((it.x / wPx * 360f).coerceIn(0f, 359.9f)) }
                }
                .pointerInput(wPx) {
                    detectHorizontalDragGestures { change, _ -> onChange((change.position.x / wPx * 360f).coerceIn(0f, 359.9f)) }
                },
        )
        // Thumb
        val x = with(androidx.compose.ui.platform.LocalDensity.current) { (hue / 360f * wPx).toDp() }
        Box(
            Modifier
                .offset(x = x - 3.dp)
                .width(6.dp)
                .fillMaxSize()
                .background(Color.White),
        )
    }
}
