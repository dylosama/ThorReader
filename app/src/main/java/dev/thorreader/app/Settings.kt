package dev.thorreader.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix

/** A physical screen of the Thor. */
enum class Screen { TOP, BOTTOM }

/** The four manual reading modes. */
enum class ReadingMode(val number: Int, val title: String, val hint: String) {
    BOOK(1, "Book", "Hold sideways. Top screen = left page."),
    BOOK_REVERSED(2, "Book reversed", "Hold sideways the other way. Top screen = right page."),
    TOP(3, "Top screen", "Two pages on the top screen. Bottom screen is free."),
    BOTTOM(4, "Bottom screen", "Read on the bottom screen. Top screen is free.");

    val isBook get() = this == BOOK || this == BOOK_REVERSED

    fun next(): ReadingMode = entries[(ordinal + 1) % entries.size]

    /**
     * Rotation applied to our content so text is upright when held for this mode.
     * BOOK: device turned counter-clockwise (top screen ends up on the left) -> rotate content
     * clockwise (+90). BOOK_REVERSED is the mirror image.
     */
    val contentRotation: Float
        get() = when (this) {
            BOOK -> 90f
            BOOK_REVERSED -> -90f
            else -> 0f
        }
}

/** Page theme for reflowable books. Applied as a colour transform on the rendered page. */
enum class PageTheme(val label: String, val paper: Color, val ink: Color) {
    PAPER("Paper", Color(0xFFFAF7F0), Color(0xFF1C1C1C)),
    SEPIA("Sepia", Color(0xFFF1E3C8), Color(0xFF4A3A28)),
    NIGHT("Night", Color(0xFF000000), Color(0xFF9A9A9A));

    /** Maps white -> [paper] and black -> [ink], linearly per channel. */
    val colorMatrix: ColorMatrix by lazy {
        fun row(p: Float, i: Float, idx: Int): FloatArray {
            val r = FloatArray(5)
            r[idx] = p - i
            r[4] = i * 255f
            return r
        }
        ColorMatrix(
            row(paper.red, ink.red, 0) +
                row(paper.green, ink.green, 1) +
                row(paper.blue, ink.blue, 2) +
                floatArrayOf(0f, 0f, 0f, 1f, 0f)
        )
    }
}

/** Library top-left switch. */
enum class Shelf(val label: String) { LIBRARY("Library"), COMPLETED("Completed") }

/** How the library list is built: the flat list (ALL) or one of the groupings. */
enum class LibraryView(val label: String) { ALL("All"), AUTHORS("Author"), SERIES("Series"), TYPE("Type"), GENRE("Genre") }

/** Orders for book lists. (AUTHOR is used for tie-breaks and tests; it isn't a chip.) */
enum class SortOrder(val label: String) {
    RECENTLY_READ("Recently read"),
    RECENTLY_ADDED("Recently added"),
    TITLE("Title"),
    AUTHOR("Author"),
    PROGRESS("Progress");

    /** Starting direction: A→Z for text, newest / highest first for the rest. */
    val defaultAscending: Boolean get() = this == TITLE || this == AUTHOR
}

/**
 * The library's one row of chips, in order. Flat chips sort the whole list ([sort]); grouped
 * chips show a list of names ([view]).
 */
enum class LibraryChip(val label: String, val sort: SortOrder? = null, val view: LibraryView? = null) {
    RECENTLY_READ("Recently read", sort = SortOrder.RECENTLY_READ),
    RECENTLY_ADDED("Recently added", sort = SortOrder.RECENTLY_ADDED),
    PROGRESS("Progress", sort = SortOrder.PROGRESS),
    TITLE("Title", sort = SortOrder.TITLE),
    AUTHOR("Author", view = LibraryView.AUTHORS),
    SERIES("Series", view = LibraryView.SERIES),
    GENRE("Genre", view = LibraryView.GENRE),
    TYPE("Type", view = LibraryView.TYPE);

    val grouped: Boolean get() = view != null
}

/** First-run tutorial steps, shown once each, in this order. */
enum class TutorialStep { ADD_BOOKS, OPEN_BOOK, OPEN_MENU, CHOOSE_MODE }

/** Persistent user settings (SharedPreferences), observable by Compose. */
class Settings(private val prefs: SharedPreferences) {

    /** The mode currently shown. The app always starts in Mode 3 (library on the top screen). */
    var mode by mutableStateOf(ReadingMode.TOP)
        private set
    var fontPt by mutableFloatStateOf(prefs.getFloat("font_pt", DEFAULT_FONT_PT))
        private set
    var marginLevel by mutableIntStateOf(prefs.getInt("margin_level", 1).coerceIn(0, MARGIN_DP.lastIndex))
        private set
    var theme by mutableStateOf(enumPref("theme", PageTheme.PAPER))
        private set
    var bottomTwoPage by mutableStateOf(prefs.getBoolean("bottom_two_page", false))
        private set

    /** The last mode used while reading: newly imported books start in it. */
    var lastBookMode by mutableStateOf(enumPref("last_book_mode", ReadingMode.TOP))
        private set

