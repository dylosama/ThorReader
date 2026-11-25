package dev.thorreader.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect as ARect
import android.net.Uri
import android.util.Log
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import dev.thorreader.app.TAG
import dev.thorreader.app.data.Book
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.io.ComicArchives
import dev.thorreader.app.io.DirImageArchive
import dev.thorreader.app.io.ImageArchive
import dev.thorreader.app.io.ImageHeader
import dev.thorreader.app.io.SafFile
import dev.thorreader.app.io.ZipImageArchive
import dev.thorreader.app.io.TarImageArchive
import dev.thorreader.app.io.SingleImageArchive
import dev.thorreader.app.io.SafFolderImageArchive
import dev.thorreader.app.convert.BookSource
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rendered page. [overflow]: taller than its page area; the reader lets it scroll. */
class Rendered(val bitmap: Bitmap, val overflow: Boolean)

/**
 * An open book. All methods must be called on the session's single engine thread
 * (MuPDF documents and the archive readers are not thread-safe).
 */
interface BookEngine : Closeable {
    val reflowable: Boolean

    /** Render one unit for a page area of [slot] pixels. [zoom] > 1 renders sharper for zooming. */
    /** [pageNumber]: printed on reflowable pages (fixed pages show it as an overlay instead). */
    fun render(unit: PageUnit, slot: SlotSize, cfg: LayoutConfig, zoom: Float, pageNumber: Int?): Rendered?
}

object EngineFactory {
    fun open(context: Context, book: Book, progress: (String) -> Unit): BookEngine {
        val format = BookFormat.valueOf(book.format)
        // Caches are tied to this exact file (id + size) so a replaced file is re-measured.
        val cacheName = "b${book.id}-${book.sizeBytes}"
        val dims = File(File(context.filesDir, "pagesizes").apply { mkdirs() }, "$cacheName.bin")
        val layouts = File(context.filesDir, "layouts").apply { mkdirs() }
        // A folder of pictures has no single file to open.
        if (format == BookFormat.IMAGES) {
            return ImageArchiveEngine(SafFolderImageArchive(context, Uri.parse(book.uri)), null, book.title, dims)
        }
        val file = SafFile.open(context, Uri.parse(book.uri))
        try {
            return when (format) {
                BookFormat.PDF, BookFormat.XPS -> MuPdfFixedEngine(MuPdf.open(file, book.fileName), file, book.title, dims)
                BookFormat.EPUB -> {
                    // A book with conversion damage ("fief-?complete") is read from a repaired copy.
                    val fixed = dev.thorreader.app.convert.EpubRepair.repairedCopy(context, book.uri, book.sizeBytes, file)
                    if (fixed != null) {
                        file.close()
                        val f = SafFile.open(fixed)
                        try {
                            // Its own layout cache: the fixed text can break lines differently.
                            ReflowEngine(MuPdf.open(f, "book.epub"), f, book.title, layouts, "$cacheName-fixed1", runCatching { EpubSections(f) }.getOrNull())
                        } catch (t: Throwable) {
                            f.close()
                            throw t
                        }
                    } else {
                        ReflowEngine(MuPdf.open(file, book.fileName), file, book.title, layouts, cacheName, runCatching { EpubSections(file) }.getOrNull())
                    }
                }
                BookFormat.FB2 ->
                    ReflowEngine(
                        MuPdf.open(file, book.fileName), file, book.title, layouts, cacheName,
                        epub = if (format == BookFormat.EPUB) runCatching { EpubSections(file) }.getOrNull() else null,
                    )
                BookFormat.TXT -> ReflowEngine(MuPdf.openText(file), null, book.title, layouts, cacheName, null).also { file.close() }
                BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3, BookFormat.HTML, BookFormat.MARKDOWN,
                BookFormat.DOCX, BookFormat.ODT, BookFormat.RTF -> {
                    if (format == BookFormat.AZW3 || format == BookFormat.DOCX || format == BookFormat.ODT || format == BookFormat.RTF) {
                        progress("Preparing book…")
                    }
                    val o = BookSource.open(context, Uri.parse(book.uri), book.fileName, book.sizeBytes, format, file)
                    Log.i(TAG, "Reading '${book.title}' (${format.label}) via ${o.how}")
                    if (o.epub != null) {
                        // Converted: everything now comes from our EPUB; the original isn't needed.
                        file.close()
                        ReflowEngine(o.doc, o.epub, book.title, layouts, cacheName, runCatching { EpubSections(o.epub) }.getOrNull())
                    } else {
                        // Read natively. HTML/Markdown were read into memory; MOBI streams from the file.
                        ReflowEngine(o.doc, file, book.title, layouts, cacheName, null)
                    }
                }
                BookFormat.CBZ -> ImageArchiveEngine(ZipImageArchive(file), file, book.title, dims)
                BookFormat.CBT -> ImageArchiveEngine(TarImageArchive(file), file, book.title, dims)
                BookFormat.IMAGE -> ImageArchiveEngine(SingleImageArchive(file, book.fileName), file, book.title, dims)
                BookFormat.IMAGES -> throw IllegalStateException()
                BookFormat.CBR, BookFormat.CB7 -> {
                    progress("Unpacking comic…")
                    val dir = ComicArchives.unpack(context, "book${book.id}", file, format) { f ->
                        progress("Unpacking comic… ${(f * 100).toInt()}%")
                    }
                    file.close()
                    ImageArchiveEngine(DirImageArchive(dir), null, book.title, dims)
                }
            }
        } catch (t: Throwable) {
            file.close()
            throw t
        }
    }
}

