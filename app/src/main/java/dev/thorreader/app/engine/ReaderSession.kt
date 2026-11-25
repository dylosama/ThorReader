package dev.thorreader.app.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import dev.thorreader.app.Screen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.TAG
import dev.thorreader.app.Background
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.BookFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/** A physical side of the reading area (after rotation in the book modes). */
enum class Side { LEFT, RIGHT }

/** A "you're at the edge of the book" pulse on [side]. [seq] makes repeats distinct. */
class EdgePulse(val side: Side, val seq: Int)

/**
 * One open book: owns the engine (on its own thread), the current pagination, the reading
 * position and a cache of rendered pages. Lives in [ThorApp], so it survives the reader moving
 * between the top-screen and bottom-screen activities (mode changes never close the book).
 */
class ReaderSession(private val app: ThorApp, initialBook: Book) {

    var book by mutableStateOf(initialBook)
        private set
    val format: BookFormat = BookFormat.valueOf(initialBook.format)
    val reflowable: Boolean = format.reflowable

    /** Small progress text; only blocks the screen before the first page exists. */
    var status by mutableStateOf<String?>("Opening…")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pagination by mutableStateOf<Pagination?>(null)
        private set
    var viewIndex by mutableIntStateOf(0)
        private set

    /** Edge feedback: a quick pulse (tap/button) ... */
    var edgePulse by mutableStateOf<EdgePulse?>(null)
        private set

    /** ... or a pull that follows the finger during a swipe (side, distance in px). */
    var edgePull by mutableStateOf<Pair<Side, Float>?>(null)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ThorReader-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var engine: BookEngine? = null
    private var generation = 0
    private var pulseSeq = 0

    // Rendered pages, LRU, bounded to CACHE_KB (current view ±2).
    private val cache = object : LruCache<String, Rendered>(CACHE_KB) {
        override fun sizeOf(key: String, value: Rendered) = value.bitmap.allocationByteCount / 1024
    }
    private val inflight = HashMap<String, Deferred<Rendered?>>()
    private var prefetchJob: Job? = null
    private var saveJob: Job? = null

    // ---- status ---------------------------------------------------------------------------
    //
    // Progress from the engine thread arrives through Handler posts, which Android may run
    // AFTER the coroutine that already cleared the status (coroutines resume via asynchronous
    // messages). Every status change therefore bumps a token, and a progress post only applies
    // while its token is still current - so a finished phase can never leave text behind.

    private var statusToken = 0

    /** Main thread only. */
    private fun showStatus(text: String?) {
        statusToken++
        if (status != text) Log.d(TAG, "Badge: ${text ?: "(gone)"}")
        status = text
    }

    /** A progress callback for the engine thread, valid until the next [showStatus]. */
    private fun progressReporter(): (String) -> Unit {
        val token = ++statusToken
        return { msg -> main.post { if (statusToken == token) status = msg } }
    }

