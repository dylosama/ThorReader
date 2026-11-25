package dev.thorreader.app.convert

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.TreeArchive
import dev.thorreader.app.TAG
import dev.thorreader.app.engine.MuPdf
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.io.SafFile
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * How a reflowable book is read: straight by MuPDF ("native") wherever MuPDF can, otherwise
 * through our own converter into a cached EPUB that MuPDF then reads.
 */
object BookSource {

    /** An opened reflowable book. [epub]: the converted EPUB file (close it with the document). */
    class Opened(val doc: Document, val epub: SafFile?, val how: String)

    /** The converted EPUB of a book, cached in app storage (keyed by the file's uri and size). */
    fun convertedFile(context: Context, uri: String, size: Long): File {
        val key = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(File(context.filesDir, "converted").apply { mkdirs() }, "$key-$size.epub")
    }

    /**
     * Open a reflowable [format] book. [file] stays owned by the caller (it is not needed after
     * this returns unless [Opened.epub] is null and the format is read natively from it).
     */
    fun open(context: Context, uri: Uri, name: String, size: Long, format: BookFormat, file: SafFile): Opened {
        fun native(magic: String = name): Opened = Opened(MuPdf.open(file, magic), null, "MuPDF")
        fun converted(): Opened {
            val epub = convertedFile(context, uri.toString(), size)
            if (!epub.exists() || epub.length() == 0L) {
                val t0 = System.currentTimeMillis()
                val book = convert(format, file)
                EpubWriter.write(book, epub)
                Log.i(TAG, "Converted '$name' (${format.label}) to EPUB: ${book.sections.size} chapters, ${book.resources.size} pictures, ${epub.length() / 1024} KB in ${System.currentTimeMillis() - t0} ms")
            }
            val f = SafFile.open(epub)
            return try {
                Opened(MuPdf.open(f, "book.epub"), f, "converted to EPUB")
            } catch (t: Throwable) {
                f.close()
                epub.delete()
                throw t
            }
        }
        /** MuPDF first (if it recognises the type and really reflows it), else our converter. */
        fun nativeOr(ext: String, fallback: () -> Opened): Opened {
            if (MuPdf.supports(ext)) {
                try {
                    val o = native("book.$ext")
                    if (MuPdf.reflows(o.doc)) return o
                    o.doc.destroy()
                    Log.i(TAG, "MuPDF opened '$name' but can't reflow it; using our converter")
                } catch (t: Throwable) {
                    Log.w(TAG, "MuPDF could not open '$name' (${t.message}); using our converter")
                }
            }
            return fallback()
        }
        return when (format) {
            BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3 -> {
                val head = file.readHead(256 * 1024)
                if (MobiFile.isDrmProtected(head)) throw DrmProtectedException()
                // KF8 (AZW3, or the KF8 half of a combined file) is converted: MuPDF reads only old MOBI.
                if (format == BookFormat.AZW3 || MobiFile(file.readAll()).kf8 != null) converted()
                else try {
                    native("book.mobi")
                } catch (t: Throwable) {
                    Log.w(TAG, "MuPDF could not open '$name' (${t.message}); using our MOBI reader")
                    converted()
                }
            }
            BookFormat.HTML -> {
                val bytes = file.readAll()
                val archive = siblingFiles(context, uri, String(bytes, Charsets.UTF_8))
                Opened(MuPdf.openBytes(bytes, "book.html", archive), null, "MuPDF" + if (archive != null) " + pictures from the folder" else "")
            }
            BookFormat.MARKDOWN -> {
                val bytes = file.readAll()
                val text = String(bytes, Charsets.UTF_8)
                val archive = siblingFiles(context, uri, text)
                if (MuPdf.supports("md")) {
                    runCatching { MuPdf.openBytes(bytes, "book.md", archive) }.getOrNull()?.let { d ->
                        if (MuPdf.reflows(d)) return Opened(d, null, "MuPDF")
                        d.destroy()
                    }
                }
                Opened(MuPdf.openBytes(MarkdownToHtml.convert(text), "book.html", archive), null, "Markdown converter")
            }
            BookFormat.DOCX -> nativeOr("docx", ::converted)
            BookFormat.ODT -> nativeOr("odt", ::converted)
            BookFormat.RTF -> nativeOr("rtf", ::converted)
            else -> native()
        }
    }