    /** Running header (chapter) and page number on pages. */
    var showHeader by mutableStateOf(prefs.getBoolean("show_header", true))
        private set

    fun updateLastBookMode(m: ReadingMode) {
        lastBookMode = m
        prefs.edit().putString("last_book_mode", m.name).apply()
    }

    fun updateShowHeader(show: Boolean) {
        showHeader = show
        prefs.edit().putBoolean("show_header", show).apply()
    }

    /** How long to press and hold a page to open the menu. */
    var menuHoldMs by mutableIntStateOf(prefs.getInt("menu_hold_ms", DEFAULT_HOLD_MS).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS))
        private set

    fun updateMenuHold(ms: Int) {
        menuHoldMs = ms.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        prefs.edit().putInt("menu_hold_ms", menuHoldMs).apply()
    }

    fun updateMode(m: ReadingMode) {
        mode = m
    }

    // ---- text settings ----------------------------------------------------------------------
    //
    // [fontPt] is the top screen's size (and the only size while [sameFont] is on);
    // [fontPtBottom] the bottom screen's size when [sameFont] is off. They are saved per book
    // (ThorApp.saveBookText, via [onTextChanged]); the prefs keep the last values as defaults.

    /** Bottom screen's text size when "Same text size on both screens" is off. */
    var fontPtBottom by mutableFloatStateOf(prefs.getFloat("font_pt_bottom", prefs.getFloat("font_pt", DEFAULT_FONT_PT)))
        private set

    /** "Same text size on both screens" (default on). */
    var sameFont by mutableStateOf(prefs.getBoolean("same_font", true))
        private set

    /** Called after any text setting changes (to save it to the open book). */
    var onTextChanged: (() -> Unit)? = null

    /** The text size a screen uses. */
    fun fontFor(screen: Screen): Float = if (sameFont || screen == Screen.TOP) fontPt else fontPtBottom

    fun updateFont(pt: Float) {
        fontPt = pt.coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        prefs.edit().putFloat("font_pt", fontPt).apply()
        onTextChanged?.invoke()
    }

    fun updateFontBottom(pt: Float) {
        fontPtBottom = pt.coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        prefs.edit().putFloat("font_pt_bottom", fontPtBottom).apply()
        onTextChanged?.invoke()
    }

    /** Change the size of [screen] (with the same-size toggle on, that is the shared size). */
    fun updateFontFor(screen: Screen, pt: Float) = if (sameFont || screen == Screen.TOP) updateFont(pt) else updateFontBottom(pt)

    fun updateSameFont(same: Boolean) {
        if (!same && sameFont) fontPtBottom = fontPt // start from the size both screens had
        sameFont = same
        prefs.edit().putBoolean("same_font", same).putFloat("font_pt_bottom", fontPtBottom).apply()
        onTextChanged?.invoke()
    }

    fun updateMargin(level: Int) {
        marginLevel = level.coerceIn(0, MARGIN_DP.lastIndex)
        prefs.edit().putInt("margin_level", marginLevel).apply()
        onTextChanged?.invoke()
    }

    /** Show a book's own text settings (not saved as the defaults). */
    fun applyBookText(top: Float, bottom: Float, same: Boolean, margin: Int) {
        fontPt = top.coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        fontPtBottom = bottom.coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        sameFont = same
        marginLevel = margin.coerceIn(0, MARGIN_DP.lastIndex)
    }

    // ---- library ------------------------------------------------------------------------

    var shelf by mutableStateOf(enumPref("lib_shelf", Shelf.LIBRARY))
        private set
    fun updateShelf(s: Shelf) {
        shelf = s
        prefs.edit().putString("lib_shelf", s.name).apply()
    }

    /** The active chip of the library row (same for Library and Completed). */
    var libraryChip by mutableStateOf(enumPref("lib_chip", LibraryChip.RECENTLY_READ))
        private set

    /** The last flat sort chosen: the order of the flat list and of books inside a group. */
    var flatSort by mutableStateOf(enumPref("flat_sort", SortOrder.RECENTLY_READ).let { if (it == SortOrder.AUTHOR) SortOrder.RECENTLY_READ else it })
        private set
    var flatAsc by mutableStateOf(prefs.getBoolean("flat_asc", flatSort.defaultAscending))
        private set

    /** Group names A→Z (↑) or Z→A (↓). */
    var groupAsc by mutableStateOf(prefs.getBoolean("group_asc", true))
        private set

    /** The flat list or the grouping shown. */
    val libraryView: LibraryView get() = libraryChip.view ?: LibraryView.ALL

    /** Is the active chip showing ↑ (ascending)? */
    val chipAscending: Boolean get() = if (libraryChip.grouped) groupAsc else flatAsc

    /**
     * Tap on a chip: the active one flips its direction; another one becomes active with its
     * starting direction (newest / highest first, A→Z for Title and for group names).
     */
    fun tapChip(chip: LibraryChip) {
        val again = chip == libraryChip
        libraryChip = chip
        val sort = chip.sort
        if (sort != null) {
            flatAsc = if (again) !flatAsc else sort.defaultAscending
            flatSort = sort
        } else {
            groupAsc = if (again) !groupAsc else true
        }
        prefs.edit().putString("lib_chip", chip.name).putString("flat_sort", flatSort.name)
            .putBoolean("flat_asc", flatAsc).putBoolean("group_asc", groupAsc).apply()
    }

    // ---- Select button -----------------------------------------------------------------

    /** The Select button cycles the reading mode while reading. Off by default (menu toggle). */
    var selectCyclesMode by mutableStateOf(prefs.getBoolean("select_cycles_mode", false))
        private set

    fun updateSelectCyclesMode(on: Boolean) {
        selectCyclesMode = on
        prefs.edit().putBoolean("select_cycles_mode", on).apply()
    }

    /**
     * "Swap L1/R1 for manga" per series (or per book without a series): on by default, so only
     * the series where it was turned OFF are stored.
     */
    private var shoulderSwapOff by mutableStateOf(prefs.getStringSet("shoulder_swap_off", emptySet())!!.toSet())

    private fun shoulderKey(bookId: Long, series: String?) = dev.thorreader.app.data.seriesKey(series) ?: "book:$bookId"

    fun mangaShoulderSwap(bookId: Long, series: String?): Boolean = shoulderKey(bookId, series) !in shoulderSwapOff

    fun updateMangaShoulderSwap(bookId: Long, series: String?, on: Boolean) {
        val k = shoulderKey(bookId, series)
        shoulderSwapOff = if (on) shoulderSwapOff - k else shoulderSwapOff + k
        prefs.edit().putStringSet("shoulder_swap_off", shoulderSwapOff).apply()
    }

    /** Comics in book modes: swipe up to swap the pages between the screens (default on). */
    var swipeSwap by mutableStateOf(prefs.getBoolean("swipe_swap", true))
        private set

    fun updateSwipeSwap(on: Boolean) {
        swipeSwap = on
        prefs.edit().putBoolean("swipe_swap", on).apply()
    }

    // ---- online book info --------------------------------------------------------------

    /** "Look up book info online" (default on). */
    var lookupOnline by mutableStateOf(prefs.getBoolean("lookup_online", true))
        private set

    fun updateLookupOnline(on: Boolean) {
        lookupOnline = on
        prefs.edit().putBoolean("lookup_online", on).apply()
    }

    // ---- bookmark colour (one for all books) --------------------------------------------

    /** ARGB of the bookmark ribbon (and the current page number in the library). */
    var bookmarkColor by mutableIntStateOf(prefs.getInt("bookmark_color", DEFAULT_BOOKMARK_COLOR))
        private set

    fun updateBookmarkColor(argb: Int) {
        bookmarkColor = argb
        prefs.edit().putInt("bookmark_color", argb).apply()
    }

    // ---- first-run tutorial -------------------------------------------------------------

    /** Tutorial steps already shown/done: bit n = [TutorialStep] ordinal n. */
    var tutorialDone by mutableIntStateOf(prefs.getInt("tutorial_done", 0))
        private set

    fun updateTutorialDone(mask: Int) {
        tutorialDone = mask
        prefs.edit().putInt("tutorial_done", mask).apply()
    }

    fun updateTheme(t: PageTheme) {
        theme = t
        prefs.edit().putString("theme", t.name).apply()
    }

    fun updateBottomTwoPage(two: Boolean) {
        bottomTwoPage = two
        prefs.edit().putBoolean("bottom_two_page", two).apply()
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, null) ?: "") }.getOrDefault(default)

    companion object {
        const val DEFAULT_FONT_PT = 10f
        const val MIN_FONT_PT = 6f
        const val MAX_FONT_PT = 24f
        val MARGIN_DP = listOf(6, 14, 24, 36)
        val MARGIN_LABELS = listOf("Narrow", "Normal", "Wide", "Extra wide")
        const val DEFAULT_HOLD_MS = 2000
        const val MIN_HOLD_MS = 500
        const val MAX_HOLD_MS = 3000
        val HOLD_CHOICES_MS = listOf(500, 1000, 1500, 2000, 2500, 3000)

        /**
         * Bookmark colours: mid-tone and fairly saturated, so each stands out on light Paper,
         * yellowish Sepia and black Night pages alike. Blue is the default.
         */
        val BOOKMARK_PRESETS = listOf(
            0xFFD93A3A.toInt(), // red
            0xFFE57A1F.toInt(), // orange
            0xFFD4A20F.toInt(), // gold
            0xFF3E9E4F.toInt(), // green
            0xFF14988F.toInt(), // teal
            0xFF3A78D6.toInt(), // blue
            0xFF8A55C8.toInt(), // purple
            0xFFD9508C.toInt(), // pink
        )
        val BOOKMARK_PRESET_NAMES = listOf("Red", "Orange", "Gold", "Green", "Teal", "Blue", "Purple", "Pink")

        /** Blue is the default bookmark colour. */
        val DEFAULT_BOOKMARK_COLOR = BOOKMARK_PRESETS[5]

        /** Custom colours from the hue slider keep the presets' brightness and strength. */
        fun customBookmarkColor(hue: Float): Int =
            android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 359.9f), 0.72f, 0.85f))
    }
}
