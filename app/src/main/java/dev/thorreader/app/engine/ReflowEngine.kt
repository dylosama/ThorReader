package dev.thorreader.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.Text
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import dev.thorreader.app.TAG
import dev.thorreader.app.io.SafFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.ceil

/**
 * Reflowable books (EPUB, MOBI, FB2, TXT).
 *
 * MuPDF lays each chapter out at the page WIDTH on very tall pages; we record the position of
 * every text line and image, then build screen pages from whole lines ([LinePaginator]), so
 * each page can have its own height (book modes: top 1920 px, bottom 1240 px) and no line is
 * ever cut. Each chapter starts on a new view, so chapters can be laid out one at a time
 * (current chapter first) and cached on disk per text layout (width, font, image cap, header).
 *
 * Running header and page number are drawn INTO the page with the book's own body font, a
 * little smaller and in grey (which the page theme turns into its muted text colour).
 */
class ReflowEngine(
    private val doc: Document,
    private val file: SafFile?,
    private val title: String,
    private val cacheDir: File,
    private val cacheName: String,
    /** EPUB only: spine + section headings for running headers. */
    private val epub: EpubSections?,
) : BookEngine {
    override val reflowable = true

    private class Seg(val page: Int, val rows: LinePaginator.Rows, val offset: Float)
    private class Chapter(val segs: List<Seg>, val height: Float) {
        val lineCount: Int get() = segs.sumOf { it.rows.size }
    }

    /** A table-of-contents entry; position resolved lazily once its chapter is laid out. */
    private class Toc(val title: String, val depth: Int, val uri: String, val chapter: Int, val hasChildren: Boolean) {
        var page = 0
        var y = 0f
        var resolved = !uri.contains('#')
    }

    private var layoutId: String? = null
    private var chapters: Array<Chapter?> = emptyArray()
    private var toc: List<Toc> = emptyList()
    private var chapterToSpine: IntArray = IntArray(0)
    private val displayLists = object : LinkedHashMap<Long, DisplayList>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DisplayList>): Boolean =
            (size > 3).also { if (it) eldest.value.destroy() }
    }
    private val loggedOverflow = HashSet<String>()
    private val loggedHeaders = HashMap<Int, String>()
    private val erase = Paint().apply { color = Color.WHITE }

    /** The book's main text font (found from its pages), used for header and page number. */
    private var bodyFont: Font? = null
    private var bodyFontTries = 0
    private val fallbackFont by lazy { Font("Times-Roman") }

    val chapterCount: Int get() = chapters.size

    /**
     * Column width in points of the (single) text layout: the widest column every slot can
     * show. A slot with a smaller text size draws the layout scaled down ([LayoutConfig.slotScale]),
     * so it could fit a wider column; the larger-text slot is the one that limits it.
     */
    private fun widthPt(cfg: LayoutConfig) = cfg.textWidthPt()

    /** Text area height of slot [i] in layout points: page minus margins minus header bands. */
    private fun heightPt(cfg: LayoutConfig, i: Int) = cfg.textHeightPt(i)
    private fun imageCapPt(cfg: LayoutConfig) = cfg.slots.indices.minOf { heightPt(cfg, it) } * 0.98f

    /**
     * Identifies the text layout (keyed by both screens' text sizes). Slicing into pages is
     * cheap and done per mode.
     */
    fun layoutIdFor(cfg: LayoutConfig): String =
        "v$LAYOUT_VERSION-w%.2f-f%.2f-i%.0f-s%s".format(
            widthPt(cfg), cfg.fontPt, imageCapPt(cfg), cfg.slotFontPt.sorted().joinToString("/") { "%.1f".format(it) },
        )

    /** Switch MuPDF to this layout (cheap: chapters are laid out lazily) and load any disk cache. */
    fun useLayout(cfg: LayoutConfig) {
        val id = layoutIdFor(cfg)
        if (id == layoutId) return
        val t0 = System.currentTimeMillis()
        displayLists.values.forEach { it.destroy() }
        displayLists.clear()
        MuPdf.setCss(maxImageHeightPt = imageCapPt(cfg))
        doc.layout(widthPt(cfg), MuPdf.TALL_PAGE_PT, cfg.fontPt)
        val n = doc.countChapters()
        chapters = arrayOfNulls(n)
        toc = readToc()
        if (chapterToSpine.size != n) chapterToSpine = mapSpine(n)
        layoutId = id
        chapterViews.clear()
        val loaded = loadCache(id)
        tocEntries = null
        Log.i(TAG, "Layout $id for '$title': $n chapters, ${toc.size} TOC entries, ${if (loaded) "from disk cache" else "not cached"} (${System.currentTimeMillis() - t0} ms)")
    }

    fun isReady(ch: Int) = chapters.getOrNull(ch) != null
    val allReady: Boolean get() = chapters.all { it != null }

    /** Lay out one chapter and record its line positions. */
    fun layoutChapter(ch: Int) {
        if (isReady(ch)) return
        val t0 = System.currentTimeMillis()
        val segs = ArrayList<Seg>()
        var offset = 0f
        val pages = doc.countPages(ch)
        for (p in 0 until pages) {
            val items = ArrayList<FloatArray>()
            val page = doc.loadPage(ch, p)
            try {
                val st = page.toStructuredText("preserve-images")
                try {
                    st.walk(ItemCollector(items))
                } finally {
                    st.destroy()
                }
            } finally {
                page.destroy()
            }
            val rows = LinePaginator.rows(items)
            segs += Seg(p, rows, offset)
            offset += if (rows.size > 0) rows.bottoms.max() else 0f
        }
        chapters[ch] = Chapter(segs, offset.coerceAtLeast(1f))
        resolveToc(ch)
        val ms = System.currentTimeMillis() - t0
        if (ms > 150) Log.d(TAG, "Laid out chapter $ch of '$title' in $ms ms")
    }

    /** Text lines and images: the things a page break must not cut. */
    private class ItemCollector(val out: MutableList<FloatArray>) : StructuredTextWalker {
        private fun add(r: Rect?) {
            if (r != null && !r.isInfinite && r.y1 > r.y0) out += floatArrayOf(r.y0, r.y1)
        }

        override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) = add(bbox)
        override fun beginTextBlock(bbox: Rect?, p1: Int) = Unit
        override fun endTextBlock() = Unit
        override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) = add(bbox)
        override fun endLine() = Unit
        override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, quad: Quad?, argb: Int, flags: Int, p7: Int) = Unit
        override fun beginStruct(standard: String?, raw: String?, idx: Int) = Unit
        override fun endStruct() = Unit
        override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) = Unit
    }

    // ---- table of contents & running headers ---------------------------------------------

    /** All TOC entries in order, with depth, located to their spine item (cheap: no fragments). */
    private fun readToc(): List<Toc> {
        val out = ArrayList<Toc>()
        fun walk(items: Array<Outline>?, depth: Int) {
            items?.forEach { o ->
                val uri = o.uri
                val t = o.title?.replace(Regex("\\s+"), " ")?.trim()
                if (uri != null && !t.isNullOrEmpty()) {
                    val ch = runCatching { doc.resolveLink(uri.substringBefore('#').ifEmpty { uri }).chapter }.getOrDefault(-1)
                    out += Toc(t, depth, uri, ch, !o.down.isNullOrEmpty())
                }
                walk(o.down, depth + 1)
            }
        }
        runCatching { walk(doc.loadOutline(), 0) }
        return out
    }

    /** Find where TOC entries with a #fragment point inside chapter [ch] (needs its layout). */
    private fun resolveToc(ch: Int) {
        for (e in toc) {
            if (e.resolved || e.chapter != ch) continue
            runCatching {
                val d = doc.resolveLinkDestination(e.uri)
                if (d.chapter == ch) {
                    e.page = d.page.coerceAtLeast(0)
                    e.y = if (d.hasY()) d.y else 0f
                }
            }
            e.resolved = true
            tocEntries = null // positions changed: rebuild the header list once
        }
    }

    /** MuPDF chapter index -> EPUB spine index (identical unless MuPDF skipped items). */
    private fun mapSpine(n: Int): IntArray {
        val spine = epub?.spine ?: return IntArray(n) { it }
        if (spine.size == n) return IntArray(n) { it }
        val map = IntArray(n) { -1 }
        spine.forEachIndexed { i, path ->
            val ch = runCatching { doc.resolveLink(path).chapter }.getOrDefault(-1)
            if (ch in 0 until n && map[ch] < 0) map[ch] = i
        }
        Log.i(TAG, "Spine has ${spine.size} items but MuPDF has $n chapters; mapped by path")
        return map
    }

    private fun headingOf(ch: Int): String? {
        val idx = chapterToSpine.getOrNull(ch) ?: return null
        return if (idx < 0) null else epub?.heading(idx)
    }

    /** A real text section (not a cover / title page)? null = not laid out yet. */
    private fun isRealSection(ch: Int): Boolean? = chapters.getOrNull(ch)?.let { it.lineCount >= REAL_SECTION_LINES }

    /** TOC entries for [RunningHeader], built once and rebuilt only when a position resolves. */
    private var tocEntries: List<RunningHeader.Entry>? = null

    private fun headerFor(ch: Int, page: Int, y: Float): String? {
        val entries = tocEntries ?: toc.map { RunningHeader.Entry(it.title, it.depth, it.chapter, it.page, it.y, it.hasChildren) }
            .also { tocEntries = it }
        val r = RunningHeader.choose(entries, title, ch, page, y, ::headingOf, ::isRealSection)
        // Log the source once per chapter (and again if it changes).
        val line = "${r.source}: ${r.text ?: "(none)"} - ${r.detail}"
        if (r.detail != "waiting for layout" && loggedHeaders[ch] != line) {
            loggedHeaders[ch] = line
            Log.i(TAG, "Header for '$title' section ${ch + 1}: \"${r.text ?: ""}\" from ${r.source} (${r.detail})")
        }
        return r.text
    }

    // ---- pages -------------------------------------------------------------------------------

    /**
     * Screen pages for chapters [lo]..[hi] (those laid out). Each chapter starts a new view.
     * The header goes on the FIRST page of each view in reading order: the left page, or the
     * right page in right-to-left books; on every page when a view has one page.
     */
    fun buildViews(cfg: LayoutConfig, lo: Int, hi: Int): List<PageView> {
        val n = cfg.perView
        val views = ArrayList<PageView>()
        // Chapters 0..prefix-1 are all laid out: their pages (and headers) are final and cached.
        val prefix = chapters.indexOfFirst { it == null }.let { if (it < 0) chapters.size else it }
        for (ch in lo..hi) {
            val chapter = chapters.getOrNull(ch) ?: continue
            views += chapterViews.get(layoutId to cfg, ch, final = ch < prefix) { chapterPages(cfg, ch, chapter) }
        }
        if (views.isEmpty()) views += PageView(List(n) { BlankUnit })
        return views
    }

    private val chapterViews = ChapterViewCache()

    /** The screen pages of one chapter (it starts a new view). */
    private fun chapterPages(cfg: LayoutConfig, ch: Int, chapter: Chapter): List<PageView> {
        val n = cfg.perView
        val views = ArrayList<PageView>()
        run {
            resolveToc(ch)
            val units = ArrayList<PageUnit>()
            for (seg in chapter.segs) {
                val spans = LinePaginator.pages(seg.rows, units.size) { k -> heightPt(cfg, k % n) }
                for (span in spans) {
                    val slotIdx = units.size % n
                    val slot = cfg.slots[slotIdx]
                    val overflow = span.overflow > 0.5f
                    if (overflow) logOverflow(ch, seg.page, span, slot, cfg)
                    val label = headerFor(ch, seg.page, span.y0)
                    val showsHeader = cfg.headerPx > 0 && units.size % n == 0
                    units += SliceUnit(
                        chapter = ch, page = seg.page, y0 = span.y0, y1 = span.y1, slot = slot,
                        fracStart = (seg.offset + span.y0) / chapter.height,
                        fracEnd = (seg.offset + span.y1) / chapter.height,
                        label = label,
                        overflow = overflow,
                        header = if (showsHeader) label else null,
                        scale = cfg.slotScale(slotIdx),
                    )
                }
            }
            while (units.size % n != 0) units += BlankUnit
            units.chunked(n).forEach { views += PageView(it) }
        }
        return views
    }

    private fun logOverflow(ch: Int, page: Int, span: LinePaginator.Span, slot: SlotSize, cfg: LayoutConfig) {
        val key = "$layoutId:$ch.$page:${span.y0}:$slot"
        if (!loggedOverflow.add(key)) return
        val over = span.overflow * cfg.pxPerPt
        Log.w(TAG, "OVERFLOW '$title' chapter ${ch + 1} at %.0fpt: content %.0f px > %d px page area (+%.0f px) -> page scrolls"
            .format(span.y0, (span.y1 - span.y0) * cfg.pxPerPt, slot.h - 2 * cfg.marginPx - 2 * cfg.headerPx, over))
    }

    override fun render(unit: PageUnit, slot: SlotSize, cfg: LayoutConfig, zoom: Float, pageNumber: Int?): Rendered? {
        if (unit !is SliceUnit) return null
        if (layoutIdFor(cfg) != layoutId) return null // stale request from an older layout
        val dlKey = (unit.chapter.toLong() shl 32) or unit.page.toLong()
        val dl = displayLists.getOrPut(dlKey) {
            val page = doc.loadPage(unit.chapter, unit.page)
            try {
                page.toDisplayList()
            } finally {
                page.destroy()
            }
        }
        val w = unit.slot.w
        val m = (cfg.marginPx + cfg.headerPx).toFloat() // top/bottom: margin + header band
        val s = cfg.pxPerPt * unit.scale
        // The column is centred: a smaller text size on this screen leaves wider side margins.
        val mx = maxOf(cfg.marginPx.toFloat(), (w - widthPt(cfg) * s) / 2f)
        val contentPx = (unit.y1 - unit.y0) * s
        val h = if (unit.overflow) ceil(contentPx + 2 * m).toInt().coerceAtMost(MAX_BITMAP) else unit.slot.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val ctm = Matrix(s, 0f, 0f, s, mx, m - unit.y0 * s)
        val dev = AndroidDrawDevice(bmp, 0, 0, false)
        try {
            dl.run(dev, ctm, null)
            dev.close()
        } finally {
            dev.destroy()
        }
        // Blank out lines belonging to the previous / next page (they are whole lines, never halves).
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, w.toFloat(), m, erase)
        canvas.drawRect(0f, m + contentPx, w.toFloat(), h.toFloat(), erase)

        if (cfg.headerPx > 0) {
            ensureBodyFont(unit)
            val size = headerTextPx(cfg.fontPt * unit.scale, cfg.pxPerPt)
            val band = cfg.headerPx.toFloat()
            val top = cfg.marginPx.toFloat()
            unit.header?.let { text ->
                // Top-left of the left page; top-right of the right page in RTL books.
                drawPlain(bmp, text, size, baseline = top + band * 0.55f, x = if (cfg.rtl) w - mx else mx, alignRight = cfg.rtl, maxWidth = w - 2 * mx)
            }
            pageNumber?.let { num ->
                // Bottom-right (bottom-left in RTL books).
                drawPlain(bmp, num.toString(), size, baseline = h - top - band * 0.28f, x = if (cfg.rtl) mx else w - mx, alignRight = !cfg.rtl, maxWidth = w - 2 * mx)
            }
        }
        return Rendered(bmp, unit.overflow)
    }

    /** Find the font most of the book's text uses (once, from the first page with text). */
    private fun ensureBodyFont(unit: SliceUnit) {
        if (bodyFont != null || bodyFontTries >= 5) return
        bodyFontTries++
        val counts = HashMap<String, Int>()
        val fonts = HashMap<String, Font>()
        runCatching {
            val page = doc.loadPage(unit.chapter, unit.page)
            try {
                val st = page.toStructuredText()
                try {
                    st.walk(object : StructuredTextWalker {
                        override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, quad: Quad?, argb: Int, flags: Int, p7: Int) {
                            val f = font ?: return
                            val name = f.name ?: return
                            counts[name] = (counts[name] ?: 0) + 1
                            fonts.putIfAbsent(name, f)
                        }
                        override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) = Unit
                        override fun beginTextBlock(bbox: Rect?, p1: Int) = Unit
                        override fun endTextBlock() = Unit
                        override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) = Unit
                        override fun endLine() = Unit
                        override fun beginStruct(standard: String?, raw: String?, idx: Int) = Unit
                        override fun endStruct() = Unit
                        override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) = Unit
                    })
                } finally {
                    st.destroy()
                }
            } finally {
                page.destroy()
            }
        }
        val best = counts.maxByOrNull { it.value } ?: return
        if (best.value < 100) return // too little text on this page to tell; try another page
        bodyFont = fonts[best.key]
        Log.i(TAG, "Body font of '$title': ${best.key} (header and page numbers use it)")
    }

    /** Book-like plain text: the book's font (or a serif fallback if it lacks the glyphs), grey. */
    private fun drawPlain(bmp: Bitmap, raw: String, size: Float, baseline: Float, x: Float, alignRight: Boolean, maxWidth: Float) {
        val font = bodyFont?.takeIf { f -> raw.codePoints().allMatch { Character.isWhitespace(it) || f.encodeCharacter(it) != 0 } }
            ?: fallbackFont
        val text = fitWidth(font, raw, size, maxWidth)
        val width = widthOf(font, text, size)
        val x0 = if (alignRight) x - width else x
        val t = Text()
        val dev = AndroidDrawDevice(bmp, 0, 0, false)
        try {
            // y-down page space: flip the glyphs (negative d), baseline at [baseline].
            t.showString(font, Matrix(size, 0f, 0f, -size, x0, baseline), text, false)
            dev.fillText(t, Matrix(), ColorSpace.DeviceRGB, floatArrayOf(MUTED, MUTED, MUTED), 1f, 0)
            dev.close()
        } finally {
            dev.destroy()
            t.destroy()
        }
    }

    private fun widthOf(font: Font, text: String, size: Float): Float {
        var w = 0f
        text.codePoints().forEach { cp -> w += font.advanceGlyph(font.encodeCharacter(cp), false) }
        return w * size
    }

    /** Truncate with an ellipsis to fit [maxWidth]. */
    private fun fitWidth(font: Font, text: String, size: Float, maxWidth: Float): String {
        if (widthOf(font, text, size) <= maxWidth) return text
        val ell = if (font.encodeCharacter('…'.code) != 0) "…" else "..."
        var t = text
        while (t.isNotEmpty() && widthOf(font, t.trimEnd() + ell, size) > maxWidth) t = t.dropLast(1)
        return t.trimEnd() + ell
    }

    // ---- disk cache ------------------------------------------------------------------------

    private fun cacheFile(id: String) = File(cacheDir, "$cacheName-${id.hashCode().toUInt()}.bin")

    private fun loadCache(id: String): Boolean = runCatching {
        val f = cacheFile(id)
        if (!f.exists()) return false
        DataInputStream(f.inputStream().buffered()).use { s ->
            if (s.readInt() != CACHE_MAGIC || s.readUTF() != id) return false
            val n = s.readInt()
            if (n != chapters.size) return false
            for (ch in 0 until n) {
                if (!s.readBoolean()) continue
                val height = s.readFloat()
                val segs = List(s.readInt()) {
                    val page = s.readInt()
                    val offset = s.readFloat()
                    val rows = s.readInt()
                    val tops = FloatArray(rows) { s.readFloat() }
                    val bottoms = FloatArray(rows) { s.readFloat() }
                    Seg(page, LinePaginator.Rows(tops, bottoms), offset)
                }
                chapters[ch] = Chapter(segs, height)
            }
            // Where each TOC entry points (so headers need no extra layout work).
            val t = s.readInt()
            if (t == toc.size) {
                for (e in toc) {
                    e.page = s.readInt()
                    e.y = s.readFloat()
                    e.resolved = s.readBoolean()
                }
            }
        }
        f.setLastModified(System.currentTimeMillis())
        true
    }.getOrElse {
        Log.w(TAG, "Layout cache unreadable; rebuilding", it)
        chapters.fill(null)
        false
    }

    /** Save once every chapter is laid out. */
    fun saveCache() {
        val id = layoutId ?: return
        if (!allReady) return
        val f = cacheFile(id)
        if (f.exists()) return
        for (ch in chapters.indices) resolveToc(ch)
        runCatching {
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(CACHE_MAGIC)
                o.writeUTF(id)
                o.writeInt(chapters.size)
                for (c in chapters) {
                    o.writeBoolean(c != null)
                    if (c == null) continue
                    o.writeFloat(c.height)
                    o.writeInt(c.segs.size)
                    for (seg in c.segs) {
                        o.writeInt(seg.page)
                        o.writeFloat(seg.offset)
                        o.writeInt(seg.rows.size)
                        seg.rows.tops.forEach(o::writeFloat)
                        seg.rows.bottoms.forEach(o::writeFloat)
                    }
                }
                o.writeInt(toc.size)
                for (e in toc) {
                    o.writeInt(e.page)
                    o.writeFloat(e.y)
                    o.writeBoolean(e.resolved)
                }
            }
            tmp.renameTo(f)
            Log.i(TAG, "Saved layout cache ${f.name} (${f.length() / 1024} KB)")
            // Keep the cache folder small.
            cacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(150)?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "Could not save layout cache", it) }
    }

    override fun close() {
        displayLists.values.forEach { it.destroy() }
        displayLists.clear()
        runCatching { epub?.close() }
        doc.destroy()
        file?.close()
    }

    private companion object {
        /** Bump when the layout rules change so old caches are ignored. */
        const val LAYOUT_VERSION = 4
        const val CACHE_MAGIC = 0x54524C32 // "TRL2"
        const val MAX_BITMAP = 8192

        /** Sections with fewer lines are covers / title pages: no "Chapter N". */
        const val REAL_SECTION_LINES = 12

        /** Grey for header/page number before theming (between ink and paper = muted). */
        const val MUTED = 0.45f
    }
}
