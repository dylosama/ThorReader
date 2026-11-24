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

/** Library view chips. */
enum class LibraryView(val label: String) { ALL("All"), AUTHORS("Authors"), SERIES("Series"), TYPE("Type"), GENRE("Genre") }

enum class SortOrder(val label: String) {
    RECENTLY_READ("Recently read"),
    RECENTLY_ADDED("Recently added"),
    TITLE("Title"),
    AUTHOR("Author"),
    PROGRESS("Progress"),
    DATE_COMPLETED("Date completed"),
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
    var libraryView by mutableStateOf(enumPref("lib_view", LibraryView.ALL))
        private set
    var sortLibrary by mutableStateOf(enumPref("sort_library", SortOrder.RECENTLY_READ))
        private set
    var sortCompleted by mutableStateOf(enumPref("sort_completed", SortOrder.DATE_COMPLETED))
        private set

    fun updateShelf(s: Shelf) {
        shelf = s
        prefs.edit().putString("lib_shelf", s.name).apply()
    }

    fun updateLibraryView(v: LibraryView) {
        libraryView = v
        prefs.edit().putString("lib_view", v.name).apply()
    }

    fun sortFor(s: Shelf) = if (s == Shelf.COMPLETED) sortCompleted else sortLibrary

    fun updateSort(s: Shelf, order: SortOrder) {
        if (s == Shelf.COMPLETED) sortCompleted = order else sortLibrary = order
        prefs.edit().putString(if (s == Shelf.COMPLETED) "sort_completed" else "sort_library", order.name).apply()
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