// ------------------------------------------------------------------------------------------
// Fixed layout (PDF, XPS, comics)
// ------------------------------------------------------------------------------------------


/** A page much taller than its page area (e.g. a long strip) is shown full-width and scrolls. */
private const val TALL_FACTOR = 1.6f
private const val MAX_BITMAP_SIDE = 8192

abstract class FixedEngine(private val title: String, private val sizeCache: File) : BookEngine {
    override val reflowable = false
    private var sizes: FloatArray? = null // w0,h0,w1,h1,...
    private val loggedOverflow = HashSet<String>()

    protected abstract val pageCount: Int
    protected abstract fun measure(page: Int): Pair<Float, Float>
    protected open fun labelFor(page: Int): String? = null

    /** Page sizes, measured once (header-only for images) and cached on disk. */
    private fun ensureSizes(progress: (Float) -> Unit): FloatArray {
        sizes?.let { return it }
        val n = pageCount
        val cached = runCatching {
            DataInputStream(sizeCache.inputStream().buffered()).use { s ->
                if (s.readInt() != n) null else FloatArray(n * 2) { s.readFloat() }
            }
        }.getOrNull()
        if (cached != null) {
            sizes = cached
            return cached
        }
        val t0 = System.currentTimeMillis()
        val s = FloatArray(n * 2)
        for (i in 0 until n) {
            val (w, h) = runCatching { measure(i) }.getOrDefault(1f to 1.4f)
            s[2 * i] = w
            s[2 * i + 1] = h
            if (i % 10 == 0) progress(i.toFloat() / n)
        }
        runCatching {
            DataOutputStream(sizeCache.outputStream().buffered()).use { o ->
                o.writeInt(n)
                s.forEach(o::writeFloat)
            }
        }
        Log.i(TAG, "Measured $n page sizes of '$title' in ${System.currentTimeMillis() - t0} ms (cached)")
        sizes = s
        return s
    }

