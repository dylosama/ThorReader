package dev.thorreader.app.engine

import dev.thorreader.app.ReadingMode
import dev.thorreader.app.Screen
import dev.thorreader.app.Settings

/** Real pixel sizes of the two screens in their natural (landscape) orientation. */
data class ScreenMetrics(
    val topW: Int,
    val topH: Int,
    val bottomW: Int,
    val bottomH: Int,
    val density: Float,
) {
    companion object {
        val DEFAULT = ScreenMetrics(1920, 1080, 1240, 1080, 2.30625f)
    }
}

/** Size in pixels of one page area (as the reader sees it, i.e. after rotation). */
data class SlotSize(val w: Int, val h: Int)

/**
 * Everything that determines how a book is cut into screen pages.
 * [slots]: page areas of one "view" in READING order (1 or 2). In book modes slot 0 is the
 * screen that shows the first page of each view, which depends on mode and reading direction.
 * [wideSlot]: the whole screen, used for a double-page spread shown in one piece (modes 3/4).
 */
data class LayoutConfig(
    val mode: ReadingMode,
    val rtl: Boolean,
    val slots: List<SlotSize>,
    val wideSlot: SlotSize,
    val fontPt: Float,
    val marginPx: Int,
    /** Pixels per point: 1pt of font = 1/72 inch on the Thor's screen. */
    val pxPerPt: Float,
    /**
     * Height reserved at the top AND bottom of reflowable pages for the running header and page
     * number (0 = off), so text never runs under them.
     */
    val headerPx: Int = 0,
) {
    val perView: Int get() = slots.size

    companion object {
        fun build(mode: ReadingMode, rtl: Boolean, screens: ScreenMetrics, settings: Settings): LayoutConfig =
            build(mode, rtl, screens, settings.fontPt, settings.marginLevel, settings.bottomTwoPage, settings.showHeader)

        fun build(
            mode: ReadingMode,
            rtl: Boolean,
            screens: ScreenMetrics,
            fontPt: Float,
            marginLevel: Int,
            bottomTwoPage: Boolean,
            showHeader: Boolean = false,
        ): LayoutConfig {
            // In book modes the reader holds the device sideways, so each screen is used rotated.
            val topRotated = SlotSize(screens.topH, screens.topW)          // 1080 x 1920
            val bottomRotated = SlotSize(screens.bottomH, screens.bottomW) // 1080 x 1240
            val slots: List<SlotSize>
            val wide: SlotSize
            when (mode) {
                ReadingMode.BOOK, ReadingMode.BOOK_REVERSED -> {
                    val firstOnTop = (mode == ReadingMode.BOOK) != rtl
                    slots = if (firstOnTop) listOf(topRotated, bottomRotated) else listOf(bottomRotated, topRotated)
                    wide = slots[0]
                }
                ReadingMode.TOP -> {
                    val half = SlotSize(screens.topW / 2, screens.topH)
                    slots = listOf(half, half)
                    wide = SlotSize(screens.topW, screens.topH)
                }
                ReadingMode.BOTTOM -> {
                    wide = SlotSize(screens.bottomW, screens.bottomH)
                    slots = if (bottomTwoPage) {
                        val half = SlotSize(screens.bottomW / 2, screens.bottomH)
                        listOf(half, half)
                    } else {
                        listOf(wide)
                    }
                }
            }
            val pxPerPt = screens.density * 160f / 72f
            val marginPx = (Settings.MARGIN_DP[marginLevel] * screens.density).toInt()
            val headerPx = if (showHeader) headerBandPx(fontPt, pxPerPt, screens.density) else 0
            return LayoutConfig(mode, rtl, slots, wide, fontPt, marginPx, pxPerPt, headerPx)
        }
    }
}

/** Running header / page number text is this much smaller than the book's text. */
const val HEADER_TEXT_SCALE = 0.8f

/** Header text size in px for a book font of [fontPt]. */
fun headerTextPx(fontPt: Float, pxPerPt: Float) = fontPt * pxPerPt * HEADER_TEXT_SCALE

/**
 * Height reserved at the top and bottom of a reflowable page for the running header and page
 * number: 1.8x the header text size (grows with the font), at least 20 dp.
 */
