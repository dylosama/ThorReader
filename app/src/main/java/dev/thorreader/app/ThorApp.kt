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
import dev.thorreader.app.engine.ScreenMetrics
import dev.thorreader.app.importer.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    lateinit var lookup: dev.thorreader.app.metadata.MetadataLookup
        private set
    val scope = MainScope()

    var screens by mutableStateOf(ScreenMetrics.DEFAULT)
        private set

    /** The open book, or null when the library is showing. */
    var session by mutableStateOf<ReaderSession?>(null)
        private set

    private var menuScreenState = mutableStateOf<Screen?>(null)

    /** Which screen the reader menu is open on (null = closed). */
    var menuScreen: Screen?
        get() = menuScreenState.value
        set(value) {
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

    fun onHostStart(who: String) {
        startedActivities++
        if (startedActivities == 1) onReturn("$who started again")
    }

    fun onHostStop(who: String) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities > 0) return
        if (SystemClock.uptimeMillis() - lastModeChangeAt < HANDOVER_GUARD_MS) {
            Log.i(TAG, "$who stopped during a mode change: not leaving")
            return
        }
        onLeave("$who stopped (app in background, swiped away or lid closed)")
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> onLeave("screen off")
                android.content.Intent.ACTION_SCREEN_ON -> if (startedActivities > 0) onReturn("screen on")
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
                closeBook("Back pressed ($source)")
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
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU,
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
                if (!action()) break // reached the edge of the book
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
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            Log.d(TAG, "Key down: ${KeyEvent.keyCodeToString(code)} ($code)")
        }
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
            // Start opens/closes the reader menu (the Menu key too, in case the Thor sends that).
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> if (s != null) {
                Log.i(TAG, "Start/Menu key (${KeyEvent.keyCodeToString(code)}, code $code): reader menu")
                toggleMenu(if (settings.mode == ReadingMode.BOTTOM) Screen.BOTTOM else Screen.TOP)
            }
            else -> {
                val r = s ?: return true
                val turn: (() -> Boolean)? = when (code) {
                    KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> r::physicalRight
                    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> r::physicalLeft
                    KeyEvent.KEYCODE_PAGE_DOWN -> r::forward
                    KeyEvent.KEYCODE_PAGE_UP -> r::back
                    else -> when (physical(code, settings.mode)) {
                        Dir.RIGHT -> r::physicalRight
                        Dir.LEFT -> r::physicalLeft
                        Dir.DOWN -> r::forward
                        Dir.UP -> r::back
                        null -> null
                    }
                }
                turn?.let { startRepeating(code, it) }
            }
        }
        return true
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
        private const val HANDOVER_GUARD_MS = 2000L
        private const val AUTO_OFF_DELAY_MS = 1200L
        private const val REPEAT_FIRST_DELAY_MS = 450L
        private const val REPEAT_START_MS = 320L
        private const val REPEAT_FASTEST_MS = 60L
        private const val REPEAT_ACCEL = 0.85f
    }
}