    fun paginate(cfg: LayoutConfig, progress: (Float) -> Unit): List<PageView> {
        val s = ensureSizes(progress)
        // Spreads: clearly double pages only (see spreadDecision), or the book's own choice.
        val ratios = List(pageCount) { p -> s[2 * p] / s[2 * p + 1].coerceAtLeast(1f) }
        val typical = typicalRatio(ratios)
        val spread = BooleanArray(pageCount) { p -> spreadDecision(ratios[p], typical, cfg.splitWide) }
        logSpreads(ratios, typical, spread, cfg.splitWide)
        val views = paginateFixed(pageCount, { p -> spread[p] }, ::labelFor, cfg)
        // Log pages that won't fit their page area (they will scroll).
        views.forEachIndexed { vi, v ->
            v.units.forEachIndexed { ui, u ->
                if (u is FixedUnit) {
                    val slot = if (v.wide) cfg.wideSlot else cfg.slots[ui.coerceAtMost(cfg.slots.lastIndex)]
                    val rw = if (u.part == Part.WHOLE) s[2 * u.page] else s[2 * u.page] / 2
                    val rh = s[2 * u.page + 1]
                    if (isTall(rw, rh, slot)) {
                        val shownH = rh * slot.w / rw
                        val key = "${u.page}${u.part}${slot}"
                        if (loggedOverflow.add(key)) {
                            Log.w(TAG, "OVERFLOW '$title' page ${u.page + 1} (view ${vi + 1}): %.0f px tall at full width in a %d px page area (+%.0f px, %.0f%%) -> scrolls"
                                .format(shownH, slot.h, shownH - slot.h, 100 * (shownH - slot.h) / slot.h))
                        }
                    }
                }
            }
        }
        return views
    }

    private var loggedSpreadsFor: SplitWide? = null

    /** Every page's width/height ratio and whether it is split as a spread (once per setting). */
    private fun logSpreads(ratios: List<Float>, typical: Float, spread: BooleanArray, mode: SplitWide) {
        if (loggedSpreadsFor == mode) return
        loggedSpreadsFor = mode
        Log.d(TAG, "Spreads in '$title' (split wide pages: ${mode.label}, typical page ratio %.2f): ${spread.count { it }} of ${ratios.size} split".format(typical))
        ratios.indices.chunked(12).forEach { chunk ->
            Log.d(TAG, "  " + chunk.joinToString("  ") { p -> "p${p + 1} %.2f%s".format(ratios[p], if (spread[p]) " SPREAD" else "") })
        }
    }

    protected fun isTall(rw: Float, rh: Float, slot: SlotSize) = rh / rw > (slot.h.toFloat() / slot.w) * TALL_FACTOR

    /** Scale for a region of [rw] x [rh]: fit the area, or fill its width if the page is very tall. */
    protected fun scaleFor(rw: Float, rh: Float, slot: SlotSize, zoom: Float): Float {
        var s = if (isTall(rw, rh, slot)) slot.w / rw else min(slot.w / rw, slot.h / rh)
        s *= zoom
        val maxSide = max(rw, rh) * s
        if (maxSide > MAX_BITMAP_SIDE) s *= MAX_BITMAP_SIDE / maxSide
        return s
    }
}

/** PDF / XPS via MuPDF. */
class MuPdfFixedEngine(
    private val doc: Document,
    private val file: SafFile,
    title: String,
    sizeCache: File,
) : FixedEngine(title, sizeCache) {
    override val pageCount: Int = doc.countPages()
    private val pages = object : LinkedHashMap<Int, Page>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>): Boolean =
            (size > 4).also { if (it) eldest.value.destroy() }
    }
    private val labels: Map<Int, String> = buildLabels()

    private fun page(i: Int): Page = pages.getOrPut(i) { doc.loadPage(i) }

    override fun measure(page: Int): Pair<Float, Float> {
        val b = page(page).bounds
        return (b.x1 - b.x0) to (b.y1 - b.y0)
    }

    override fun labelFor(page: Int): String? = labels.entries.lastOrNull { it.key <= page }?.value

    private fun buildLabels(): Map<Int, String> {
        val out = sortedMapOf<Int, String>()
        fun walk(items: Array<Outline>?) {
            items?.forEach { o ->
                runCatching { doc.pageNumberFromLocation(doc.resolveLink(o)) }.getOrNull()
                    ?.takeIf { it >= 0 && o.title != null }
                    ?.let { out.putIfAbsent(it, o.title.trim()) }
                walk(o.down)
            }
        }
        runCatching { walk(doc.loadOutline()) }
        return out
    }

    override fun render(unit: PageUnit, slot: SlotSize, cfg: LayoutConfig, zoom: Float, pageNumber: Int?): Rendered? {
        if (unit !is FixedUnit) return null
        val page = page(unit.page)
        val b = page.bounds
        val pw = b.x1 - b.x0
        val ph = b.y1 - b.y0
        val regionW = if (unit.part == Part.WHOLE) pw else pw / 2
        val regionX0 = if (unit.part == Part.RIGHT) pw / 2 else 0f
        val s = scaleFor(regionW, ph, slot, zoom)
        val bw = ceil(regionW * s).toInt().coerceAtLeast(1)
        val bh = ceil(ph * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val ctm = Matrix(s, 0f, 0f, s, -b.x0 * s, -b.y0 * s)
        val dev = AndroidDrawDevice(bmp, (regionX0 * s).roundToInt(), 0, false)
        try {
            page.run(dev, ctm, null)
            dev.close()
        } finally {
            dev.destroy()
        }
        return Rendered(bmp, isTall(regionW, ph, slot))
    }

    override fun close() {
        pages.values.forEach { it.destroy() }
        pages.clear()
        doc.destroy()
        file.close()
    }
}

