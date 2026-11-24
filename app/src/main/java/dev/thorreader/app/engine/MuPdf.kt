package dev.thorreader.app.engine

import android.graphics.Bitmap
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