fun headerBandPx(fontPt: Float, pxPerPt: Float, density: Float): Int =
    maxOf(headerTextPx(fontPt, pxPerPt) * 1.8f, 20 * density).toInt()

/** Which unit of a view (index in reading order) a screen shows in a book mode. */
fun bookUnitIndexFor(screen: Screen, mode: ReadingMode, rtl: Boolean): Int {
    val leftIdx = if (rtl) 1 else 0
    val screenIsLeft = (screen == Screen.TOP) == (mode == ReadingMode.BOOK)
    return if (screenIsLeft) leftIdx else 1 - leftIdx
}

enum class Part { WHOLE, LEFT, RIGHT }

/** One page area's content. */
sealed interface PageUnit {
    val key: String

    /** Chapter title / outline entry for the menu, if known. */
    val label: String? get() = null
}

data object BlankUnit : PageUnit {
    override val key = "blank"
}

/** A page (or half of a double-page spread) of a fixed-layout book. */
data class FixedUnit(val page: Int, val part: Part, override val label: String? = null) : PageUnit {
    override val key = "f$page$part"
}

/**
 * A page of a reflowable book: points [y0, y1) of MuPDF tall page ([chapter], [page]), laid out
 * for [slot]. Always whole lines. [fracStart]/[fracEnd] locate it within the chapter
 * independently of font/layout, for saving the position. [overflow]: content taller than the
 * slot (a single huge image/table), so the page scrolls.
 */
data class SliceUnit(
    val chapter: Int,
    val page: Int,
    val y0: Float,
    val y1: Float,
    val slot: SlotSize,
    val fracStart: Float,
    val fracEnd: Float,
    /** The chapter name for this page (also shown in the menu). */
    override val label: String?,
    val overflow: Boolean,
    /** Running header drawn on this page: only the left page of a view (right page in RTL). */
    val header: String? = null,
) : PageUnit {
    override val key = "s$chapter.$page:$y0:$y1:${slot.w}x${slot.h}:h${header.hashCode()}"
}

/** What is visible at once: 1 or 2 units in reading order. [wide] = one spread filling the screen. */
data class PageView(val units: List<PageUnit>, val wide: Boolean = false)

/** Layout-independent reading position. Fixed: chapter = page index. Reflowable: chapter + fraction. */
data class Anchor(val chapter: Int, val fraction: Float)

/**
 * The views for one layout. [layoutId] identifies the text layout (width/font/...) so rendered
 * pages can be reused across re-publishes. Reflowable books are published progressively:
 * [complete] = false while only chapters [firstChapter]..[lastChapter] of [chapterCount] are in.
 */