    init {
        scope.launch {
            val t0 = SystemClock.uptimeMillis()
            try {
                val report = progressReporter()
                engine = onEngine { EngineFactory.open(app, initialBook, report) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open ${initialBook.fileName}", t)
                error = if (t is dev.thorreader.app.convert.DrmProtectedException || t is dev.thorreader.app.io.NoAccessException || t is dev.thorreader.app.io.TooLargeException) t.message
                else "Could not open this book.\n${t.message ?: t.javaClass.simpleName}"
                return@launch
            } finally {
                showStatus(null) // also clears "Unpacking comic…" if unpacking failed
            }
            Log.i(TAG, "Opened '${initialBook.title}' (${format.label}) in ${SystemClock.uptimeMillis() - t0} ms")
            snapshotFlow {
                LayoutConfig.build(app.settings.mode, book.rtl, app.screens, app.settings)
                    .copy(splitWide = if (reflowable) SplitWide.AUTO else app.settings.splitWideFor(book.id))
            }
                .distinctUntilChanged()
                .collectLatest { cfg ->
                    // Debounce: rapid font/margin taps only lay out once. A newer change cancels
                    // this one; that cancellation is normal and never shown as an error.
                    val prev = pagination?.config
                    if (prev != null && (prev.slotFontPt != cfg.slotFontPt || prev.fontPt != cfg.fontPt || prev.marginPx != cfg.marginPx || prev.headerPx != cfg.headerPx)) {
                        delay(SETTINGS_DEBOUNCE_MS)
                    }
                    try {
                        relayout(cfg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e(TAG, "Pagination failed", t)
                        if (pagination == null) error = "Could not lay out this book.\n${t.message ?: t.javaClass.simpleName}"
                    } finally {
                        showStatus(null) // finished, failed or cancelled: never leave a status behind
                    }
                }
        }
    }

    private suspend fun <T> onEngine(block: () -> T): T = withContext(engineDispatcher) {
        coroutineContext.ensureActive()
        block()
    }

    /** Engine work that isn't the page on screen: runs at Android's background priority. */
    private suspend fun <T> onEngineBackground(block: () -> T): T = onEngine {
        val tid = android.os.Process.myTid()
        val old = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            block()
        } finally {
            android.os.Process.setThreadPriority(old)
        }
    }

    private fun currentAnchor(): Anchor = pagination?.anchorOf(viewIndex) ?: Anchor(book.posChapter, book.posFraction)

    private suspend fun relayout(cfg: LayoutConfig) {
        val t0 = SystemClock.uptimeMillis()
        when (val eng = engine ?: return) {
            is ReflowEngine -> relayoutReflow(eng, cfg, t0)
            is FixedEngine -> {
                val report = progressReporter()
                if (pagination == null) status = "Preparing pages…"
                val views = onEngine { eng.paginate(cfg) { f -> report("Preparing pages… ${(f * 100).toInt()}%") } }
                publish(Pagination(cfg, views, ++generation))
                rememberPageCount()
                Log.i(TAG, "Paginated for mode ${cfg.mode.number} rtl=${cfg.rtl}: ${views.size} views in ${SystemClock.uptimeMillis() - t0} ms")
            }
        }
    }

    /**
     * Reflowable: lay out the chapter at the reading position first and show it immediately,
     * then the following / preceding chapters in the background (one engine task per chapter,
     * so page renders can run in between), re-publishing as the range grows.
     */
    private suspend fun relayoutReflow(eng: ReflowEngine, cfg: LayoutConfig, t0: Long) {
        val id = onEngine {
            eng.useLayout(cfg)
            eng.layoutIdFor(cfg)
        }
        val n = eng.chapterCount
        if (n == 0) {
            publish(Pagination(cfg, listOf(PageView(List(cfg.perView) { BlankUnit })), ++generation, id, true, 1))
            return
        }
        val start = currentAnchor().chapter.coerceIn(0, n - 1)
        if (pagination == null) showStatus("Laying out pages…")
        Log.d(TAG, "Layout ready to slice after ${SystemClock.uptimeMillis() - t0} ms")
        val cached = onEngine {
            eng.layoutChapter(start)
            eng.allReady
        }
        var lo = if (cached) 0 else start
        var hi = if (cached) n - 1 else start
        suspend fun publishRange(complete: Boolean) {
            val tb = SystemClock.uptimeMillis()
            val views = onEngine { eng.buildViews(cfg, lo, hi) }
            Log.d(TAG, "Built pages for chapters ${lo + 1}..${hi + 1} in ${SystemClock.uptimeMillis() - tb} ms")
            publish(Pagination(cfg, views, ++generation, id, complete, n))
        }
        publishRange(lo == 0 && hi == n - 1)
        Log.i(TAG, "First page ready (chapter ${start + 1}/$n) in ${SystemClock.uptimeMillis() - t0} ms")
        var lastPublish = SystemClock.uptimeMillis()
        while (lo > 0 || hi < n - 1) {
            // Background work: waits while pages are being turned, the app is in the background
            // or the screen is off, and runs at background priority (keeps the Thor cool).
            Background.awaitAllowed()
            if (hi < n - 1) onEngineBackground { eng.layoutChapter(hi + 1) }.also { hi++ }
            if (lo > 0) onEngineBackground { eng.layoutChapter(lo - 1) }.also { lo-- }
            showStatus("Laying out ${layoutPercent(hi - lo + 1, n)}%")
            if (SystemClock.uptimeMillis() - lastPublish > 600) {
                publishRange(false)
                lastPublish = SystemClock.uptimeMillis()
            }
        }
        // Final page list (cheap: each chapter's pages are cached in the engine), then the badge
        // goes away at once; saving the layout cache afterwards is silent background work.
        if (pagination?.complete != true || pagination?.layoutId != id) publishRange(true)
        showStatus(null)
        rememberPageCount()
        onEngineBackground { eng.saveCache() }
        app.trimCaches()
        Log.i(TAG, "Full layout for mode ${cfg.mode.number}: ${pagination?.views?.size} views in ${SystemClock.uptimeMillis() - t0} ms")
    }

    /** The library shows this layout's page count ("412 pages"): save it once it is known. */
    private fun rememberPageCount() {
        val n = pagination?.takeIf { it.complete }?.pageCount ?: return
        saveCurrentPage()
        if (n == book.layoutPages) return
        book = book.copy(layoutPages = n)
        val id = book.id
        app.scope.launch(Dispatchers.IO) { app.db.books().setLayoutPages(id, n) }
    }

    /**
     * Reaching the last page marks the book completed (once; it stays completed until the user
     * marks it unread). Called only for page turns / jumps, never for merely opening or saving.
     */
    private fun checkCompleted() {
        val p = pagination ?: return
        if (book.completedAt != null || !p.isLastView(viewIndex)) return
        val at = System.currentTimeMillis()
        book = book.copy(completedAt = at)
        val id = book.id
        app.scope.launch(Dispatchers.IO + NonCancellable) { app.db.books().setCompleted(id, at) }
        Log.i(TAG, "Completed '${book.title}' (last page reached)")
    }

    // ---- bookmark (one per book, stored as a book location) ---------------------------------

    /** The bookmark, or null. */
    val bookmark: Anchor?
        get() {
            val ch = book.bookmarkChapter ?: return null
            return Anchor(ch, book.bookmarkFraction ?: 0f)
        }

    /** Does the current view show the bookmarked page? */
    val bookmarkHere: Boolean
        get() {
            val a = bookmark ?: return false
            val v = pagination?.views?.getOrNull(viewIndex) ?: return false
            return v.units.any { unitHolds(it, a) }
        }

    /** View index the bookmark is on (for "Go to bookmark"), or null. */
    val bookmarkView: Int? get() = bookmark?.let { a -> pagination?.findView(a) }

    /** Page number to show for the bookmark. */
    val bookmarkPage: Int?
        get() {
            val p = pagination ?: return null
            val v = bookmarkView ?: return null
            return p.pageNumberOfView(v) ?: (v + 1)
        }

    /** Bookmark unit [unitIndex] (reading order) of the current view. Moves an existing one. */
    fun setBookmark(unitIndex: Int, reason: String): Boolean {
        val v = pagination?.views?.getOrNull(viewIndex) ?: return false
        val u = v.units.getOrNull(unitIndex) ?: return false
        val a = bookmarkAnchorOf(u) ?: return false
        storeBookmark(a, "ON at ${pagination?.pageNumber(u)?.let { "page $it" } ?: "view ${viewIndex + 1}"}", reason)
        return true
    }

    /** Bookmark the later page of the current view (the right-hand page; left-hand in RTL). */
    fun setBookmarkOnLaterPage(reason: String): Boolean {
        val v = pagination?.views?.getOrNull(viewIndex) ?: run {
            Log.i(TAG, "Bookmark not set ($reason): book not laid out yet")
            return false
        }
        val idx = v.units.indexOfLast { it !is BlankUnit }
        return idx >= 0 && setBookmark(idx, reason)
    }

    fun clearBookmark(reason: String) {
        if (bookmark == null) return
        storeBookmark(null, "OFF", reason)
    }

    /** Go to the bookmarked page. */
    fun goToBookmark() {
        bookmarkView?.let { jumpTo(it) }
    }

    private fun storeBookmark(a: Anchor?, what: String, reason: String) {
        book = book.copy(bookmarkChapter = a?.chapter, bookmarkFraction = a?.fraction)
        val id = book.id
        Log.i(TAG, "Bookmark $what for '${book.title}' (reason: $reason)")
        app.scope.launch(Dispatchers.IO + NonCancellable) { app.db.books().setBookmark(id, a?.chapter, a?.fraction) }
    }

    /** Completion changed from the library popup while this book is open. */
    fun applyCompleted(at: Long?) {
        book = book.copy(completedAt = at)
    }

    /** Swap in a new pagination, staying on the same place in the book. */
    private fun publish(p: Pagination) {
        val anchor = currentAnchor()
        swappedView = null // a new layout (e.g. another mode) shows pages in normal order
        pagination = p
        viewIndex = p.findView(anchor).coerceIn(0, p.views.size - 1)
        finishTurnNow("new layout")
        shownIndex = viewIndex // a new layout replaces the pages anyway; no waiting
        commitJob?.cancel()
        turnWaiting = false
        prefetch()
    }

    // ---- rendering ------------------------------------------------------------------------

    /** Page number printed into a reflowable page (null for fixed pages / while unknown / off). */
    private fun printedNumber(p: Pagination, unit: PageUnit): Int? =
        if (unit is SliceUnit && p.config.headerPx > 0) p.pageNumber(unit) else null

    private fun cacheKey(p: Pagination, unit: PageUnit, slot: SlotSize, zoom: Float): String =
        "${p.layoutId}:${unit.key}@${slot.w}x${slot.h}z$zoom#${printedNumber(p, unit)}:${p.config.rtl}"

    fun cached(p: Pagination, unit: PageUnit, slot: SlotSize, zoom: Float): Rendered? =
        if (unit is BlankUnit) null else cache.get(cacheKey(p, unit, slot, zoom))

    /** Must be called on the main thread. */
    suspend fun render(p: Pagination, unit: PageUnit, slot: SlotSize, zoom: Float): Rendered? {
        if (unit is BlankUnit) return null
        val eng = engine ?: return null
        val key = cacheKey(p, unit, slot, zoom)
        cache.get(key)?.let { return it }
        val job = inflight.getOrPut(key) {
            scope.async(engineDispatcher) {
                try {
                    eng.render(unit, slot, p.config, zoom, printedNumber(p, unit))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Render failed for ${unit.key}", t)
                    null
                }
            }.also { d -> d.invokeOnCompletion { main.post { if (inflight[key] === d) inflight.remove(key) } } }
        }
        val r = job.await()
        if (r != null) cache.put(key, r)
        return r
    }

    /** Render the current view first, then neighbours, so turning pages is instant. */
    private fun prefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val p = pagination ?: return@launch
            // By VIEW, every page at its own slot size (the same mapping the screens use):
            // current, NEXT, previous, then ±2. Bounded by the LRU cache (pages are screen-sized).
            for (i in prefetchOrder(viewIndex, p.views.size)) {
                for ((u, slot) in prefetchRequests(p, i)) render(p, u, slot, 1f)
            }
        }
    }

    // ---- atomic page turns ---------------------------------------------------------------
    //
    // [viewIndex] is where the reader IS (position, page numbers, menu). The screens draw
    // [shownIndex]: it only moves to a new view once every page of that view is rendered, so
    // both screens switch together and never show black while one page is still decoding.

    /** The view the screens show. Follows [viewIndex] as soon as its pages are ready. */
    var shownIndex by mutableIntStateOf(0)
        private set

    /** A turn has waited more than 300 ms for its pages: show a small "Loading…" badge. */
    var turnWaiting by mutableStateOf(false)
        private set

    /** When the last turn started (uptime ms), for the per-screen commit log. */
    var turnStartedAt = 0L
        private set
    private var commitJob: Job? = null

    /**
     * Both screens switch to view [i] (in the same frame: one state change). With a [cause]
     * the switch is animated (page-turn style); the overlay covers the pages until it ends.
     */
    private fun show(i: Int, cause: TurnCause? = null, stepsFrom: Int = shownIndex) {
        if (i == shownIndex) return
        val from = shownIndex
        val fromSwapped = isSwapped(app.settings.mode)
        finishTurnNow("new turn") // a turn mid-animation completes instantly; no queue
        swappedView = null // a page turn ends a screen swap
        shownIndex = i
        if (cause != null) startTurn(from, i, cause, i - stepsFrom, fromSwapped)
    }

    /** Are all pages of view [i] rendered (at the slot sizes the screens use)? */
    private fun viewReady(p: Pagination, i: Int): Boolean {
        val v = p.views.getOrNull(i) ?: return false
        return v.units.withIndex().all { (k, u) -> u is BlankUnit || cached(p, u, p.slotFor(v, k), 1f) != null }
    }

    /**
     * Show view [viewIndex] on both screens as soon as all its pages are rendered. Animated
     * ([cause]) only if they are ready right away: a turn that had to wait commits instantly.
     */
    private fun commitWhenReady(cause: TurnCause? = null, stepsFrom: Int = shownIndex) {
        val p = pagination ?: return
        val target = viewIndex
        val v = p.views.getOrNull(target) ?: return
        commitJob?.cancel()
        turnWaiting = false
        if (viewReady(p, target)) {
            show(target, cause, stepsFrom)
            return
        }
        commitJob = scope.launch {
            val badge = launch {
                delay(TURN_BADGE_DELAY_MS)
                turnWaiting = true
            }
            try {
                // Both pages at once (they queue on the engine thread in this order).
                v.units.withIndex().map { (i, u) -> async { render(p, u, p.slotFor(v, i), 1f) } }.forEach { it.await() }
            } finally {
                badge.cancel()
                turnWaiting = false
            }
            if (viewIndex == target && pagination === p) show(target)
        }
    }

    /** Were the new view's pages already rendered? A miss on a one-step turn is a bug. */
    private fun logTurn(from: Int, to: Int, p: Pagination) {
        val v = p.views.getOrNull(to) ?: return
        val slots = v.units.mapIndexed { i, u ->
            val hit = u is BlankUnit || cached(p, u, p.slotFor(v, i), 1f) != null
            "slot$i ${if (hit) "hit" else "MISS"}"
        }
        val dir = if (to > from) "forward" else "back"
        val line = "Turn $dir to view ${to + 1} (from ${from + 1}${if (book.rtl) ", right-to-left" else ""}): ${slots.joinToString(", ")}"
        if (kotlin.math.abs(to - from) == 1 && slots.any { it.endsWith("MISS") }) Log.w(TAG, "$line (should have been pre-rendered)")
        else Log.d(TAG, line)
    }

    // ---- page-turn animation (step 22) ---------------------------------------------------
    //
    // ONE shared clock for both screens: [turnProgress] is advanced by a single coroutine on the
    // main thread's frame clock; the overlay on each screen (MainActivity window on display 0,
    // Presentation on display 4) reads it in its draw phase, so both move in the same frame.
    // Nothing runs while no turn is active.

    /** One animated turn from view [from] to view [to]. */
    class Turn(
        val id: Int,
        val from: Int,
        val to: Int,
        val style: TurnStyle,
        val durationMs: Int,
        /** Was the old view shown swapped between the screens (comics)? Its pictures follow that. */
        val fromSwapped: Boolean,
        /** Started by a finger drag (follows the finger until released). */
        val interactive: Boolean,
        /** Button / tap curl: the starting tilt (+ bottom corner, − top corner, 0 = straight). */
        val cornerTheta0: Float = 0f,
    ) {
        val forward: Boolean get() = to > from

        /** Released: where t was, its tilt then, and where it is heading (1 = done, 0 = back). */
        var released = !interactive
        var releaseT = 0f
        var releaseTheta = 0f
        var target = 1f
    }

    /** The turn being animated, or null (then nothing is drawn or computed for animations). */
    var turn by mutableStateOf<Turn?>(null)
        private set

    private val turnT = mutableFloatStateOf(0f)

    /** Progress of [turn], 0..1 (eased). Read in the draw phase only. */
    val turnProgress: Float get() = turnT.floatValue

    /** The curl the finger is holding: on which screen / page area, in that area's frame. */
    class DragCurl(val screen: Screen, val regionSide: Side?, val curl: Curl)

    var dragCurl by mutableStateOf<DragCurl?>(null)
        private set

    /** Set by ThorApp while a held button flips continuously (fast slide instead of a curl). */
    var repeatingTurns = false

    private var turnSeq = 0
    private var turnJob: Job? = null
    private var dragFraction = 0f
    private var slowCurlTurns = 0

    /** Debug builds only: `adb shell setprop debug.thorreader.turnms 4000` slows turns down for screen recordings. */
    private val debugTurnMs: Int? by lazy {
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return@lazy null
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "debug.thorreader.turnms"))
            p.inputStream.bufferedReader().readText().trim().toIntOrNull()?.takeIf { it in 50..20_000 }
        }.getOrNull()
    }

    private var batteryCheckedAt = 0L
    private var batteryLow = false

    /** Below 15 % and not charging (checked at most once a minute; the sticky intent, no receiver). */
    private fun lowBattery(): Boolean {
        if (!app.settings.simplerTurnOnLowBattery) return false
        val now = SystemClock.uptimeMillis()
        if (batteryCheckedAt == 0L || now - batteryCheckedAt > 60_000) {
            batteryCheckedAt = now
            val i = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val b = i?.let { dev.thorreader.app.ui.BatteryState.from(it) }
            batteryLow = b != null && b.percent < PageTurnMath.LOW_BATTERY && !b.charging
        }
        return batteryLow
    }

    private fun chooseStyle(cause: TurnCause, steps: Int): Pair<TurnStyle, Int> {
        val (style, ms) = PageTurnMath.chooseStyle(
            cause, steps, app.settings.turnStyleFor(book.id), app.settings.turnSpeed, lowBattery(), TurnSupport.curlDisabledReason == null,
        )
        return style to (if (cause == TurnCause.NORMAL || cause == TurnCause.BUTTON) debugTurnMs ?: ms else ms)
    }

    /** Animate the switch that [show] just made (the old view's pages must still be rendered). */
    private fun startTurn(from: Int, to: Int, cause: TurnCause, steps: Int, fromSwapped: Boolean) {
        val p = pagination ?: return
        val (style, ms) = chooseStyle(cause, steps)
        if (style == TurnStyle.NONE || ms <= 0) return
        if (!viewReady(p, from)) {
            Log.d(TAG, "Turn ${from + 1} -> ${to + 1}: old pages no longer rendered, no animation")
            return
        }
        val corner = if (cause == TurnCause.BUTTON && style == TurnStyle.CURL) PageTurnMath.cornerTheta0(app.settings.buttonTurnCornerBottom) else 0f
        val t = Turn(++turnSeq, from, to, style, ms, fromSwapped, interactive = false, cornerTheta0 = corner)
        turn = t
        turnT.floatValue = 0f
        runTurn(t, 1f, PageTurnMath::easeInOut)
    }

    /** Advance [t] to [toT] on the frame clock, then end it. */
    private fun runTurn(t: Turn, toT: Float, easing: (Float) -> Float) {
        turnJob?.cancel()
        turnJob = scope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            val fromT = turnT.floatValue
            val dur = (t.durationMs * kotlin.math.abs(toT - fromT)).coerceAtLeast(60f)
            val intervals = ArrayList<Float>(64)
            var start = -1L
            var last = -1L
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                if (start < 0) start = now
                if (last >= 0) intervals += (now - last) / 1_000_000f
                last = now
                val x = ((now - start) / 1_000_000f / dur).coerceIn(0f, 1f)
                turnT.floatValue = fromT + (toT - fromT) * easing(x)
                if (x >= 1f) break
            }
            endTurn(t, intervals)
        }
    }

    private fun endTurn(t: Turn, intervals: List<Float>) {
        if (turn !== t) return
        val p50 = PageTurnMath.percentile(intervals, 50f)
        val p95 = PageTurnMath.percentile(intervals, 95f)
        Log.d(
            TAG,
            "Turn anim ${t.style.name.lowercase()}${if (t.interactive) " (drag)" else ""} view ${t.from + 1}->${t.to + 1}" +
                "${if (t.target == 0f) " cancelled" else ""}: ${intervals.size + 1} frames, frame interval p50 ${"%.1f".format(p50)} ms, p95 ${"%.1f".format(p95)} ms",
        )
        // Too slow for the curl on this device: after a few slow curls in a row, use Slide.
        if (t.style == TurnStyle.CURL && intervals.size >= 5) {
            slowCurlTurns = if (p95 > PageTurnMath.SLOW_P95_MS) slowCurlTurns + 1 else 0
            if (slowCurlTurns >= PageTurnMath.SLOW_TURNS_TO_FALL_BACK) {
                TurnSupport.disableCurl("too slow: frame interval p95 ${"%.1f".format(p95)} ms in $slowCurlTurns curl turns in a row")
            }
        }
        turn = null
        dragCurl = null
        turnT.floatValue = 0f
        turnJob = null
    }

    /**
     * Finish the running turn instantly (a new turn, the menu, a mode change, the lid closing).
     * A drag still held is completed if it was past the threshold, else dropped.
     */
    fun finishTurnNow(reason: String) {
        val t = turn ?: return
        turnJob?.cancel()
        turnJob = null
        if (t.interactive && !t.released && shownIndex == t.from && PageTurnMath.shouldComplete(dragFraction, 0f)) {
            commitDrag(t)
        }
        turn = null
        dragCurl = null
        turnT.floatValue = 0f
        Log.d(TAG, "Turn animation finished early ($reason)")
    }

    // ---- drags: the page follows the finger ----

    /**
     * A horizontal drag toward [side] began. Starts an interactive turn if the page there is
     * already rendered and animations are on; returns its id, or null (then the old swipe-on-
     * release behaviour applies).
     */
    fun beginDrag(side: Side): Int? {
        val p = pagination ?: return null
        if (scrubPos != null || viewIndex != shownIndex) return null
        val delta = if ((side == Side.RIGHT) != book.rtl) 1 else -1
        val target = shownIndex + delta
        if (target !in p.views.indices || !viewReady(p, target) || !viewReady(p, shownIndex)) return null
        val (style, ms) = chooseStyle(TurnCause.NORMAL, delta)
        if (style == TurnStyle.NONE) return null
        finishTurnNow("drag started")
        val t = Turn(++turnSeq, shownIndex, target, style, ms, isSwapped(app.settings.mode), interactive = true)
        dragFraction = 0f
        turnT.floatValue = 0f
        turn = t
        return t.id
    }

    /**
     * The finger moved. [w] x [h] = the touched page area; [start] / [finger] in its pixels;
     * [radius] = the curl radius there. The grabbed corner follows the finger on the page that
     * turns (curl); otherwise the progress follows the distance moved.
     */
    fun dragTo(id: Int, screen: Screen, regionSide: Side?, w: Int, h: Int, start: androidx.compose.ui.geometry.Offset, finger: androidx.compose.ui.geometry.Offset, radius: Float) {
        val t = turn ?: return
        if (t.id != id || t.released) return
        val p = pagination ?: return
        val mode = app.settings.mode
        val rtl = book.rtl
        val hinge = turnHasHinge(p, t, mode)
        val startSide = PageTurnMath.startSide(t.forward, rtl)
        val role = when {
            hinge && regionSide != null -> PageTurnMath.roleOf(regionSide, startSide)
            !hinge && regionSide == null -> RegionRole.SINGLE
            else -> null // page areas don't match this turn's layout: follow the distance only
        }
        val wf = w.toFloat()
        // Travel toward the turn: the finger moves away from the side the sheet starts on.
        val moved = (if (startSide == Side.RIGHT) start.x - finger.x else finger.x - start.x).coerceAtLeast(0f)
        dragFraction = (moved / wf).coerceIn(0f, 1f)
        val tracksCorner = role == RegionRole.SOURCE || (role == RegionRole.SINGLE && t.forward)
        if (tracksCorner && role != null) {
            val mirror = PageTurnMath.freeEdgeLeft(role, regionSide, rtl)
            val fx = if (mirror) wf - finger.x else finger.x
            val cy = PageTurnMath.grabbedCornerY(start.y, h.toFloat())
            val curl = PageTurnMath.curlFromDrag(wf, cy, fx, finger.y, radius, h.toFloat())
            if (curl != null) {
                dragCurl = DragCurl(screen, regionSide, curl)
                turnT.floatValue = PageTurnMath.progressForAxis(role, curl.a, wf, radius)
            } else {
                dragCurl = null
                turnT.floatValue = 0f
            }
        } else {
            dragCurl = null
            turnT.floatValue = if (hinge) dragFraction * PageTurnMath.HANDOFF else dragFraction
        }
    }

    /** The finger let go; [velocity] = px/s toward the turn. Completes or springs back. */
    fun endDrag(id: Int, velocity: Float) {
        val t = turn ?: return
        if (t.id != id || t.released) return
        val complete = PageTurnMath.shouldComplete(dragFraction, velocity)
        t.released = true
        t.releaseT = turnT.floatValue
        t.releaseTheta = dragCurl?.curl?.theta ?: 0f
        t.target = if (complete) 1f else 0f
        dragCurl = null
        if (complete) commitDrag(t)
        Log.d(TAG, "Drag released at ${"%.0f".format(dragFraction * 100)} % of the page, ${"%.0f".format(velocity)} px/s -> ${if (complete) "turn" else "spring back"}")
        runTurn(t, t.target, PageTurnMath::easeOut)
    }

    /** A drag turn completes: the reader moves to the new view (position, prefetch, save). */
    private fun commitDrag(t: Turn) {
        val p = pagination ?: return
        if (viewIndex == t.to) return
        logTurn(viewIndex, t.to, p)
        viewIndex = t.to
        turnStartedAt = SystemClock.uptimeMillis()
        swappedView = null
        shownIndex = t.to // the overlay covers the pages until the animation ends
        Background.markBusy()
        prefetch()
        scheduleSave()
        checkCompleted()
    }

    /** Does [t] fold at a hinge (two page areas) or turn as one sheet? */
    fun turnHasHinge(p: Pagination, t: Turn, mode: ReadingMode): Boolean {
        fun twoPages(i: Int) = p.views.getOrNull(i)?.let { !it.wide && it.units.size == 2 } == true
        return PageTurnMath.hasHinge(mode.isBook, twoPages(t.from), twoPages(t.to))
    }

    // ---- navigation -----------------------------------------------------------------------

    fun goTo(index: Int, cause: TurnCause = TurnCause.NORMAL) {
        val p = pagination ?: return
        val target = index.coerceIn(0, p.views.size - 1)
        if (target == viewIndex) return
        logTurn(viewIndex, target, p)
        viewIndex = target
        turnStartedAt = SystemClock.uptimeMillis()
        commitWhenReady(if (repeatingTurns && (cause == TurnCause.NORMAL || cause == TurnCause.BUTTON)) TurnCause.REPEAT else cause)
        dev.thorreader.app.Background.markBusy() // background work waits while pages are being turned
        prefetch()
        scheduleSave()
        checkCompleted()
    }

    /** True if moving [delta] views would go past the real first/last page of the book. */
    private fun atEdge(delta: Int): Boolean {
        val p = pagination ?: return false
        val t = viewIndex + delta
        return t < 0 || (t >= p.views.size && p.complete) // end of a partial layout is not the end
    }

    /** The physical side the next page lies on. */
    private val forwardSide: Side get() = if (book.rtl) Side.LEFT else Side.RIGHT

    /** Turn [delta] views. Returns false if it couldn't move (edge of the book / not laid out). */
    private fun step(delta: Int, cause: TurnCause = TurnCause.NORMAL): Boolean {
        val p = pagination ?: return false
        val t = viewIndex + delta
        return when {
            t in p.views.indices -> {
                goTo(t, cause)
                true
            }
            atEdge(delta) -> {
                if (delta > 0) checkCompleted() // e.g. a one-page book: trying to go on = finished
                pulse(if (delta > 0) forwardSide else if (forwardSide == Side.RIGHT) Side.LEFT else Side.RIGHT)
                false
            }
            else -> false
        }
    }

    fun forward(cause: TurnCause = TurnCause.NORMAL) = step(1, cause)
    fun back(cause: TurnCause = TurnCause.NORMAL) = step(-1, cause)

    /** Turn toward the page that is physically on the right (next page, or previous in RTL). */
    fun physicalRight(cause: TurnCause = TurnCause.NORMAL) = step(if (book.rtl) -1 else 1, cause)
    fun physicalLeft(cause: TurnCause = TurnCause.NORMAL) = step(if (book.rtl) 1 else -1, cause)

    // ---- scrubber & jumps ------------------------------------------------------------------
    //
    // Positions are remembered as Anchors (place in the book), never as page numbers, because a
    // book's page numbers change with font, margins and mode.

    /** The screen showing the scrubber bar (opened by tapping the page number), or null. */
    var scrubScreen by mutableStateOf<Screen?>(null)
        private set

    /** Scrub target view while dragging (follows the finger at once; pages follow ~10x/s). */
    var scrubPos by mutableStateOf<Int?>(null)
        private set
    private var scrubOrigin: Anchor? = null
    private var scrubApplyJob: Job? = null

    /** Where "↩ Back to page N" returns to, or null. */
    var backTarget by mutableStateOf<Anchor?>(null)
        private set
    var lastJumpAt by mutableLongStateOf(0L)
        private set
    var backPillVisible by mutableStateOf(false)
        private set
    private var pillJob: Job? = null

    val scrubbing: Boolean get() = scrubPos != null

    /** Start scrubbing (from the page-number bar when [screen] is set, else the menu slider). */
    fun beginScrub(screen: Screen?) {
        if (pagination == null) return
        if (scrubPos == null) {
            scrubOrigin = currentAnchor()
            scrubPos = viewIndex
        }
        if (screen != null) {
            scrubScreen = screen
            app.menuScreen = null
        }
    }

    /** Move the scrub target; the visible page follows at most ~10 times a second. */
    fun scrubTo(index: Int) {
        val p = pagination ?: return
        if (scrubPos == null) beginScrub(null)
        scrubPos = index.coerceIn(0, p.views.size - 1)
        if (scrubApplyJob?.isActive == true) return
        scrubApplyJob = scope.launch {
            while (true) {
                val t = scrubPos ?: break
                if (t != viewIndex) {
                    viewIndex = t
                    turnStartedAt = SystemClock.uptimeMillis()
                    commitWhenReady()
                }
                delay(100)
                if (scrubPos == null || scrubPos == viewIndex) break
            }
        }
    }

    /** Scrub by [delta] views in the PHYSICAL direction (+ = toward the right side). */
    fun scrubPhysical(delta: Int) = scrubTo((scrubPos ?: viewIndex) + if (book.rtl) -delta else delta)

    /** Finish scrubbing: jump to the target ([confirm]) or return to where scrubbing began. */
    fun endScrub(confirm: Boolean) {
        val target = scrubPos ?: return
        val origin = scrubOrigin
        scrubApplyJob?.cancel()
        scrubPos = null
        scrubScreen = null
        if (confirm) {
            val before = shownIndex
            viewIndex = target.coerceIn(0, (pagination?.views?.size ?: 1) - 1)
            turnStartedAt = SystemClock.uptimeMillis()
            commitWhenReady(TurnCause.JUMP, stepsFrom = before)
            if (origin != null && pagination?.findView(origin) != viewIndex) recordJump(origin)
            prefetch()
            scheduleSave()
            checkCompleted()
        } else if (origin != null) {
            pagination?.let { viewIndex = it.findView(origin).coerceIn(0, it.views.size - 1) }
            turnStartedAt = SystemClock.uptimeMillis()
            commitWhenReady()
            prefetch()
        }
    }

    /** Jump to view [index] (chapter list, -10/+10, go-to-page), remembering where we were. */
    fun jumpTo(index: Int, cause: TurnCause = TurnCause.JUMP) {
        val p = pagination ?: return
        val target = index.coerceIn(0, p.views.size - 1)
        if (target == viewIndex) return
        val origin = currentAnchor()
        goTo(target, cause)
        recordJump(origin)
    }

    /** Jump by [delta] views in reading order (reader menu ±1 / ±10: the full animation). */
    fun jumpBy(delta: Int) = jumpTo(viewIndex + delta, TurnCause.NORMAL)

    /** Go to printed page [n]. Returns false if that page doesn't exist (yet). */
    fun jumpToPage(n: Int): Boolean {
        val v = pagination?.viewOfPage(n) ?: return false
        jumpTo(v)
        return true
    }

    /** "↩ Back to page N": return to where we were before the last jump. */
    fun jumpBack() {
        val a = backTarget ?: return
        val p = pagination ?: return
        backTarget = null
        backPillVisible = false
        goTo(p.findView(a), TurnCause.JUMP)
        Log.i(TAG, "Back to position before the jump")
    }

    /** Page number to show on the back pill. */
    val backPage: Int?
        get() {
            val p = pagination ?: return null
            val a = backTarget ?: return null
            val v = p.findView(a)
            return p.pageNumberOfView(v) ?: (v + 1)
        }

    /** A jump within the last 2 minutes: the menu still offers "Back to page N". */
    val recentJump: Boolean get() = backTarget != null && SystemClock.uptimeMillis() - lastJumpAt < 120_000

    private fun recordJump(origin: Anchor) {
        val now = SystemClock.uptimeMillis()
        // A burst of jumps (e.g. several +10s) keeps the ORIGINAL place to go back to.
        if (backTarget == null || now - lastJumpAt > 10_000) backTarget = origin
        lastJumpAt = now
        backPillVisible = true
        pillJob?.cancel()
        pillJob = scope.launch {
            delay(BACK_PILL_MS)
            backPillVisible = false
        }
    }

    /** True if turning toward [side] is impossible because the book ends there. */
    fun sideBlocked(side: Side): Boolean = atEdge(if ((side == Side.RIGHT) != book.rtl) 1 else -1)

    private fun pulse(side: Side) {
        edgePulse = EdgePulse(side, ++pulseSeq)
        app.tick()
        Log.d(TAG, "Edge of book: ${if (viewIndex == 0) "first" else "last"} page, side $side")
    }

    val progress: Float get() = pagination?.progressAt(viewIndex) ?: book.progress

    /** Reading direction changed (this book or its series): re-lays out via the config flow. */
    fun applyRtl(rtl: Boolean) {
        if (book.rtl != rtl) book = book.copy(rtlOverride = rtl)
    }

    /** The book's own saved mode changed (the screens already switched). */
    fun applyMode(mode: ReadingMode) {
        finishTurnNow("mode change")
        book = book.copy(mode = mode.name)
        swappedView = null
    }

    // ---- swap pages between the screens (comics, book modes) ----------------------------
    //
    // View-only: which screen shows which page of the current view. Never changes the position,
    // page numbers or bookmarks. Stored as the view index that is swapped, so turning the page
    // ends it by itself.

    private var swappedView by mutableStateOf<Int?>(null)

    /** Are the two pages of the current view shown on each other's screens? */
    fun isSwapped(mode: ReadingMode): Boolean = mode.isBook && !reflowable && swappedView == shownIndex // the view on screen

    /** Short house-style message on the big screen ("Page 7 ⇄"); [seq] restarts its fade. */
    class SwapPill(val text: String, val seq: Int)

    var swapPill by mutableStateOf<SwapPill?>(null)
        private set
    private var swapPillSeq = 0
    private var swapPillJob: Job? = null

    private fun showSwapPill(text: String) {
        swapPill = SwapPill(text, ++swapPillSeq)
        swapPillJob?.cancel()
        swapPillJob = scope.launch {
            delay(1500)
            swapPill = null
        }
    }

    /**
     * Swap / unswap the pages of the current view between the screens ([source] is logged).
     * Only fixed-layout books in book modes. A split double-page spread becomes ONE whole
     * spread on the big screen (the small screen shows it dimmed); swapping again splits it.
     */
    fun toggleSwap(mode: ReadingMode, source: String) {
        if (!mode.isBook || reflowable) return
        val p = pagination ?: return
        val v = p.views.getOrNull(viewIndex) ?: return
        if (v.units.size != 2 || v.units.any { it is BlankUnit }) {
            Log.i(TAG, "Swap ($source): not possible here (single page)")
            return
        }
        if (swappedView != viewIndex) {
            // Render what each screen will show first (max 0.6 s; the same mapping the screens
            // use), so the slide shows real pages, not black.
            val target = viewIndex
            scope.launch {
                withTimeoutOrNull(600) {
                    for (screen in dev.thorreader.app.Screen.entries) {
                        for ((u, slot) in screenPages(p, target, mode, screen, swapped = true)) render(p, u, slot, 1f)
                    }
                }
                if (viewIndex == target && pagination === p) {
                    dev.thorreader.app.Background.markBusy() // the swap animation runs now
                    swappedView = target
                    announceSwap(mode, p, v, true, source)
                }
            }
            return
        }
        swappedView = null
        announceSwap(mode, p, v, false, source)
    }

    private fun announceSwap(mode: ReadingMode, p: Pagination, v: PageView, on: Boolean, source: String) {
        val spread = isSplitSpread(v)
        Log.i(TAG, "Swap ($source): ${if (spread) "spread ${if (on) "shown whole on the big screen" else "split again"}" else "pages ${if (on) "swapped" else "back to normal"}"} on view ${viewIndex + 1}")
        if (on && spread) {
            p.pageNumber(v.units[0])?.let { showSwapPill("Page $it ⇄ whole spread") }
        } else if (on) {
            // The page now on the big (top) screen.
            val bigIdx = 1 - bookUnitIndexFor(dev.thorreader.app.Screen.TOP, mode, book.rtl)
            p.pageNumber(v.units[bigIdx])?.let { showSwapPill("Page $it ⇄") }
        } else {
            swapPill = null
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            savePosition()
        }
    }

    private fun savePosition() {
        val p = pagination ?: return
        val a = p.anchorOf(viewIndex) ?: return
        val prog = progress
        book = book.copy(posChapter = a.chapter, posFraction = a.fraction, progress = prog)
        saveCurrentPage()
        val id = book.id
        app.scope.launch(Dispatchers.IO + NonCancellable) {
            app.db.books().savePosition(id, a.chapter, a.fraction, prog, System.currentTimeMillis())
        }
    }

    /** The library shows "277 / 1794": remember the page number at the reading position. */
    private fun saveCurrentPage() {
        val n = pagination?.pageNumberOfView(viewIndex) ?: return
        if (n == book.lastPage) return
        book = book.copy(lastPage = n)
        val id = book.id
        app.scope.launch(Dispatchers.IO + NonCancellable) { app.db.books().setLastPage(id, n) }
    }

    fun close() {
        finishTurnNow("book closed")
        savePosition()
        showStatus(null)
        scope.cancel()
        val eng = engine
        executor.execute { runCatching { eng?.close() } }
        executor.shutdown()
        cache.evictAll()
    }

    private companion object {
        const val SETTINGS_DEBOUNCE_MS = 400L
        const val BACK_PILL_MS = 6000L

        /** Rendered pages kept in memory: enough for the current view ±2 plus a zoom (~120 MB). */
        const val CACHE_KB = 120 * 1024

        /** A turn whose pages aren't ready yet shows "Loading…" only after this long. */
        const val TURN_BADGE_DELAY_MS = 300L
    }
}

/** Whether the GPU curl can be used (it falls back to Slide for the rest of the session, logged once). */
object TurnSupport {
    @Volatile
    var curlDisabledReason: String? = null
        private set

    fun disableCurl(reason: String) {
        if (curlDisabledReason != null) return
        curlDisabledReason = reason
        Log.w(TAG, "Page curl off for this session, using Slide instead: $reason")
    }
}
