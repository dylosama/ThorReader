package dev.thorreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.ChapterTitle
import dev.thorreader.app.engine.ReaderSession
import kotlin.math.roundToInt

/** "Page 45 of 312 · Ch. 3 · The Humming Ridge" (or a percentage while page numbers aren't known). */
fun positionLabel(session: ReaderSession, view: Int): String {
    val p = session.pagination ?: return ""
    val page = p.pageNumberOfView(view)
    val total = p.pageCount
    val where = when {
        page != null && total != null -> "Page $page of $total"
        page != null -> "Page $page"
        else -> "${(p.progressAt(view) * 100).roundToInt()}%"
    }
    val chapter = p.labelAt(view)?.let { if (session.reflowable) it else ChapterTitle.format(it) }
    return if (chapter != null) "$where · $chapter" else where
}

/**
 * A scrub track: drag (or tap) anywhere on it to move through the book; the page follows live.
 * Runs right-to-left in RTL books. [onRelease] is called when the finger lifts.
 */
@Composable
fun ScrubTrack(session: ReaderSession, modifier: Modifier = Modifier, showLabel: Boolean = true, onRelease: () -> Unit) {
    val p = session.pagination ?: return
    val count = p.views.size
    val rtl = p.config.rtl
    val pos = session.scrubPos ?: session.viewIndex
    val frac = if (count <= 1) 0f else pos.toFloat() / (count - 1)
    val visualFrac = if (rtl) 1f - frac else frac
    Column(modifier) {
        if (showLabel) {
            // Floating label that follows the thumb (kept inside the track's width).
            Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                HouseBadge(positionLabel(session, pos), Modifier.align(BiasAlignment(visualFrac * 2f - 1f, 0f)))
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(count, rtl) {
                    fun indexAt(x: Float): Int {
                        var f = (x / size.width).coerceIn(0f, 1f)
                        if (rtl) f = 1f - f
                        return (f * (count - 1)).roundToInt()
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        session.scrubTo(indexAt(down.position.x))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            session.scrubTo(indexAt(c.position.x))
                            c.consume()
                        }
                        onRelease()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbX = maxWidth * visualFrac
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(HouseStyle.Hairline))
            // Filled part runs from where the book starts (left, or right in RTL) to the thumb.
            val filled = maxWidth * frac
            Box(
                Modifier
                    .offset(x = if (rtl) maxWidth - filled else 0.dp)
                    .width(filled)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Accent),
            )
            Box(
                Modifier
                    .offset(x = thumbX - 8.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * The scrubber opened by tapping the page number: a thin house-style bar along the bottom of
 * the page. Letting go jumps there; tapping elsewhere (or B) cancels and returns.
 */
@Composable
fun ScrubberBar(session: ReaderSession) {
    Box(Modifier.fillMaxSize()) {
        // Tap outside the bar = cancel.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { session.endScrub(false) },
        )
        ScrubTrack(
            session,
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = HouseStyle.EdgeGap)
                .clip(RoundedCornerShape(18.dp))
                .background(HouseStyle.Background)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) { session.endScrub(true) }
    }
}

/** "↩ Back to page N" for a few seconds after any jump. */
@Composable
fun BackPill(session: ReaderSession, modifier: Modifier = Modifier) {
    if (!session.backPillVisible || session.scrubbing) return
    val page = session.backPage ?: return
    HouseBadge(
        "↩ Back to page $page",
        modifier
            .clip(HouseStyle.Shape)
            .clickable { session.jumpBack() },
    )
}

/** Number pad for "Go to page". */
@Composable
fun NumberPad(session: ReaderSession, onDone: () -> Unit) {
    val p = session.pagination
    val total = p?.pageCount
    var digits by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(HouseStyle.PanelShape).background(HouseStyle.Background).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (digits.isEmpty()) "Page…" else digits,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            message ?: if (total != null) "1 – $total" else "Page numbers appear when the book is laid out",
            color = if (message != null) HouseStyle.Warning else HouseStyle.Subtle,
            fontSize = HouseStyle.TextSize,
        )
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "Go"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    PadKey(key, primary = key == "Go") {
                        message = null
                        when (key) {
                            "⌫" -> digits = digits.dropLast(1)
                            "Go" -> {
                                val n = digits.toIntOrNull()
                                when {
                                    n == null -> Unit
                                    total == null -> message = "Not available yet"
                                    n !in 1..total -> message = "No page $n (1 – $total)"
                                    session.jumpToPage(n) -> onDone()
                                    else -> message = "Page $n not found"
                                }
                            }
                            else -> if (digits.length < 5) digits += key
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp, 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Accent else Color(0x33FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) OnAccent else Color.White, fontSize = 17.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Chapter list with the current chapter highlighted; tapping one jumps there. */
@Composable
fun ChapterList(session: ReaderSession, onJump: () -> Unit) {
    val p = session.pagination ?: return
    val chapters = p.chapters
    if (chapters.isEmpty()) {
        Text("No chapters in this book's table of contents.", color = HouseStyle.Subtle, fontSize = HouseStyle.TextSize)
        return
    }
    val current = p.chapterIndexAt(session.viewIndex)
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current > 2) state.scrollToItem(current - 2) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), state = state) {
        itemsIndexed(chapters) { i, (name, view) ->
            val title = if (session.reflowable) name else ChapterTitle.format(name) ?: name
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HouseStyle.ItemMinHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == current) Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable {
                        session.jumpTo(view)
                        onJump()
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    color = if (i == current) Accent else HouseStyle.TextColor,
                    fontSize = HouseStyle.ItemTextSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                p.pageNumberOfView(view)?.let { Text("$it", color = HouseStyle.Subtle, fontSize = HouseStyle.TextSize) }
            }
        }
    }
}
