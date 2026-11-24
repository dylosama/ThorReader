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

    fun updateFont(pt: Float) {
        fontPt = pt.coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        prefs.edit().putFloat("font_pt", fontPt).apply()
    }

    fun updateMargin(level: Int) {
        marginLevel = level.coerceIn(0, MARGIN_DP.lastIndex)
        prefs.edit().putInt("margin_level", marginLevel).apply()
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
    }
}
