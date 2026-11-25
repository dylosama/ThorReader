package dev.thorreader.app

import android.app.Application
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import dev.thorreader.app.data.AppDatabase
import dev.thorreader.app.data.Book
import dev.thorreader.app.data.SeriesDirection
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.engine.MuPdf
import dev.thorreader.app.engine.ReaderSession
import dev.thorreader.app.engine.TurnCause
import dev.thorreader.app.engine.ScreenMetrics
import dev.thorreader.app.importer.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import dev.thorreader.app.engine.BookAnimMath
import dev.thorreader.app.ui.frontRect
import java.io.File

const val TAG = "ThorReader"

/** The Thor's bottom screen is display 4. */
const val BOTTOM_DISPLAY_ID = 4

data class ModeToast(val mode: ReadingMode, val seq: Int)

/** App-wide state shared by both activities and the bottom-screen Presentation. */
class ThorApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var importer: Importer
        private set

    /** Files from other apps (sharing, Thor Bookloader). */
    val incoming by lazy { dev.thorreader.app.importer.IncomingImporter(this, db.books()) }
    lateinit var lookup: dev.thorreader.app.metadata.MetadataLookup
        private set
    val scope = MainScope()

    var screens by mutableStateOf(ScreenMetrics.DEFAULT)
        private set

    /** The open book, or null when the library is showing. */
    var session by mutableStateOf<ReaderSession?>(null)
        private set

    private var menuScreenState = mutableStateOf<Screen?>(null)

    /** The reader menu's folded "More" section is open (kept while the app runs). */
    var menuMoreOpen by mutableStateOf(false)

    /** Which screen the reader menu is open on (null = closed). */
    var menuScreen: Screen?
        get() = menuScreenState.value
        set(value) {
            if (value != null && menuScreenState.value == null) session?.finishTurnNow("menu opened")
            menuScreenState.value = value
            if (value != null) tutorialDone(TutorialStep.OPEN_MENU)
        }

    /** Set by the library while it shows something Back should close (search, a category). */
    var libraryBack: (() -> Boolean)? = null

    /** Library overlays: the Settings screen and the "Edit info" panel for one book. */
    var settingsOpen by mutableStateOf(false)
    var editInfoBook by mutableStateOf<Book?>(null)

    /** Open a web page (e.g. where to get a ComicVine key) in the browser. */
    fun openWebPage(url: String) {
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "No browser to open $url") }
    }

    /** Set by the reader menu while its colour picker is open (Back/B closes it first). */
    var menuBack: (() -> Boolean)? = null

    /** The last B press was used by the library (so its key-up is swallowed too). */
    private var libraryBackPending = false

    // ---- first-run tutorial -------------------------------------------------------------

    private fun isDone(step: TutorialStep) = settings.tutorialDone and (1 shl step.ordinal) != 0

    /**
     * Should [step]'s badge show now (its screen must also be showing)? Each step shows once,
     * in order: "Tap a book" waits for "Add your books", "Choose how you hold" for "open the menu".
     * (The library steps and the reader steps happen on different screens, so after "Show
     * tutorial again" while reading, the reader steps come first.)
     */
    fun tutorialShows(step: TutorialStep): Boolean = !isDone(step) && when (step) {
        TutorialStep.ADD_BOOKS, TutorialStep.OPEN_MENU -> true
        TutorialStep.OPEN_BOOK -> isDone(TutorialStep.ADD_BOOKS)
        TutorialStep.CHOOSE_MODE -> isDone(TutorialStep.OPEN_MENU)
    }

    /** [step] was done or dismissed: never show it again (until "Show tutorial again"). */
    fun tutorialDone(step: TutorialStep) {
        if (isDone(step)) return
        settings.updateTutorialDone(settings.tutorialDone or (1 shl step.ordinal))
        Log.i(TAG, "Tutorial step ${step.name} done")
    }

    /** Badges currently on screen (a tap anywhere dismisses them). */
    val tutorialVisible = androidx.compose.runtime.mutableStateListOf<TutorialStep>()

    /** A tap anywhere: dismiss the badges that are showing (the tap itself still goes through). */
    fun tutorialTapped() {
        tutorialVisible.toList().forEach { tutorialDone(it) }
    }

    /** "Show tutorial again" (reader menu). */
    fun restartTutorial() {
        settings.updateTutorialDone(0)
        menuScreen = null // close the menu so "Hold anywhere ... to open the menu" shows right away
        Log.i(TAG, "Tutorial restarted")
    }

    /** Brief "Mode 2: Book reversed" label after a mode change. */
    var modeToast by mutableStateOf<ModeToast?>(null)
        private set

    /** True if Android refused to move the reader onto the bottom display (mode 4 fallback). */
    var bottomFallback by mutableStateOf(false)

    private var toastSeq = 0
    private var toastJob: Job? = null
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MuPdf.init()
        settings = Settings(getSharedPreferences("thor_reader", MODE_PRIVATE))
        db = Room.databaseBuilder(this, AppDatabase::class.java, "thor_reader.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()
        importer = Importer(this, db.books(), scope)
        lookup = dev.thorreader.app.metadata.MetadataLookup(this).also { it.start() }
        settings.onTextChanged = ::saveBookText
        registerScreenReceiver()
        trimCaches()
        refreshScreens()
        MuPdf.logNativeSupport()
        // Settings.mode always starts as Mode 3: the library on the top screen.
        Log.i(TAG, "App started in mode ${settings.mode.number} (${settings.mode.title}), screens=$screens")
    }

    // ---- displays -------------------------------------------------------------------------

    fun bottomDisplay(): Display? =
        displayManager.getDisplay(BOTTOM_DISPLAY_ID)
            ?: displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    /**
     * Real size of each screen, looked up by display id. Uses the display's hardware mode
     * (physical pixels), which - unlike getRealMetrics() - does not depend on which display the
     * calling activity happens to be on.
     */
    fun refreshScreens() {
        fun size(d: Display?): Pair<Int, Int>? = d?.mode?.let { mode ->
            val w = mode.physicalWidth
            val h = mode.physicalHeight
            if (w <= 0 || h <= 0) null else maxOf(w, h) to minOf(w, h) // landscape: width = longer side
        }
        val top = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val bottom = bottomDisplay()
        val (tw, th) = size(top) ?: (ScreenMetrics.DEFAULT.topW to ScreenMetrics.DEFAULT.topH)
        val (bw, bh) = size(bottom) ?: (ScreenMetrics.DEFAULT.bottomW to ScreenMetrics.DEFAULT.bottomH)
        val density = top?.let { runCatching { createDisplayContext(it).resources.displayMetrics.density }.getOrNull() }
            ?: ScreenMetrics.DEFAULT.density
        val m = ScreenMetrics(tw, th, bw, bh, density)
        if (m != screens) {
            screens = m
            Log.i(TAG, "Screens: top (display 0) ${tw}x$th, bottom (display ${bottom?.displayId}) ${bw}x$bh, density $density")
        }
    }

    // ---- modes ----------------------------------------------------------------------------

    /** Time of the last mode change: the screen handover that follows must not close the book. */
    private var lastModeChangeAt = 0L

    /**
     * Switch the screens to [mode]. While a book is open the mode is saved to that book (and
     * remembered as the mode for newly imported books).
     */
    fun setMode(mode: ReadingMode, showLabel: Boolean = true) {
        if (mode != settings.mode) {
            Log.i(TAG, "Mode ${settings.mode.number} -> ${mode.number} (${mode.title}); open book: ${session?.book?.title}")
            lastModeChangeAt = SystemClock.uptimeMillis()
            stopRepeating() // the key-up may arrive at another window during the screen handover
            session?.finishTurnNow("mode change")
            settings.updateMode(mode)
        }
        session?.let { s ->
            if (s.book.readingMode != mode) {
                s.applyMode(mode)
                val id = s.book.id
                scope.launch(Dispatchers.IO) { db.books().setMode(id, mode.name) }
            }
            settings.updateLastBookMode(mode)
        }
        menuScreen = null
        if (!showLabel) return
        toastSeq++
        modeToast = ModeToast(mode, toastSeq)
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(1600)
            modeToast = null
        }
    }

    fun cycleMode() = setMode(settings.mode.next())

    /** Change a book's saved mode from the library (doesn't change the screens). */
    fun setBookMode(book: Book, mode: ReadingMode) {
        scope.launch(Dispatchers.IO) { db.books().setMode(book.id, mode.name) }
        Log.i(TAG, "'${book.title}' will open in mode ${mode.number} (${mode.title})")
    }

    // ---- books ----------------------------------------------------------------------------

    // ---- book open / close animation ---------------------------------------------------------
    //
    // One clock (bookAnimT) for both windows, like the page turn. What a screen showed before
    // the switch is kept as a PixelCopy snapshot, so the switch happens at once (the book loads
    // in parallel) and nothing pops. See PLAN.md "Book open / close animation".

    class BookAnim(
        val open: Boolean,
        val bookId: Long,
        /** The book's reading mode (the mode it opens in / is closed from). */
        val mode: ReadingMode,
        val rtl: Boolean,
        val cover: android.graphics.Bitmap,
        /** The cover's place in its library row (top screen px), if known. */
        val from: android.graphics.RectF?,
        /** What each screen showed before the switch (library on open, pages on close). */
        val snapshots: Map<Screen, android.graphics.Bitmap>,
        /** Low battery / no snapshot: a quick fade instead of the cover. */
        val fade: Boolean,
        /** Lifted out of a library row (leave its place empty); false = from the 3D book. */
        val fromRow: Boolean = true,
        /** Hinge style (book modes 1 / 2, Settings → Open & close book → Style), else Classic. */
        val hinge: Boolean = false,
    ) {
        /** Does this animation draw on [screen]? */
        fun uses(screen: Screen): Boolean = screen == Screen.TOP || mode.isBook || mode == ReadingMode.BOTTOM
    }

    var bookAnim by mutableStateOf<BookAnim?>(null)
        private set
    val bookAnimT = androidx.compose.runtime.mutableFloatStateOf(0f)

    /** Opening is held on the closed cover, waiting for the first pages (> 300 ms): show progress. */
    var bookAnimWaiting by mutableStateOf(false)
        private set
    private var bookAnimJob: Job? = null
    private var bookAnimSkip = false

    /** Each library row's cover position (top screen px), recorded as the rows are laid out. */
    val coverBounds = HashMap<Long, android.graphics.RectF>()

    /** The window on each screen (for snapshots). */
    val windows = HashMap<Screen, android.view.Window>()

    /** Low battery (same rule as page turns): below 15 % and not charging. */
    private fun batteryLow(): Boolean {
        if (!settings.simplerTurnOnLowBattery) return false
        val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val b = dev.thorreader.app.ui.BatteryState.from(i) ?: return false
        return b.percent < dev.thorreader.app.engine.PageTurnMath.LOW_BATTERY && !b.charging
    }

    /** What [screen] shows right now, as a bitmap (null if it can't be copied). */
    private suspend fun snapshot(screen: Screen): android.graphics.Bitmap? {
        val w = windows[screen] ?: return null
        val v = w.decorView
        if (v.width <= 0 || v.height <= 0) return null
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        return kotlinx.coroutines.suspendCancellableCoroutine { c ->
            runCatching {
                android.view.PixelCopy.request(w, bmp, { r ->
                    if (r == android.view.PixelCopy.SUCCESS) c.resume(bmp) {} else {
                        bmp.recycle()
                        c.resume(null) {}
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }.onFailure {
                Log.w(TAG, "Snapshot of the ${screen.name.lowercase()} screen failed", it)
                c.resume(null) {}
            }
        }
    }

    /** The book's cover (decoded at a size good for full screen), or a generated one. */
    suspend fun loadCover(book: Book): android.graphics.Bitmap = withContext(Dispatchers.IO) {
        book.coverPath?.let { path ->
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1400) sample *= 2
                android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        } ?: dev.thorreader.app.ui.placeholderCoverBitmap(book.title, book.author)
    }

    /** Open [id] from the library with the cover animation (or instantly if it's off). */
    fun openBookAnimated(id: Long, fromRect: android.graphics.RectF? = null, onSnapshot: (() -> Unit)? = null) {
        if (bookAnim != null) return
        if (!settings.bookAnim) {
            onSnapshot?.invoke()
            return openBook(id)
        }
        scope.launch {
            val book = db.books().get(id) ?: return@launch
            val mode = book.readingMode ?: settings.lastBookMode
            val from = fromRect ?: coverBounds[id]?.let { android.graphics.RectF(it) }
            val cover = loadCover(book)
            if (onSnapshot != null) {
                // Let the screen show what the animation starts from (e.g. the 3D book's front).
                withContext(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                }
            }
            val shot = snapshot(Screen.TOP)
            onSnapshot?.invoke()
            val fade = batteryLow() || shot == null
            if (fade) Log.i(TAG, "Book opens with a quick fade (${if (shot == null) "no snapshot" else "low battery"})")
            startBookAnim(BookAnim(true, id, mode, book.rtl, cover, from, listOfNotNull(shot?.let { Screen.TOP to it }).toMap(), fade, fromRow = fromRect == null, hinge = useHinge(mode, fade)))
            openBook(id)
        }
    }

    // ---- Inspect book (step 24) ----------------------------------------------------------------

    /** The 3D book being inspected (top screen; the bottom screen shows its details), or null. */
    var inspecting by mutableStateOf<dev.thorreader.app.ui.InspectState?>(null)
        private set

    fun startInspect(book: Book) {
        if (inspecting != null || session != null || bookAnim != null) return
        Log.i(TAG, "Inspect book '${book.title}'")
        inspecting = dev.thorreader.app.ui.InspectState(book, coverBounds[book.id]?.let { android.graphics.RectF(it) })
    }

    /** X / B / Back: the book shrinks back into its row (then [endInspect]). */
    fun closeInspect(reason: String) {
        val s = inspecting ?: return
        if (s.closing) return
        Log.i(TAG, "Inspect closed ($reason)")
        s.closing = true
    }

    fun endInspect(s: dev.thorreader.app.ui.InspectState) {
        if (inspecting !== s) return
        inspecting = null
        scope.launch {
            delay(300)
            s.textures?.recycle()
        }
    }

    /** "Read" on the bottom screen: open the book with the normal animation, from the 3D book's front. */
    fun readFromInspect() {
        val s = inspecting ?: return
        if (s.closing) return
        s.touched()
        s.rot = dev.thorreader.app.engine.Rot.IDENTITY
        val from = s.frontRect(screens.topW.toFloat(), screens.topH.toFloat())
        Log.i(TAG, "Read from Inspect: '${s.book.title}'")
        openBookAnimated(s.book.id, from) {
            inspecting = null
            scope.launch {
                delay(1500)
                s.textures?.recycle()
            }
        }
    }

    /** Buttons while inspecting: d-pad turns the book 15°, A = front view, B / Back = close. */
    private fun handleInspectKey(s: dev.thorreader.app.ui.InspectState, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        s.touched()
        val step = Math.toRadians(15.0).toFloat()
        fun turn(axis: dev.thorreader.app.engine.V3, a: Float) {
            s.rot = s.rot.then(dev.thorreader.app.engine.Rot.axisAngle(axis, a)).cleaned()
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> turn(dev.thorreader.app.engine.V3(0f, 1f, 0f), step)
            KeyEvent.KEYCODE_DPAD_RIGHT -> turn(dev.thorreader.app.engine.V3(0f, 1f, 0f), -step)
            KeyEvent.KEYCODE_DPAD_UP -> turn(dev.thorreader.app.engine.V3(1f, 0f, 0f), -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> turn(dev.thorreader.app.engine.V3(1f, 0f, 0f), step)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                s.rot = dev.thorreader.app.engine.Rot.IDENTITY
                s.zoom = 1f
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> closeInspect("button B / Back")
            else -> return e.keyCode != KeyEvent.KEYCODE_VOLUME_UP && e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        }
        return true
    }

    /** Close the book from the reader (menu button, Back) with the reverse animation. */
    fun closeBookAnimated(reason: String) {
        val s = session ?: return
        if (bookAnim != null) return
        if (!settings.bookAnim) return closeBook(reason)
        scope.launch {
            val book = s.book
            val mode = settings.mode
            menuScreen = null
            s.finishTurnNow("closing the book")
            // Let the menu disappear before the pages are copied.
            withContext(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
            }
            val shots = HashMap<Screen, android.graphics.Bitmap>()
            if (mode != ReadingMode.BOTTOM) snapshot(Screen.TOP)?.let { shots[Screen.TOP] = it }
            if (mode.isBook || mode == ReadingMode.BOTTOM) snapshot(Screen.BOTTOM)?.let { shots[Screen.BOTTOM] = it }
            val cover = loadCover(book)
            val fade = batteryLow() || shots.isEmpty()
            startBookAnim(BookAnim(false, book.id, mode, book.rtl, cover, coverBounds[book.id]?.let { android.graphics.RectF(it) }, shots, fade, hinge = useHinge(mode, fade)))
            closeBook(reason)
        }
    }

    /** Hinge only in the sideways book modes; Modes 3 and 4 always use the Classic motion. */
    private fun useHinge(mode: ReadingMode, fade: Boolean): Boolean =
        !fade && mode.isBook && settings.bookAnimStyle == BookAnimStyle.HINGE

    private fun startBookAnim(a: BookAnim) {
        bookAnimJob?.cancel()
        bookAnimSkip = false
        bookAnimT.floatValue = 0f
        bookAnim = a
        Log.i(TAG, "Book ${if (a.open) "open" else "close"} animation (${if (a.hinge) "hinge" else "classic"}, ${settings.bookAnimSpeed.label.lowercase()}), mode ${a.mode.number}${if (a.rtl) ", right-to-left" else ""}${if (a.fade) ", fade" else ""}")
        bookAnimJob = scope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            val dur = debugBookMs()?.toFloat() ?: when {
                a.fade -> BookAnimMath.FADE_MS.toFloat()
                a.hinge -> settings.bookAnimSpeed.hingeMs.toFloat()
                else -> settings.bookAnimSpeed.bookMs.toFloat()
            }
            // Opening never reveals blank pages: wait for them at the hold point (fade: at the start).
            val holdAt = if (a.fade) 0f else BookAnimMath.holdPoint(a.hinge)
            var start = -1L
            var held = !a.open
            var frames = 0
            val freeze = debugProp("debug.thorreader.bookfreeze")?.toFloatOrNull()?.takeIf { it in 0.01f..0.99f }
            var frozeOnce = false
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                if (start < 0) start = now
                var t = ((now - start) / 1_000_000f / dur).coerceIn(0f, 1f)
                if (bookAnimSkip) {
                    bookAnimSkip = false
                    t = if (!held) maxOf(t, holdAt) else 1f
                    start = now - (t * dur * 1_000_000).toLong()
                }
                if (!held && t >= holdAt) {
                    held = true
                    if (!pagesReadyForReveal()) {
                        bookAnimT.floatValue = holdAt
                        val waitStart = now
                        val badge = launch {
                            delay(300)
                            bookAnimWaiting = true
                        }
                        // No frames while waiting: just suspend until the pages exist.
                        androidx.compose.runtime.snapshotFlow { pagesReadyForReveal() }.first { it }
                        badge.cancel()
                        bookAnimWaiting = false
                        val resumed = androidx.compose.runtime.withFrameNanos { it }
                        Log.d(TAG, "Book open held on the cover ${(resumed - waitStart) / 1_000_000} ms for the first pages")
                        start = resumed - (holdAt * dur * 1_000_000).toLong()
                        continue
                    }
                }
                // Debug builds: `setprop debug.thorreader.bookfreeze 0.8` stops at that point (for screenshots) until a tap.
                if (freeze != null && t >= freeze && !frozeOnce) {
                    frozeOnce = true
                    bookAnimT.floatValue = freeze
                    while (!bookAnimSkip) delay(100)
                    bookAnimSkip = false
                    start = androidx.compose.runtime.withFrameNanos { it } - (freeze * dur * 1_000_000).toLong()
                    continue
                }
                bookAnimT.floatValue = t
                frames++
                if (t >= 1f) break
            }
            Log.d(TAG, "Book ${if (a.open) "open" else "close"} animation done: $frames frames")
            endBookAnim(a)
        }
    }

    /** Debug builds only: `adb shell setprop debug.thorreader.bookms 6000` slows the animation for screen recordings. */
    private fun debugProp(name: String): String? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return runCatching { Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().readText().trim() }.getOrNull()?.ifEmpty { null }
    }

    private fun debugBookMs(): Int? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.thorreader.bookms")).inputStream.bufferedReader().readText().trim().toIntOrNull()
        }.getOrNull()?.takeIf { it in 100..30_000 }
    }

    /** The first pages are rendered (or the book failed / was closed): the cover may open. */
    private fun pagesReadyForReveal(): Boolean {
        val s = session ?: return true
        return s.firstViewShown || s.error != null
    }

    /** Tap / A / B / Back during the animation: jump to the end (an opening waits for its pages). */
    fun skipBookAnim() {
        if (bookAnim == null) return
        bookAnimSkip = true
    }

    private fun endBookAnim(a: BookAnim) {
        if (bookAnim !== a) return
        bookAnim = null
        bookAnimWaiting = false
        bookAnimT.floatValue = 0f
        bookAnimJob = null
        // Free the bitmaps once the last frame that used them is gone.
        scope.launch {
            delay(200)
            a.snapshots.values.forEach { it.recycle() }
            a.cover.recycle()
        }
    }

    /** Open a book in its own saved mode (or the last mode used, if it has none yet). */
    fun openBook(id: Long) {
        scope.launch {
            val book = db.books().get(id) ?: return@launch
            closeBook("opening another book", returnToLibrary = false)
            val mode = book.readingMode ?: settings.lastBookMode
            if (book.readingMode == null) launch(Dispatchers.IO) { db.books().setMode(book.id, mode.name) }
            applyBookText(book)
            Log.i(TAG, "Opening '${book.title}' (${book.format}) in mode ${mode.number}: ${book.uri}")
            session = ReaderSession(this@ThorApp, book.copy(mode = mode.name))
            userActive()
            setMode(mode)
            tutorialDone(TutorialStep.ADD_BOOKS)
            tutorialDone(TutorialStep.OPEN_BOOK)
        }
    }

    /**
     * Text settings are per book: show the book's own, or give a book that has none yet the
     * current ones (so later changes in other books don't affect it).
     */
    private fun applyBookText(book: Book) {
        val s = settings
        if (book.fontTop != null) {
            s.applyBookText(book.fontTop, book.fontBottom ?: book.fontTop, book.sameFont ?: true, book.marginLevel ?: s.marginLevel)
        } else {
            scope.launch(Dispatchers.IO) { db.books().setText(book.id, s.fontPt, s.fontPtBottom, s.sameFont, s.marginLevel) }
        }
        val sizes = if (s.sameFont) "${s.fontPt}pt (both screens)" else "${s.fontPt}pt top / ${s.fontPtBottom}pt bottom"
        Log.i(TAG, "Text for '${book.title}': $sizes, margins ${s.marginLevel}")
    }

    /** A text setting changed in the menu: save it to the open book. */
    private fun saveBookText() {
        val id = session?.book?.id ?: return
        val s = settings
        scope.launch(Dispatchers.IO) { db.books().setText(id, s.fontPt, s.fontPtBottom, s.sameFont, s.marginLevel) }
    }

    // ---- auto-bookmark when leaving / coming back ----------------------------------------
    //
    // Leaving = our last visible activity stopped (app backgrounded or swiped away, lid closed)
    // or the screen turned off, while a book is open: bookmark the current (later) page.
    // Coming straight back to that same open book: remove that bookmark after a short moment,
    // so the reader sees the ribbon slide away. Mode changes move the reader between
    // activities; those stops/starts are ignored.

    private var startedActivities = 0

    /** Id of the open book whose bookmark was set automatically on leaving, or null. */
    private var autoBookmarkBook: Long? = null
    private var returnJob: Job? = null

    // ---- typing on the bottom screen ------------------------------------------------------

    /**
     * The text field being edited (library search, Edit info, Add author/series, Settings), or
     * null. While set, the bottom screen shows the keyboard (TypingPresentation) so the top
     * screen stays fully visible; cleared, the bottom screen is released again.
     */
    var typing by mutableStateOf<dev.thorreader.app.ui.TypingSession?>(null)
        private set

    fun startTyping(s: dev.thorreader.app.ui.TypingSession) {
        if (session != null) return // reading: there are no text fields
        if (typing != null) stopTyping("another field")
        typing = s
        Log.i(TAG, "Typing started (${s.kind.name.lowercase()} field \"${s.hint}\")")
    }

    fun stopTyping(reason: String) {
        val s = typing ?: return
        typing = null
        Log.i(TAG, "Typing ended ($reason)")
        s.onDone()
    }

    /**
     * Keys while typing: d-pad moves the keyboard highlight, A types the highlighted key,
     * B = backspace (on an empty field: done), Start = Done. A physical keyboard types directly.
     */
    fun handleTypingKey(e: KeyEvent): Boolean {
        val s = typing ?: return false
        val code = e.keyCode
        if (e.action != KeyEvent.ACTION_DOWN) {
            return code in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER,
            ) || e.unicodeChar != 0
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> s.moveHighlight(-1, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> s.moveHighlight(1, 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> s.moveHighlight(0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> s.moveHighlight(0, 1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                val k = s.highlighted() ?: return true
                tick()
                if (!s.press(k)) stopTyping("Done (A)")
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                Log.d(TAG, "Typing: B (code $code)")
                if (s.text.isEmpty()) stopTyping("B on an empty field") else s.backspace()
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> stopTyping("Start")
            KeyEvent.KEYCODE_DEL -> s.backspace()
            KeyEvent.KEYCODE_FORWARD_DEL -> if (s.cursor < s.text.length) { s.moveCursor(1); s.backspace() }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (s.kind == dev.thorreader.app.ui.TypingSession.Kind.MULTILINE && !e.isShiftPressed) s.insert("\n") else stopTyping("Enter")
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> stopTyping("Back")
            else -> {
                // Physical / Bluetooth keyboard: type the character.
                val ch = e.unicodeChar
                if (ch == 0) return false
                s.insert(String(Character.toChars(ch)))
            }
        }
        return true
    }

    /** Keep the re-creatable caches within the limit (background, low priority). */
    fun trimCaches() {
        scope.launch(Background.dispatcher) { runCatching { dev.thorreader.app.io.CacheBudget.trim(this@ThorApp, settings.cacheLimitMb) } }
    }

    // ---- screen on while reading ---------------------------------------------------------

    /**
     * Keep the screen on while reading, but not forever: it is released [KEEP_ON_MS] after the
     * last touch or button, so a forgotten book lets the screen time out. The library never
     * holds the screen on.
     */
    var keepScreenOn by mutableStateOf(false)
        private set
    private var keepOnJob: Job? = null

    /** Any touch or button on either screen. */
    fun userActive() {
        if (session == null) {
            keepScreenOn = false
            return
        }
        if (!keepScreenOn) keepScreenOn = true
        keepOnJob?.cancel()
        keepOnJob = scope.launch {
            delay(KEEP_ON_MS)
            keepScreenOn = false
            Log.i(TAG, "No input for ${KEEP_ON_MS / 60000} min: screen may time out now")
        }
    }

    fun onHostStart(who: String) {
        startedActivities++
        Background.setVisible(true)
        if (startedActivities == 1) onReturn("$who started again")
    }

    fun onHostStop(who: String) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities > 0) return
        Background.setVisible(false) // background work pauses until we're visible again
        if (SystemClock.uptimeMillis() - lastModeChangeAt < HANDOVER_GUARD_MS) {
            Log.i(TAG, "$who stopped during a mode change: not leaving")
            return
        }
        onLeave("$who stopped (app in background, swiped away or lid closed)")
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    Background.setScreenOn(false) // no work while the screen is off
                    onLeave("screen off")
                }
                android.content.Intent.ACTION_SCREEN_ON -> {
                    Background.setScreenOn(true)
                    if (startedActivities > 0) onReturn("screen on")
                }
            }
        }
    }

    private fun registerScreenReceiver() {
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED)
    }

    private fun onLeave(reason: String) {
        Log.i(TAG, "Leaving: $reason")
        session?.finishTurnNow("leaving: $reason") // lid closed mid-turn: finish instantly (position is saved below)
        val s = session ?: return
        returnJob?.cancel()
        if (autoBookmarkBook == s.book.id && s.bookmarkHere) return // already done (screen off + stop)
        if (s.setBookmarkOnLaterPage("auto, $reason")) autoBookmarkBook = s.book.id
    }

    private fun onReturn(reason: String) {
        Log.i(TAG, "Back: $reason")
        val s = session ?: return
        if (autoBookmarkBook != s.book.id) return
        returnJob?.cancel()
        returnJob = scope.launch {
            delay(AUTO_OFF_DELAY_MS) // let the reader see the ribbon before it slides away
            if (session === s && startedActivities > 0 && autoBookmarkBook == s.book.id) {
                s.clearBookmark("auto, back to the book: $reason")
                autoBookmarkBook = null
            }
        }
    }

    /** "Mark as completed" / "Mark as unread" (unread keeps the reading position). */
    fun setCompleted(book: Book, completed: Boolean) {
        val at = if (completed) System.currentTimeMillis() else null
        session?.let { if (it.book.id == book.id) it.applyCompleted(at) }
        scope.launch(Dispatchers.IO) { db.books().setCompleted(book.id, at) }
        Log.i(TAG, "'${book.title}' marked ${if (completed) "completed" else "unread"}")
    }

    /**
     * The ONLY way a book closes. [reason] is logged so unexpected closes can be traced.
     * Afterwards the app returns to Mode 3 (library on the top screen, bottom screen released);
     * the book keeps its own saved mode.
     */
    fun closeBook(reason: String, returnToLibrary: Boolean = true) {
        val s = session ?: return
        stopRepeating()
        Log.i(TAG, "Closed '${s.book.title}' (reason: $reason)")
        autoBookmarkBook = null // a closed book keeps its bookmark
        returnJob?.cancel()
        s.close()
        session = null
        keepScreenOn = false // the library lets the screen time out normally
        menuScreen = null
        if (returnToLibrary) setMode(ReadingMode.TOP, showLabel = false)
    }

    /** One light haptic tick (edge of the book), if the device can. */
    fun tick() {
        runCatching {
            val v = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
            if (v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
        }
    }

    /**
     * Reading direction for [book] and every other book in the same series, now and for
     * volumes imported later.
     */
    fun setReadingDirection(book: Book, rtl: Boolean) {
        session?.let { if (it.book.id == book.id) it.applyRtl(rtl) }
        scope.launch(Dispatchers.IO) {
            val dao = db.books()
            dao.setRtlOverride(book.id, rtl)
            val changed = mutableListOf(book.id)
            val key = seriesKey(book.series)
            if (key != null) {
                dao.setSeriesDirection(SeriesDirection(key, rtl))
                dao.booksInAnySeries().filter { seriesKey(it.series) == key && it.id != book.id }.forEach {
                    dao.setRtlOverride(it.id, rtl)
                    changed += it.id
                }
            }
            Log.i(TAG, "Reading direction ${if (rtl) "right-to-left" else "left-to-right"} for '${book.title}'" +
                if (key != null) " and its series '${book.series}' (${changed.size} books)" else "")
            launch(Dispatchers.Main) { session?.let { if (it.book.id in changed) it.applyRtl(rtl) } }
        }
    }

    fun removeBook(book: Book) {
        if (session?.book?.id == book.id) closeBook("book removed from library")
        scope.launch(Dispatchers.IO) {
            db.books().delete(book.id)
            book.coverPath?.let { File(it).delete() }
            dev.thorreader.app.convert.BookSource.convertedFile(this@ThorApp, book.uri, book.sizeBytes).delete()
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(book.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            Log.i(TAG, "Removed '${book.title}' from library")
        }
    }

    fun toggleMenu(screen: Screen) {
        menuScreen = if (menuScreen == screen) null else screen
    }

    /**
     * Back / B button. Returns false if there is nothing to go back from (leave the app).
     * Back events that arrive while the reader is moving between screens (just after a mode
     * change) are ignored, so a mode switch can never close the book.
     */
    fun handleBack(source: String): Boolean {
        val sinceModeChange = SystemClock.uptimeMillis() - lastModeChangeAt
        if (inspecting != null && session == null) {
            closeInspect("Back ($source)")
            return true
        }
        if (bookAnim != null) {
            skipBookAnim()
            return true
        }
        if (typing != null) {
            stopTyping("Back ($source)")
            return true
        }
        return when {
            sinceModeChange < HANDOVER_GUARD_MS -> {
                Log.w(TAG, "Back from $source ignored: ${sinceModeChange} ms after a mode change (screen handover)")
                true
            }
            session?.scrubbing == true -> {
                session?.endScrub(false)
                true
            }
            session == null && libraryBack?.invoke() == true -> true
            menuScreen != null -> {
                if (menuBack?.invoke() != true) menuScreen = null
                true
            }
            session != null -> {
                closeBookAnimated("Back pressed ($source)")
                true
            }
            else -> false
        }
    }

    // ---- buttons --------------------------------------------------------------------------

    private enum class Dir { LEFT, RIGHT, UP, DOWN }

    /** The direction a d-pad button physically points while the device is held for [mode]. */
    private fun physical(code: Int, mode: ReadingMode): Dir? {
        val pressed = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            else -> return null
        }
        return when (mode) {
            // Turned counter-clockwise: the device's "up" now points left, "down" points right.
            ReadingMode.BOOK -> when (pressed) {
                Dir.UP -> Dir.LEFT; Dir.DOWN -> Dir.RIGHT; Dir.LEFT -> Dir.DOWN; Dir.RIGHT -> Dir.UP
            }
            // Turned clockwise: "up" points right, "down" points left.
            ReadingMode.BOOK_REVERSED -> when (pressed) {
                Dir.UP -> Dir.RIGHT; Dir.DOWN -> Dir.LEFT; Dir.LEFT -> Dir.UP; Dir.RIGHT -> Dir.DOWN
            }
            else -> pressed
        }
    }

    private val handledKeys = setOf(
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP,
    )

    // Held buttons flip continuously: one turn at once, then after a pause faster and faster.
    private var repeatJob: Job? = null
    private var repeatKey = 0

    private fun startRepeating(code: Int, action: () -> Boolean) {
        repeatJob?.cancel()
        repeatKey = code
        if (!action()) return
        repeatJob = scope.launch {
            delay(REPEAT_FIRST_DELAY_MS)
            var interval = REPEAT_START_MS
            while (true) {
                // Held button: continuous flipping uses a fast slide, never the slow curl.
                val s = session
                s?.repeatingTurns = true
                val moved = try { action() } finally { s?.repeatingTurns = false }
                if (!moved) break // reached the edge of the book
                delay(interval)
                interval = (interval * REPEAT_ACCEL).toLong().coerceAtLeast(REPEAT_FASTEST_MS)
            }
        }
    }

    fun stopRepeating() {
        repeatJob?.cancel()
        repeatJob = null
    }

    /** Hardware buttons from either screen. Returns true if consumed. */
    fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        // Book opening / closing: A, B or Back skip to the end; nothing else happens meanwhile.
        if (bookAnim != null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && code in setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER)) skipBookAnim()
            return code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
        }
        // Inspect book: the buttons turn the 3D book.
        inspecting?.let { s ->
            if (session == null && handleInspectKey(s, e)) return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            Log.d(TAG, "Key down: ${KeyEvent.keyCodeToString(code)} ($code)")
        }
        // Typing a text field (bottom-screen keyboard): the buttons and any keyboard type there.
        if (typing != null && handleTypingKey(e)) return true
        val s = session
        if (s != null && s.scrubScreen != null) return handleScrubKey(s, e)
        // Reader menu open: the d-pad and A move through the menu instead of turning pages, and
        // B closes the colour picker first, then the menu.
        if (s != null && menuScreen != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER,
                -> return false
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && menuBack?.invoke() != true) menuScreen = null
                    return true
                }
            }
        }
        // Library: B closes search / a category / the sort menu (otherwise it's the normal Back).
        if (s == null && code == KeyEvent.KEYCODE_BUTTON_B && libraryBack != null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) libraryBackPending = libraryBack?.invoke() == true
            return libraryBackPending
        }
        // Select changes the mode only when turned on in the reader menu (off by default).
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT && !settings.selectCyclesMode) return false
        // In the library only Select is ours; the d-pad keeps its normal focus behaviour.
        if (code != KeyEvent.KEYCODE_BUTTON_SELECT && (s == null || code !in handledKeys)) return false
        if (code !in handledKeys) return false
        if (e.action == KeyEvent.ACTION_UP) {
            if (code == repeatKey) stopRepeating()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true // we do our own repeat
        when (code) {
            // Modes belong to books now: in the library Select does nothing.
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (s != null) cycleMode()
            // Y swaps the two pages between the screens (comics, book modes).
            KeyEvent.KEYCODE_BUTTON_Y -> if (s != null) {
                Log.i(TAG, "Y button (${KeyEvent.keyCodeToString(code)}, code $code): swap screens")
                s.toggleSwap(settings.mode, "Y button")
            }
            // Start opens/closes the reader menu (the Menu key too, in case the Thor sends that).
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> if (s != null) {
                Log.i(TAG, "Start/Menu key (${KeyEvent.keyCodeToString(code)}, code $code): reader menu")
                toggleMenu(if (settings.mode == ReadingMode.BOTTOM) Screen.BOTTOM else Screen.TOP)
            }
            else -> {
                val r = s ?: return true
                // Mode 1, comics/manga/PDF: d-pad RIGHT swaps the screens instead of turning the
                // page (Y also swaps). Mode 2 and other modes keep d-pad right as a page turn.
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && settings.mode == ReadingMode.BOOK && !r.reflowable) {
                    Log.i(TAG, "D-pad RIGHT (code $code): mode 1, fixed-layout -> swap screens")
                    r.toggleSwap(settings.mode, "d-pad right")
                    return true
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val dir = physical(code, settings.mode)
                    Log.i(TAG, "D-pad RIGHT (code $code): mode ${settings.mode.number}, ${if (r.reflowable) "book" else "fixed-layout"} -> page turn (${dir?.name?.lowercase()} side)")
                }
                val turn: (() -> Boolean)? = when (code) {
                    KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2,
                    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2,
                    -> shoulderAction(r, code)
                    KeyEvent.KEYCODE_PAGE_DOWN -> { { r.forward(TurnCause.BUTTON) } }
                    KeyEvent.KEYCODE_PAGE_UP -> { { r.back(TurnCause.BUTTON) } }
                    else -> when (physical(code, settings.mode)) {
                        Dir.RIGHT -> { { r.physicalRight(TurnCause.BUTTON) } }
                        Dir.LEFT -> { { r.physicalLeft(TurnCause.BUTTON) } }
                        Dir.DOWN -> { { r.forward(TurnCause.BUTTON) } }
                        Dir.UP -> { { r.back(TurnCause.BUTTON) } }
                        null -> null
                    }
                }
                turn?.let { startRepeating(code, it) }
            }
        }
        return true
    }

    /**
     * A shoulder press: see [ShoulderButtons] (manga swap per series). Logged with mode,
     * direction, toggle and result; holding the button repeats the same action.
     */
    private fun shoulderAction(r: ReaderSession, code: Int): () -> Boolean {
        val right = code == KeyEvent.KEYCODE_BUTTON_R1 || code == KeyEvent.KEYCODE_BUTTON_R2
        val mode = settings.mode
        val rtl = r.book.rtl
        val swap = settings.mangaShoulderSwap(r.book.id, r.book.series)
        val t = ShoulderButtons.turn(right, mode, rtl, swap)
        Log.i(
            TAG,
            "Shoulder ${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_BUTTON_")} (code $code): mode ${mode.number}, " +
                "${if (rtl) "right-to-left" else "left-to-right"}, swap ${if (!rtl) "n/a" else if (swap) "ON" else "OFF"} -> " +
                t.label + if (t == Turn.PAGE_LEFT || t == Turn.PAGE_RIGHT) " = ${ShoulderButtons.meaning(t, rtl).label}" else "",
        )
        return when (t) {
            Turn.NEXT -> { { r.forward(TurnCause.BUTTON) } }
            Turn.PREVIOUS -> { { r.back(TurnCause.BUTTON) } }
            Turn.PAGE_RIGHT -> { { r.physicalRight(TurnCause.BUTTON) } }
            Turn.PAGE_LEFT -> { { r.physicalLeft(TurnCause.BUTTON) } }
        }
    }

    /**
     * Scrubber open: left/right = 1 page, shoulders = 10 pages (both in the physical direction,
     * so right-to-left books run right-to-left), A = jump there, B = cancel and go back.
     */
    private fun handleScrubKey(s: ReaderSession, e: KeyEvent): Boolean {
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_UP) {
            if (code == repeatKey) stopRepeating()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true
        val step: Int? = when (code) {
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> 10
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> -10
            else -> when (physical(code, settings.mode)) {
                Dir.RIGHT, Dir.DOWN -> 1
                Dir.LEFT, Dir.UP -> -1
                null -> null
            }
        }
        when {
            step != null -> startRepeating(code) {
                val before = s.scrubPos
                s.scrubPhysical(step)
                s.scrubPos != before
            }
            code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER -> s.endScrub(true)
            code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK -> s.endScrub(false)
            else -> return false
        }
        return true
    }

    companion object {
        lateinit var instance: ThorApp
            private set

        /** The app is running (false in plain unit tests). */
        val ready: Boolean get() = ::instance.isInitialized
        private const val HANDOVER_GUARD_MS = 2000L
        private const val AUTO_OFF_DELAY_MS = 1200L
        private const val KEEP_ON_MS = 10 * 60_000L
        private const val REPEAT_FIRST_DELAY_MS = 450L
        private const val REPEAT_START_MS = 320L
        private const val REPEAT_FASTEST_MS = 60L
        private const val REPEAT_ACCEL = 0.85f
    }
}