    private fun convert(format: BookFormat, file: SafFile): ConvBook = when (format) {
        BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3 -> {
            val m = MobiFile(file.readAll())
            if (m.drm) throw DrmProtectedException()
            if (m.kf8 != null) m.kf8Book() else m.mobi6Book()
        }
        BookFormat.DOCX -> DocxConverter.convert(file)
        BookFormat.ODT -> OdtConverter.convert(file)
        BookFormat.RTF -> RtfConverter.convert(file.readAll())
        else -> throw IOException("No converter for ${format.label}")
    }

    /**
     * Pictures and style sheets an HTML/Markdown file refers to, read from its folder. Android
     * only lets us see a file's neighbours when it was added with "Add folder" (a folder grant);
     * for a single picked file this returns null and the text shows without pictures.
     */
    fun siblingFiles(context: Context, uri: Uri, html: String): TreeArchive? = runCatching {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val docId = DocumentsContract.getDocumentId(uri)
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
        val slash = docId.lastIndexOf('/')
        val folder = if (slash >= 0) docId.substring(0, slash) else docId.substringBefore(':') + ":"
        val refs = Regex("""(?:src|href|xlink:href)\s*=\s*["']([^"'#?]+)""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.groupValues[1].trim() }
            .plus(Regex("""!\[[^\]]*]\(\s*<?([^)\s>]+)""").findAll(html).map { it.groupValues[1] }) // Markdown images
            .filter { r -> !r.contains("://") && !r.startsWith("data:") && !r.startsWith("mailto:") && !r.startsWith("/") }
            .filter { r -> r.substringAfterLast('.').lowercase() in SIBLING_EXTS }
            .distinct().take(400).toList()
        if (refs.isEmpty()) return null
        val archive = TreeArchive()
        var added = 0
        for (ref in refs) {
            val decoded = runCatching { URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8") }.getOrDefault(ref)
            val id = joinId(folder, decoded) ?: continue
            if (!id.startsWith(treeId.trimEnd('/')) && !treeId.endsWith(":")) continue
            val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val bytes = runCatching { context.contentResolver.openInputStream(child)?.use { dev.thorreader.app.io.Limits.readCapped(it, 25L shl 20, "a picture") } }.getOrNull() ?: continue
            if (bytes.size > 25 shl 20) continue
            val buf = Buffer().apply { writeBytes(bytes) }
            archive.add(clean(decoded), buf)
            if (clean(ref) != clean(decoded)) archive.add(clean(ref), buf)
            added++
        }
        Log.i(TAG, "Pictures next to the file: $added of ${refs.size} found")
        if (added == 0) null else archive
    }.onFailure { Log.w(TAG, "Could not read the files next to ${uri.lastPathSegment?.substringAfterLast('/')}", it) }.getOrNull()

    private val SIBLING_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "css")

    /** "primary:Books/x" + "img/a.png" -> "primary:Books/x/img/a.png" (with ".." handled). */
    private fun joinId(folder: String, rel: String): String? {
        val root = folder.substringBefore(':') + ":"
        val parts = folder.substringAfter(':').split('/').filter { it.isNotEmpty() }.toMutableList()
        for (p in rel.split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return root + parts.joinToString("/")
    }

    /** The name MuPDF asks the archive for: the reference as written, minus a leading "./". */
    private fun clean(p: String) = p.removePrefix("./")
}

/** Markdown -> HTML (CommonMark), used when MuPDF can't open .md itself. */
object MarkdownToHtml {
    fun convert(text: String): ByteArray {
        val doc = org.commonmark.parser.Parser.builder().build().parse(text)
        val body = org.commonmark.renderer.html.HtmlRenderer.builder().build().render(doc)
        val title = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim() ?: ""
        return ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>${esc(title)}</title></head><body>$body</body></html>")
            .toByteArray(Charsets.UTF_8)
    }
}
