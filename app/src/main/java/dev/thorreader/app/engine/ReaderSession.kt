package dev.thorreader.app.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
            snapshotFlow { LayoutConfig.build(app.settings.mode, book.rtl, app.screens, app.settings) }
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

    /** Both screens switch to view [i] (in the same frame: one state change). */
    private fun show(i: Int) {
        if (i != shownIndex) swappedView = null // a page turn ends a screen swap
        shownIndex = i
    }

    /** Show view [viewIndex] on both screens as soon as all its pages are rendered. */
    private fun commitWhenReady() {
        val p = pagination ?: return
        val target = viewIndex
        val v = p.views.getOrNull(target) ?: return
        commitJob?.cancel()
        turnWaiting = false
        val ready = v.units.withIndex().all { (i, u) -> u is BlankUnit || cached(p, u, p.slotFor(v, i), 1f) != null }
        if (ready) {
            show(target)
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

    // ---- navigation -----------------------------------------------------------------------

    fun goTo(index: Int) {
        val p = pagination ?: return
        val target = index.coerceIn(0, p.views.size - 1)
        if (target == viewIndex) return
        logTurn(viewIndex, target, p)
        viewIndex = target
        turnStartedAt = SystemClock.uptimeMillis()
        commitWhenReady()
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
    private fun step(delta: Int): Boolean {
        val p = pagination ?: return false
        val t = viewIndex + delta
        return when {
            t in p.views.indices -> {
                goTo(t)
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

    fun forward() = step(1)
    fun back() = step(-1)

    /** Turn toward the page that is physically on the right (next page, or previous in RTL). */
    fun physicalRight() = step(if (book.rtl) -1 else 1)
    fun physicalLeft() = step(if (book.rtl) 1 else -1)

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
            viewIndex = target.coerceIn(0, (pagination?.views?.size ?: 1) - 1)
            turnStartedAt = SystemClock.uptimeMillis()
            commitWhenReady()
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
    fun jumpTo(index: Int) {
        val p = pagination ?: return
        val target = index.coerceIn(0, p.views.size - 1)
        if (target == viewIndex) return
        val origin = currentAnchor()
        goTo(target)
        recordJump(origin)
    }

    /** Jump by [delta] views in reading order. */
    fun jumpBy(delta: Int) = jumpTo(viewIndex + delta)

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
        goTo(p.findView(a))
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
     * Only fixed-layout books in book modes; split double-page spreads can't be swapped.
     */
    fun toggleSwap(mode: ReadingMode, source: String) {
        if (!mode.isBook || reflowable) return
        val p = pagination ?: return
        val v = p.views.getOrNull(viewIndex) ?: return
        if (v.units.size != 2 || v.units.any { it is BlankUnit }) {
            Log.i(TAG, "Swap ($source): not possible here (single page)")
            return
        }
        if (v.units.any { it is FixedUnit && it.part != Part.WHOLE }) {
            Log.i(TAG, "Swap ($source): split double-page spread, can't swap")
            showSwapPill("Spread can't be swapped")
            return
        }
        if (swappedView != viewIndex) {
            // Render each page for the OTHER screen's size first (max 0.6 s), so the slide shows
            // real pages, not black.
            val target = viewIndex
            scope.launch {
                withTimeoutOrNull(600) {
                    v.units.forEachIndexed { i, u -> render(p, u, p.slotFor(v, 1 - i), 1f) }
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
        Log.i(TAG, "Swap ($source): pages ${if (on) "swapped" else "back to normal"} on view ${viewIndex + 1}")
        if (on) {
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