class Pagination(
    val config: LayoutConfig,
    val views: List<PageView>,
    val generation: Int,
    val layoutId: String = "fixed",
    val complete: Boolean = true,
    val chapterCount: Int = 0,
) {

    fun slotFor(view: PageView, unitIndex: Int): SlotSize =
        if (view.wide) config.wideSlot else config.slots[unitIndex.coerceAtMost(config.slots.lastIndex)]

    fun anchorOf(viewIndex: Int): Anchor? {
        val view = views.getOrNull(viewIndex) ?: return null
        return when (val u = view.units.firstOrNull { it !is BlankUnit }) {
            is FixedUnit -> Anchor(u.page, 0f)
            is SliceUnit -> Anchor(u.chapter, u.fracStart + 0.0001f)
            else -> null
        }
    }

    fun findView(anchor: Anchor): Int {
        views.forEachIndexed { i, v ->
            for (u in v.units) {
                when (u) {
                    is FixedUnit -> if (u.page >= anchor.chapter) return i
                    is SliceUnit -> if (u.chapter > anchor.chapter || (u.chapter == anchor.chapter && u.fracEnd > anchor.fraction)) return i
                    else -> Unit
                }
            }
        }
        return (views.size - 1).coerceAtLeast(0)
    }

    fun labelAt(viewIndex: Int): String? = views.getOrNull(viewIndex)?.units?.firstNotNullOfOrNull { it.label }

    /** Reflowable page numbers, counted over the whole book (only once it is fully laid out). */
    private val sliceNumbers: Map<String, Int>? by lazy {
        if (!complete) return@lazy null
        val map = HashMap<String, Int>()
        var n = 0
        for (v in views) for (u in v.units) if (u is SliceUnit) map[u.key] = ++n
        map
    }

    /** The printed page number of [unit], or null if not known (yet) / blank page. */
    fun pageNumber(unit: PageUnit): Int? = when (unit) {
        is FixedUnit -> unit.page + 1
        is SliceUnit -> sliceNumbers?.get(unit.key)
        else -> null
    }

    fun isLastView(i: Int): Boolean = complete && i == views.size - 1

    /** Page number shown for a view (its first page), or null if not known yet. */
    fun pageNumberOfView(i: Int): Int? = views.getOrNull(i)?.units?.firstNotNullOfOrNull { pageNumber(it) }

    /** Total pages: real pages for PDF/comics, this layout's pages for books (once complete). */
    val pageCount: Int? by lazy {
        val fixedMax = views.maxOfOrNull { v -> v.units.maxOfOrNull { (it as? FixedUnit)?.page ?: -1 } ?: -1 } ?: -1
        if (fixedMax >= 0) fixedMax + 1 else sliceNumbers?.size
    }

    /** The view showing page [n], or null if there is no such page (yet). */
    fun viewOfPage(n: Int): Int? = views.indexOfFirst { v -> v.units.any { pageNumber(it) == n } }.takeIf { it >= 0 }

    /** Chapters (TOC / running-header names) with the view each starts on, in order. */
    val chapters: List<Pair<String, Int>> by lazy {
        val out = ArrayList<Pair<String, Int>>()
        var last: String? = null
        views.forEachIndexed { i, v ->
            val label = v.units.firstNotNullOfOrNull { it.label }
            if (label != null && label != last) out += label to i
            if (label != null) last = label
        }
        out
    }

    /** Index into [chapters] of the chapter containing view [i], or -1. */
    fun chapterIndexAt(i: Int): Int = chapters.indexOfLast { it.second <= i }

    /** 0..1 through the whole book (by chapter for reflowable books, by page for fixed ones). */
    fun progressAt(viewIndex: Int): Float {
        val a = anchorOf(viewIndex) ?: return 0f
        return if (chapterCount > 0) ((a.chapter + a.fraction) / chapterCount).coerceIn(0f, 1f)
        else if (views.size <= 1) 1f else viewIndex.toFloat() / (views.size - 1)
    }
}

/** Builds views for a fixed-layout book (comics, PDF). */
fun paginateFixed(count: Int, isSpread: (Int) -> Boolean, label: (Int) -> String?, cfg: LayoutConfig): List<PageView> {
    val views = ArrayList<PageView>()
    if (cfg.mode.isBook) {
        // One unit per screen. Spreads are split in half, first half (in reading order) on the
        // first screen of a view, so a blank page is inserted if needed to align them.
        val units = ArrayList<PageUnit>()
        for (p in 0 until count) {
            if (isSpread(p)) {
                if (units.size % 2 == 1) units += BlankUnit
                units += FixedUnit(p, if (cfg.rtl) Part.RIGHT else Part.LEFT, label(p))
                units += FixedUnit(p, if (cfg.rtl) Part.LEFT else Part.RIGHT, label(p))
            } else {
                units += FixedUnit(p, Part.WHOLE, label(p))
            }
        }
        if (units.size % 2 == 1) units += BlankUnit
        units.chunked(2).forEach { views += PageView(it) }
    } else if (cfg.perView == 2) {
        // Two pages side by side; a spread is shown whole on its own.
        var p = 0
        while (p < count) {
            if (isSpread(p)) {
                views += PageView(listOf(FixedUnit(p, Part.WHOLE, label(p))), wide = true)
                p++
            } else if (p + 1 < count && !isSpread(p + 1)) {
                views += PageView(listOf(FixedUnit(p, Part.WHOLE, label(p)), FixedUnit(p + 1, Part.WHOLE, label(p + 1))))
                p += 2
            } else {
                views += PageView(listOf(FixedUnit(p, Part.WHOLE, label(p)), BlankUnit))
                p++
            }
        }
    } else {
        for (p in 0 until count) views += PageView(listOf(FixedUnit(p, Part.WHOLE, label(p))), wide = isSpread(p))
    }
    if (views.isEmpty()) views += PageView(List(cfg.perView) { BlankUnit })
    return views
}
