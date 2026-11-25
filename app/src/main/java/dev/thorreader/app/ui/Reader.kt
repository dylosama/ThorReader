package dev.thorreader.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.Screen
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.BlankUnit
import dev.thorreader.app.engine.ChapterTitle
import dev.thorreader.app.engine.PageUnit
import dev.thorreader.app.engine.Pagination
import dev.thorreader.app.engine.ReaderSession
import dev.thorreader.app.engine.Rendered
import dev.thorreader.app.engine.Side
import dev.thorreader.app.engine.SlotSize
import dev.thorreader.app.engine.bookUnitIndexFor
import dev.thorreader.app.engine.unitHolds
import dev.thorreader.app.engine.screenPages
import dev.thorreader.app.engine.isSplitSpread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The page(s) a screen shows for the current mode. */
@Composable
fun ReaderScreen(session: ReaderSession, screen: Screen, mode: ReadingMode) {
    val p = session.pagination
    val error = session.error
    Box(Modifier.fillMaxSize()) {
        when {
            error != null -> ErrorMessage(error)
            p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HouseProgressBadge(session.status ?: "Opening…")
            }
            else -> {
                // The view the screens show: it changes only once all its pages are rendered, so both
                // screens switch together (see ReaderSession.shownIndex).
                val index = session.shownIndex.coerceIn(0, p.views.size - 1)
                val view = p.views[index]
                val rtl = p.config.rtl
                when {
                    mode.isBook -> {
                        val idx = bookUnitIndexFor(screen, mode, rtl)
                        val screenIsLeft = (screen == Screen.TOP) == (mode == ReadingMode.BOOK)
                        val swapped = session.isSwapped(mode)
                        // Swipe up on the small (bottom) screen swaps; on the big one while swapped swaps back.
                        val swipeUp: (() -> Unit)? =
                            if (!session.reflowable && ThorApp.instance.settings.swipeSwap && (swapped == (screen == Screen.TOP) || (swapped && isSplitSpread(view)))) {
                                { session.toggleSwap(mode, "swipe up on the ${if (screen == Screen.TOP) "big" else "small"} screen") }
                            } else {
                                null
                            }
                        // When the pages are swapped, each screen shows the OTHER page, re-fitted to
                        // this screen's own size. The two pages slide past each other at the hinge.
                        AnimatedContent(
                            targetState = index to swapped,
                            transitionSpec = {
                                if (initialState.first == targetState.first && initialState.second != targetState.second) {
                                    // The hinge is on the right of the left screen, on the left of the right one.
                                    val toHinge = if (screenIsLeft) 1 else -1
                                    val spec = tween<IntOffset>(300, easing = FastOutSlowInEasing)
                                    slideInHorizontally(spec) { it * toHinge } togetherWith slideOutHorizontally(spec) { it * toHinge }
                                } else {
                                    EnterTransition.None togetherWith ExitTransition.None
                                }
                            },
                            label = "swap",
                        ) { (vi, sw) ->
                            // Same mapping as the pre-rendering (screenPages / prefetchRequests).
                            val (unit, slot) = screenPages(p, vi, mode, screen, sw).firstOrNull() ?: (BlankUnit to p.slotFor(view, idx))
                            Box(Modifier.fillMaxSize()) {
                                PageSlot(
                                    session, p, unit, slot, screen, setOf(if (screenIsLeft) Side.LEFT else Side.RIGHT),
                                    Modifier.fillMaxSize(), onSwipeUp = swipeUp,
                                )
                                // A swapped double-page spread: the small screen shows it dimmed, with a hint.
                                if (sw && screen == Screen.BOTTOM && isSplitSpread(p.views.getOrNull(vi) ?: view)) {
                                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                                    HouseBadge("⇄ Swipe up to split", Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
                                }
                            }
                        }
                        // "Page 7 ⇄" / "Page 7 ⇄ whole spread" on the big screen, fading out.
                        if (screen == Screen.TOP) SwapPillOverlay(session)
                    }
                    view.wide || view.units.size == 1 ->
                        PageSlot(session, p, view.units[0], p.slotFor(view, 0), screen, setOf(Side.LEFT, Side.RIGHT), Modifier.fillMaxSize())
                    else -> Row(Modifier.fillMaxSize()) {
                        // Two pages side by side; in right-to-left the first page is on the right.
                        val leftIdx = if (rtl) 1 else 0
                        PageSlot(session, p, view.units[leftIdx], p.slotFor(view, leftIdx), screen, setOf(Side.LEFT), Modifier.weight(1f).fillMaxHeight())
                        PageSlot(session, p, view.units[1 - leftIdx], p.slotFor(view, 1 - leftIdx), screen, setOf(Side.RIGHT), Modifier.weight(1f).fillMaxHeight())
                    }
                }
                // Per-turn log: when this screen actually shows the new view.
                LaunchedEffect(index) {
                    val t = session.turnStartedAt
                    if (t > 0) {
                        withFrameNanos { }
                        android.util.Log.d(dev.thorreader.app.TAG, "Screen ${screen.name} shows view ${index + 1}, ${android.os.SystemClock.uptimeMillis() - t} ms after the turn")
                    }
                }
                // A turn still waiting for its pages after 300 ms: the old pages stay up, plus this.
                if (session.turnWaiting) {
                    HouseProgressBadge("Loading…", Modifier.align(Alignment.BottomCenter).padding(bottom = HouseStyle.EdgeGap))
                }
                // Small, non-blocking progress indicator (background layout etc.): tucked into the
                // top-left corner margin of each page screen, right of a bookmark ribbon there.
                session.status?.let { s ->
                    val topLeftUnit: PageUnit? = when {
                        mode.isBook -> {
                            val screenIsLeft = (screen == Screen.TOP) == (mode == ReadingMode.BOOK)
                            val i = bookUnitIndexFor(screen, mode, rtl).let { if (session.isSwapped(mode)) 1 - it else it }
                            if (screenIsLeft) view.units.getOrNull(i) else null
                        }
                        view.wide || view.units.size == 1 -> if (rtl) null else view.units.firstOrNull()
                        else -> view.units.getOrNull(if (rtl) 1 else 0)
                    }
                    val ribbonThere = session.bookmark?.let { a -> topLeftUnit?.let { unitHolds(it, a) } } == true
                    CornerBadge(s, Modifier.align(Alignment.TopStart).padding(start = if (ribbonThere) 50.dp else 3.dp, top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ErrorMessage(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = HouseStyle.Warning,
            fontSize = HouseStyle.ItemTextSize,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(HouseStyle.PanelShape)
                .background(HouseStyle.Background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Chip("Back to library") { ThorApp.instance.closeBook("error screen button") }
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** The ring only appears after this long, so ordinary taps don't flash it. */
private const val RING_DELAY_MS = 150L

private class Hold(val pos: Offset, val startedAt: Long)

/**
 * One page area: draws the rendered page and its overlays, and handles touch.
 * - Tap: left half = page to the left, right half = page to the right (so RTL flips naturally).
 * - Press and hold (Settings.menuHoldMs): a ring fills up, then the reader menu opens.
 * - Horizontal swipe: turn page. Vertical drag: scroll a page that doesn't fit (overflow).
 * - Fixed-layout pages: double-tap zoom, pinch zoom/pan.
 * [edges]: which physical sides of this page are the outside edges of the spread (where the
 * "end of book" shadow appears).
 */
@Composable
fun PageSlot(
    session: ReaderSession,
    p: Pagination,
    unit: PageUnit,
    slot: SlotSize,
    screen: Screen,
    edges: Set<Side>,
    modifier: Modifier,
    /** A swipe up on this page (comics in book modes: swap the screens), or null. */
    onSwipeUp: (() -> Unit)? = null,
) {
    val app = ThorApp.instance
    val currentSwipeUp by rememberUpdatedState(onSwipeUp)
    val reflow = session.reflowable
    val theme = app.settings.theme
    val holdMs = app.settings.menuHoldMs
    val background = if (reflow) theme.paper else Color.Black
    val rtl = p.config.rtl

    var zoom by remember(unit.key) { mutableFloatStateOf(1f) }
    var pan by remember(unit.key) { mutableStateOf(Offset.Zero) }
    var scroll by remember(unit.key) { mutableFloatStateOf(0f) }
    var hold by remember(unit.key) { mutableStateOf<Hold?>(null) }
    val zoomTier = if (zoom > 1.3f) DOUBLE_TAP_ZOOM else 1f

    var rendered by remember(unit.key, p.layoutId, slot) { mutableStateOf(session.cached(p, unit, slot, 1f)) }
    LaunchedEffect(unit.key, p.layoutId, slot, zoomTier) {
        session.render(p, unit, slot, zoomTier)?.let { rendered = it }
    }
    val currentRendered by rememberUpdatedState(rendered)
    val currentEdges by rememberUpdatedState(edges)
    val currentP by rememberUpdatedState(p) // the gesture handler outlives re-publishes
    // Bookmark ribbon: on this page? Which top corner (the page's outer side; single pages
    // follow the running header: left, or right in right-to-left books).
    val marked = session.bookmark?.let { unitHolds(unit, it) } == true
    val ribbonSide = if (edges.size == 1) edges.first() else if (rtl) Side.RIGHT else Side.LEFT
    val currentMarked by rememberUpdatedState(marked)
    val currentRibbonSide by rememberUpdatedState(ribbonSide)

    /** How far an overflowing page can scroll, for a box of [size]. */
    fun maxScroll(size: IntSize): Float {
        val r = currentRendered ?: return 0f
        if (!r.overflow) return 0f
        val shownH = r.bitmap.height * (size.width.toFloat() / r.bitmap.width)
        return max(0f, shownH - size.height)
    }

    fun clampPan(o: Offset, z: Float, size: IntSize): Offset {
        val mx = (z - 1f) * size.width / 2f
        val my = (z - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    BoxWithConstraints(
        modifier
            .background(background)
            .clipToBounds()
            .pointerInput(unit.key, reflow, holdMs) {
                val slop = viewConfiguration.touchSlop
                val swipeMin = 60.dp.toPx()
                val canZoom = !reflow && unit !is BlankUnit
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A touch on the bookmark ribbon: swipe UP removes it. Never turns the page or
                    // starts the hold-for-menu ring.
                    if (currentMarked && inRibbonZone(down.position, size.width, currentRibbonSide, this)) {
                        var dy = 0f
                        var dx = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { dy += it.positionChange().y; dx += it.positionChange().x; it.consume() }
                            if (ev.changes.none { it.pressed }) break
                            if (-dy > RIBBON_SWIPE_DP.dp.toPx() && -dy > abs(dx)) {
                                session.clearBookmark("swiped up on the ribbon")
                                ev.changes.forEach { it.consume() }
                                waitForUpOrCancellation()
                                break
                            }
                        }
                        return@awaitEachGesture
                    }
                    hold = Hold(down.position, down.uptimeMillis)
                    var now = down.uptimeMillis
                    var total = Offset.Zero
                    var multi = false
                    var dragging = false
                    var vertical = false
                    var menuOpened = false
                    var pulled = false
                    while (true) {
                        val waitingForHold = !dragging && !multi && !menuOpened
                        val remaining = holdMs - (now - down.uptimeMillis)
                        val ev = if (waitingForHold && remaining > 0) {
                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (ev == null) {
                            // Held long enough without moving: open the menu on this screen.
                            menuOpened = true
                            hold = null
                            app.menuScreen = screen
                            now = down.uptimeMillis + holdMs
                            continue
                        }
                        now = ev.changes.firstOrNull()?.uptimeMillis ?: now
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (menuOpened) {
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (pressed >= 2) {
                            hold = null
                            multi = true
                            if (canZoom) {
                                val z = (zoom * ev.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                zoom = z
                                pan = clampPan(pan + ev.calculatePan(), z, size)
                                ev.changes.forEach { it.consume() }
                            }
                            continue
                        }
                        if (multi) continue
                        val ch = ev.changes.first { it.pressed }
                        val d = ch.positionChange()
                        total += d
                        if (!dragging && total.getDistance() > slop) {
                            dragging = true
                            hold = null
                            vertical = abs(total.y) > abs(total.x)
                        }
                        if (dragging) {
                            when {
                                zoom > 1.01f -> pan = clampPan(pan + d, zoom, size)
                                vertical -> scroll = (scroll - d.y).coerceIn(0f, maxScroll(size))
                                else -> {
                                    // Swiping toward the end of the book: the edge resists.
                                    val side = if (total.x < 0) Side.RIGHT else Side.LEFT
                                    if (session.sideBlocked(side)) {
                                        if (!pulled) app.tick()
                                        pulled = true
                                        session.edgePull = side to abs(total.x)
                                    } else if (session.edgePull != null) {
                                        session.edgePull = null
                                    }
                                }
                            }
                            ch.consume()
                        }
                    }
                    hold = null
                    session.edgePull = null // eases back
                    if (menuOpened || multi) return@awaitEachGesture
                    if (dragging) {
                        // Horizontal swipe turns the page (finger moving left = page on the right).
                        if (zoom <= 1.01f && !vertical && abs(total.x) > swipeMin && abs(total.x) > abs(total.y) * 1.2f) {
                            val side = if (total.x < 0) Side.RIGHT else Side.LEFT
                            if (!session.sideBlocked(side)) {
                                if (side == Side.RIGHT) session.physicalRight() else session.physicalLeft()
                            }
                        }
                        // Swipe UP (comics, book modes): swap the pages between the screens. Not
                        // while zoomed (that drag pans) or on a page that scrolls (that drag scrolls).
                        val swipeUp = currentSwipeUp
                        if (swipeUp != null && zoom <= 1.01f && vertical && total.y < -swipeMin &&
                            abs(total.y) > abs(total.x) * 1.2f && maxScroll(size) <= 1f
                        ) {
                            swipeUp()
                        }
                        return@awaitEachGesture
                    }
                    // A tap. On zoomable pages wait briefly for a second tap (double-tap zoom).
                    if (canZoom) {
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (second != null) {
                            if (zoom > 1.01f) {
                                zoom = 1f
                                pan = Offset.Zero
                            } else {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                zoom = DOUBLE_TAP_ZOOM
                                pan = clampPan((c - second.position) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM, size)
                            }
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                    }
                    if (zoom > 1.01f) return@awaitEachGesture // zoomed in: taps don't turn pages
                    // Tapping the page number (bottom corner) opens the scrubber.
                    val numberShown = app.settings.showHeader && currentP.pageNumber(unit) != null
                    val zoneH = max(64.dp.toPx(), (currentP.config.marginPx + currentP.config.headerPx).toFloat())
                    val inCorner = if (currentP.config.rtl) down.position.x < size.width * 0.22f else down.position.x > size.width * 0.78f
                    if (numberShown && inCorner && down.position.y > size.height - zoneH) {
                        session.beginScrub(screen)
                        return@awaitEachGesture
                    }
                    if (down.position.x < size.width / 2f) session.physicalLeft() else session.physicalRight()
                }
            },
    ) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val r: Rendered? = rendered
        if (r != null) {
            val image = remember(r.bitmap) { r.bitmap.asImageBitmap() }
            val bw = r.bitmap.width.toFloat()
            val bh = r.bitmap.height.toFloat()
            // Normal pages fit the area; overflowing pages fill its width and scroll.
            val scale = if (r.overflow) boxW / bw else min(boxW / bw, boxH / bh)
            val dw = bw * scale
            val dh = bh * scale
            val maxScroll = if (r.overflow) max(0f, dh - boxH) else 0f
            val sc = scroll.coerceIn(0f, maxScroll)
            val dx = (boxW - dw) / 2f
            val dy = if (r.overflow) -sc else (boxH - dh) / 2f
            val filter = if (reflow) ColorFilter.colorMatrix(theme.colorMatrix) else null
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            ) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
                    dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                    colorFilter = filter,
                )
            }
            if (maxScroll > 1f) OverflowHints(sc, maxScroll, background)
        }

        PageOverlays(session, p, unit, rtl)
        key(unit.key) { BookmarkRibbon(marked, ribbonSide) }
        EdgeShadow(session, currentEdges)
        hold?.let { HoldRing(it, holdMs) }
    }
}

/**
 * Comics / PDF only: the page number as a small house-style pill over the art (bottom-right,
 * bottom-left in right-to-left books). Books (reflowable) get a plain running header and page
 * number drawn into the page itself in the book's own font - see ReflowEngine.
 */
@Composable
private fun PageOverlays(session: ReaderSession, p: Pagination, unit: PageUnit, rtl: Boolean) {
    if (session.reflowable || !ThorApp.instance.settings.showHeader || unit is BlankUnit) return
    val n = p.pageNumber(unit) ?: return
    Box(Modifier.fillMaxSize()) {
        HouseBadge(
            n.toString(),
            Modifier
                .align(if (rtl) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(HouseStyle.EdgeGap),
        )
    }
}

/**
 * A soft dark curve growing from the outer edge when you try to turn past the first/last page:
 * follows the finger during a swipe (session.edgePull) and eases back on release; a quick
 * in-and-out pulse on tap/button (session.edgePulse).
 */
@Composable
private fun EdgeShadow(session: ReaderSession, edges: Set<Side>) {
    val amount = remember { Animatable(0f) }
    var shownSide by remember { mutableStateOf(Side.RIGHT) }
    val maxPullPx = with(LocalDensity.current) { 160.dp.toPx() }
    val pull = session.edgePull
    LaunchedEffect(pull) {
        if (pull != null && pull.first in edges) {
            shownSide = pull.first
            amount.snapTo((pull.second / maxPullPx).coerceIn(0f, 1f))
        } else if (amount.value > 0f) {
            amount.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 250f))
        }
    }
    val pulse = session.edgePulse
    val seenAtStart = remember { session.edgePulse?.seq ?: 0 } // don't replay old pulses
    LaunchedEffect(pulse) {
        if (pulse != null && pulse.seq > seenAtStart && pulse.side in edges) {
            shownSide = pulse.side
            amount.animateTo(0.7f, tween(110))
            amount.animateTo(0f, tween(300))
        }
    }
    val a = amount.value
    if (a <= 0.001f) return
    Canvas(Modifier.fillMaxSize()) {
        val depth = 90.dp.toPx() * a
        val edgeX = if (shownSide == Side.RIGHT) size.width else 0f
        val inward = if (shownSide == Side.RIGHT) -1f else 1f
        val path = Path().apply {
            moveTo(edgeX, 0f)
            // Control point at twice the depth gives a curve that bulges `depth` at mid-height.
            quadraticTo(edgeX + inward * 2 * depth, size.height / 2f, edgeX, size.height)
            close()
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                listOf(Color.Black.copy(alpha = 0.55f * a + 0.1f), Color.Transparent),
                startX = edgeX,
                endX = edgeX + inward * depth,
            ),
        )
    }
}

/** Subtle fades at the edges of a scrollable page, plus a hint while there is more below. */
@Composable
private fun OverflowHints(scroll: Float, maxScroll: Float, background: Color) {
    val fade = 56.dp
    Box(Modifier.fillMaxSize()) {
        if (scroll > 1f) {
            Box(
                Modifier.fillMaxWidth().height(fade).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(background.copy(alpha = 0.85f), Color.Transparent)))
            )
        }
        if (scroll < maxScroll - 1f) {
            Box(
                Modifier.fillMaxWidth().height(fade).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, background.copy(alpha = 0.9f))))
            )
            HouseBadge("▾ more – drag up", Modifier.align(Alignment.BottomCenter).padding(bottom = HouseStyle.EdgeGap))
        }
    }
}

/** A ring around the finger that fills up while holding; full = menu opens. */
@Composable
private fun HoldRing(hold: Hold, holdMs: Int) {
    val progress = remember(hold) { Animatable(0f) }
    LaunchedEffect(hold) {
        kotlinx.coroutines.delay(RING_DELAY_MS)
        progress.animateTo(1f, tween((holdMs - RING_DELAY_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
    }
    if (progress.value <= 0f) return
    val radius = 34.dp
    val px = with(LocalDensity.current) { radius.toPx() }
    Canvas(
        Modifier
            .offset { IntOffset((hold.pos.x - px).roundToInt(), (hold.pos.y - px).roundToInt()) }
            .size(radius * 2),
    ) {
        val stroke = 6.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawCircle(HouseStyle.Background, radius = size.minDimension / 2)
        drawArc(Color(0x55FFFFFF), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(Accent, -90f, 360f * progress.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** "Page 7 ⇄" (or "Spread can't be swapped") on the big screen; fades out after 1.5 s. */
@Composable
private fun SwapPillOverlay(session: ReaderSession) {
    val pill = session.swapPill
    var last by remember { mutableStateOf("") }
    if (pill != null) last = pill.text
    Box(Modifier.fillMaxSize().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
        androidx.compose.animation.AnimatedVisibility(
            visible = pill != null,
            enter = androidx.compose.animation.fadeIn(tween(150)),
            exit = androidx.compose.animation.fadeOut(tween(400)),
        ) {
            HouseBadge(last)
        }
    }
}

/**
 * The background-work badge ("Laying out 42%"): the house pill, a size smaller, so it fits in
 * the page's top corner margin above the running header. Slightly see-through.
 */
@Composable
private fun CornerBadge(text: String, modifier: Modifier) {
    Text(
        text,
        color = HouseStyle.TextColor,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = modifier
            .graphicsLayer { alpha = 0.85f }
            .clip(HouseStyle.Shape)
            .background(HouseStyle.Background)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}