/** Comics (CBZ / unpacked CBR / CB7): pages are images. */
class ImageArchiveEngine(
    private val archive: ImageArchive,
    private val file: SafFile?,
    title: String,
    sizeCache: File,
) : FixedEngine(title, sizeCache) {
    override val pageCount: Int = archive.pages.size

    /** Width/height from the image file header only - no decoding. */
    override fun measure(page: Int): Pair<Float, Float> {
        val name = archive.pages[page]
        archive.open(name).use { s -> ImageHeader.size(s) }?.let { (w, h) -> return w.toFloat() to h.toFloat() }
        // Unusual format: let Android read the bounds (still no pixel decoding).
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        archive.open(name).use { BitmapFactory.decodeStream(it, null, o) }
        return o.outWidth.toFloat().coerceAtLeast(1f) to o.outHeight.toFloat().coerceAtLeast(1f)
    }

    override fun render(unit: PageUnit, slot: SlotSize, cfg: LayoutConfig, zoom: Float, pageNumber: Int?): Rendered? {
        if (unit !is FixedUnit) return null
        val bytes = archive.read(archive.pages[unit.page])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val region = when (unit.part) {
            Part.WHOLE -> ARect(0, 0, w, h)
            Part.LEFT -> ARect(0, 0, w / 2, h)
            Part.RIGHT -> ARect(w / 2, 0, w, h)
        }
        val rw = region.width().toFloat()
        val rh = region.height().toFloat()
        val tall = isTall(rw, rh, slot)
        // Never upscale beyond the source; downsample with inSampleSize while staying >= target.
        val s = scaleFor(rw, rh, slot, zoom).coerceAtMost(1f)
        var sample = 1
        while (s * sample * 2 <= 1f) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = if (unit.part == Part.WHOLE) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } else {
            runCatching {
                val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                try {
                    dec.decodeRegion(region, opts)
                } finally {
                    dec.recycle()
                }
            }.getOrElse {
                // Formats without region decoding (e.g. GIF): decode whole, then crop.
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                val l = region.left / sample
                Bitmap.createBitmap(full, l, 0, (region.width() / sample).coerceAtMost(full.width - l), full.height)
            }
        } ?: return null
        // inSampleSize only halves, so the picture can still be up to 2x the size it is shown
        // at (a manga page ~12 MB instead of ~6). Scale it to exactly that size: the render
        // cache then holds the current view ±2 easily, so turning never waits for a decode.
        val tw = (rw * s).roundToInt().coerceAtLeast(1)
        val th = (rh * s).roundToInt().coerceAtLeast(1)
        if (bmp.width > tw * 1.05f && bmp.height > th * 1.05f) {
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled !== bmp) bmp.recycle()
            return Rendered(scaled, tall)
        }
        return Rendered(bmp, tall)
    }

    override fun close() {
        archive.close()
        file?.close()
    }
}

