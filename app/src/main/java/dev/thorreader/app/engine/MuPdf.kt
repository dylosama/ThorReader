package dev.thorreader.app.engine

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Archive
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import dev.thorreader.app.importer.TextToHtml
import dev.thorreader.app.io.MuPdfStream
import dev.thorreader.app.io.SafFile
import java.io.IOException

/** Shared MuPDF helpers. */
object MuPdf {
    /** Height of the "endless" pages used for reflowable layout (we cut screen pages ourselves). */
    const val TALL_PAGE_PT = 40000f

    /** Reference layout used on import to count pages: roughly a bottom-screen page at 10pt. */
    const val REF_PAGE_W_PT = 196f
    const val REF_PAGE_H_PT = 227f
    const val REF_FONT_PT = 10f

    private var currentCss: String? = null

    fun init() {
        Context.init()
        setCss(maxImageHeightPt = null)
    }

    /**
     * Our own stylesheet on top of the book's: no page margins (we draw margins ourselves) and,
     * when known, images limited to one screen-page height so they aren't cut in half.
     */
    @Synchronized
    fun setCss(maxImageHeightPt: Float?) {
        val css = buildString {
            append("@page { margin: 0 !important; } ")
            append("html, body { margin: 0 !important; padding: 0 !important; } ")
            if (maxImageHeightPt != null) {
                append("img, svg, image { max-width: 100% !important; max-height: ${maxImageHeightPt.toInt()}pt !important; } ")
            }
        }
        if (css != currentCss) {
            Context.setUserCSS(css)
            currentCss = css
        }
    }

    /** Opens a book; [nameHint] (the file name) tells MuPDF the format. */
    fun open(file: SafFile, nameHint: String): Document {
        val doc = Document.openDocument(MuPdfStream(file.channel), nameHint)
        if (doc.needsPassword()) {
            doc.destroy()
            throw IOException("This book is password-protected / DRM-locked.")
        }
        return doc
    }

    fun openText(file: SafFile): Document = Document.openDocument(TextToHtml.convert(file.readAll()), "text/html")

    /** Open from memory; [archive] supplies files the document refers to (pictures, CSS). */
    fun openBytes(bytes: ByteArray, magic: String, archive: Archive?): Document {
        val doc = if (archive != null) Document.openDocument(bytes, magic, archive) else Document.openDocument(bytes, magic)
        if (doc.needsPassword()) {
            doc.destroy()
            throw IOException("This book is password-protected / DRM-locked.")
        }
        return doc
    }

    /** Does this MuPDF build have a reader for files ending in [ext]? */
    fun supports(ext: String): Boolean = runCatching { Document.recognize("book.$ext") }.getOrDefault(false)

    /**
     * True if [doc] really re-flows to the requested page size (rather than having fixed pages),
     * so text size and margins work.
     */
    fun reflows(doc: Document): Boolean = runCatching {
        doc.layout(REF_PAGE_W_PT, TALL_PAGE_PT, REF_FONT_PT)
        if (doc.countChapters() == 0 || doc.countPages(0) == 0) return false
        val page = doc.loadPage(0, 0)
        try {
            val b = page.bounds
            kotlin.math.abs((b.x1 - b.x0) - REF_PAGE_W_PT) < 1f && kotlin.math.abs((b.y1 - b.y0) - TALL_PAGE_PT) < 1f
        } finally {
            page.destroy()
        }
    }.getOrDefault(false)

    /** Logs which extra formats this MuPDF build reads by itself (decides native vs converter). */
    fun logNativeSupport() {
        val exts = listOf("epub", "mobi", "azw", "azw3", "fb2", "html", "xhtml", "md", "docx", "odt", "rtf", "cbz", "cbt", "jpg", "png", "webp", "gif", "avif", "txt")
        val (yes, no) = exts.partition { supports(it) }
        android.util.Log.i(dev.thorreader.app.TAG, "MuPDF native support: YES ${yes.joinToString(" ")} | NO ${no.joinToString(" ")}")
    }

    fun renderCover(doc: Document, maxW: Int = 420, maxH: Int = 600): Bitmap? = runCatching {
        if (doc.countPages() == 0) return null
        val page = doc.loadPage(0)
        try {
            AndroidDrawDevice.drawPageFit(page, maxW, maxH)
        } finally {
            page.destroy()
        }
    }.getOrNull()

    fun meta(doc: Document, key: String): String? =
        runCatching { doc.getMetaData(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
