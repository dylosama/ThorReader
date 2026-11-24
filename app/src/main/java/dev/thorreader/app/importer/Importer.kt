package dev.thorreader.app.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artifex.mupdf.fitz.Document
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.data.BookDao
import dev.thorreader.app.data.seriesKey
import dev.thorreader.app.engine.MuPdf
import dev.thorreader.app.io.ComicArchives
import dev.thorreader.app.io.SafFile
import dev.thorreader.app.io.ZipImageArchive
import dev.thorreader.app.io.TarImageArchive
import dev.thorreader.app.io.SingleImageArchive
import dev.thorreader.app.io.SafFolderImageArchive
import dev.thorreader.app.io.ImageArchive
import dev.thorreader.app.convert.BookSource
import dev.thorreader.app.convert.DrmProtectedException
import dev.thorreader.app.io.queryFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Imports books chosen with the system file picker (single files or a whole folder). */
class Importer(private val context: Context, private val dao: BookDao, private val scope: CoroutineScope) {

    /** Shown on the library screen while importing; null when idle. */
    var status by mutableStateOf<String?>(null)
        private set

    private val mutex = Mutex()
    private var clearJob: Job? = null

    /** [format] is set for a folder of pictures (it has no file extension). */
    private class Candidate(val uri: Uri, val name: String, val size: Long, val format: BookFormat? = null)

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                guarded {
                    val cands = uris.mapNotNull { u -> queryFileInfo(context, u)?.let { Candidate(u, it.name, it.size) } }
                    run(cands)
                }
            }
        }
    }

    fun importFolder(tree: Uri) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                guarded {
                    postStatus("Scanning folder…")
                    val found = ArrayList<Candidate>()
                    val rootId = DocumentsContract.getTreeDocumentId(tree)
                    val rootName = queryFileInfo(context, DocumentsContract.buildDocumentUriUsingTree(tree, rootId))?.name
                        ?: rootId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Pictures" }
                    runCatching { scan(tree, rootId, rootName, found, 0) }
                        .onFailure { Log.e(TAG, "Folder scan failed", it) }
                    Log.i(TAG, "Folder scan found ${found.size} supported files")
                    run(found)
                }
            }
        }
    }

    /**
     * Book files anywhere below [docId]. A folder that holds pictures but no books becomes one
     * comic (pictures next to books, like covers, are ignored).
     */
    private fun scan(tree: Uri, docId: String, dirName: String, out: MutableList<Candidate>, depth: Int) {
        if (depth > 12) return
        var images = 0
        var imageBytes = 0L
        var books = 0
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) -1 else c.getLong(3)
                val format = BookFormat.fromFileName(name)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!name.startsWith(".") && name != "__MACOSX") scan(tree, id, name, out, depth + 1)
                } else if (format == BookFormat.IMAGE) {
                    if (!name.startsWith(".")) {
                        images++
                        imageBytes += size.coerceAtLeast(0)
                    }
                } else if (format != null) {
                    books++
                    out += Candidate(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size)
                    if (out.size % 25 == 0) postStatus("Scanning folder… ${out.size} books found")
                }
            }
        }
        if (images > 0 && books == 0) {
            out += Candidate(DocumentsContract.buildDocumentUriUsingTree(tree, docId), dirName, imageBytes, BookFormat.IMAGES)
        }
    }

    private suspend fun run(cands: List<Candidate>) {
        val existing = dao.allUris().toHashSet()
        var added = 0
        var skipped = 0
        var drm = 0
        val newIds = ArrayList<Long>()
        var comicsAdded = 0
        cands.forEachIndexed { i, c ->
            val format = c.format ?: BookFormat.fromFileName(c.name)
            if (format == null || c.uri.toString() in existing) {
                skipped++
                return@forEachIndexed
            }
            postStatus("Importing ${i + 1} of ${cands.size}: ${c.name}")
            val meta = try {
                extract(c.uri, c.name, c.size, format)
            } catch (e: DrmProtectedException) {
                Log.w(TAG, "Not imported: '${c.name}' is DRM-protected")
                drm++
                return@forEachIndexed
            } catch (t: Throwable) {
                Log.w(TAG, "Metadata extraction failed for ${c.name}", t)
                ExtractedMeta()
            }
            // A picture folder has no extension: don't let "Vol. 2" lose its number to the "." rule.
            val book = withFileMeta(
                Book(
                    uri = c.uri.toString(),
                    fileName = c.name,
                    format = format.name,
                    sizeBytes = c.size,
                    title = c.name,
                    pageCount = meta.pageCount,
                    coverPath = saveCover(meta),
                    // New books start in the last mode used while reading.
                    mode = ThorApp.instance.settings.lastBookMode.name,
                    lookupState = "pending",
                ),
                meta,
            )
            // A direction chosen earlier for this series applies to new volumes too.
            val seriesRtl = seriesKey(book.series)?.let { dao.seriesRtl(it) }
            val newId = dao.insert(book.copy(rtlOverride = seriesRtl))
            if (newId > 0) {
                added++
                newIds += newId
                if (format.comic && !book.isManga) comicsAdded++
                Log.i(TAG, "Imported '${book.title}' (${format.label}, ${book.pageCount} pages, series=${book.series} #${book.seriesNumber}, manga flag=${book.mangaRtl}, series direction=${seriesRtl ?: "none"})")
            }
        }
        // Online info for the new books (in the background, one at a time).
        ThorApp.instance.lookup.enqueue(newIds, "import")
        if (comicsAdded > 0) ThorApp.instance.lookup.comicsImported()
        if (drm > 0 && added == 0 && skipped == 0) {
            finalStatus(if (drm == 1) "This book is DRM-protected, so it can't be added" else "These $drm books are DRM-protected, so they can't be added")
            return
        }
        finalStatus(
            "Added $added book(s)" +
                (if (drm > 0) ", $drm not added: DRM-protected" else "") +
                (if (skipped > 0) ", skipped $skipped already in library or unsupported" else ""),
        )
    }

    /** Re-read one book's metadata from its file with the current rules (one-time re-scan). */
    fun rescan(b: Book): Book? = runCatching {
        withFileMeta(b, extract(Uri.parse(b.uri), b.fileName, b.sizeBytes, BookFormat.valueOf(b.format)))
    }.onFailure { Log.w(TAG, "Re-scan of '${b.title}' failed: ${it.message}") }.getOrNull()

    /** Any failure still ends with a final status that then clears itself. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            postStatus(null)
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Import failed", t)
            finalStatus("Import failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Show [s] for 5 seconds, then clear it - unless a newer import has started meanwhile. */
    private fun finalStatus(s: String) {
        scope.launch(Dispatchers.Main) {
            status = s
            clearJob?.cancel()
            clearJob = launch {
                delay(5000)
                status = null
            }
        }
    }

    private fun extract(uri: Uri, name: String, size: Long, format: BookFormat): ExtractedMeta {
        val m = ExtractedMeta()
        if (format == BookFormat.IMAGES) {
            // A folder of pictures: no single file to open.
            SafFolderImageArchive(context, uri).use { a -> comicMeta(a, m) }
            return m
        }
        SafFile.open(context, uri).use { f ->
            when (format) {
                BookFormat.EPUB -> {
                    runCatching { EpubMeta.read(f, m) }.onFailure { Log.w(TAG, "OPF parse failed: $name", it) }
                    muReflow(MuPdf.open(f, name), m)
                }
                BookFormat.FB2 -> {
                    runCatching { Fb2Meta.read(f, m) }.onFailure { Log.w(TAG, "FB2 parse failed: $name", it) }
                    muReflow(MuPdf.open(f, name), m)
                }
                BookFormat.TXT -> muReflow(MuPdf.openText(f), m, cover = false)
                BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3, BookFormat.HTML, BookFormat.MARKDOWN,
                BookFormat.DOCX, BookFormat.ODT, BookFormat.RTF -> {
                    // Same route as reading (MuPDF itself, or our converter -> cached EPUB).
                    val o = BookSource.open(context, uri, name, size, format, f)
                    Log.i(TAG, "Import '$name' (${format.label}) via ${o.how}")
                    try {
                        o.epub?.let { e -> runCatching { EpubMeta.read(e, m) }.onFailure { Log.w(TAG, "Converted OPF unreadable: $name", it) } }
                        muReflow(o.doc, m)
                    } finally {
                        o.epub?.close()
                    }
                }
                BookFormat.CBT -> TarImageArchive(f).use { a -> comicMeta(a, m) }
                BookFormat.IMAGE -> SingleImageArchive(f, name).use { a -> comicMeta(a, m) }
                BookFormat.IMAGES -> Unit
                BookFormat.PDF, BookFormat.XPS -> {
                    val doc = MuPdf.open(f, name)
                    try {
                        muInfo(doc, m)
                        m.pageCount = doc.countPages()
                        if (m.coverBytes == null) m.coverBitmap = MuPdf.renderCover(doc)
                    } finally {
                        doc.destroy()
                    }
                }
                BookFormat.CBZ -> ZipImageArchive(f).use { a ->
                    a.comicInfo()?.let { runCatching { ComicInfo.read(it, m) } }
                    m.pageCount = a.pages.size
                    m.coverBytes = a.pages.firstOrNull()?.let { a.read(it) }
                }
                BookFormat.CBR, BookFormat.CB7 -> {
                    val scan = ComicArchives.scan(f, format)
                    scan.comicInfo?.let { runCatching { ComicInfo.read(it, m) } }
                    m.pageCount = scan.pageCount
                    m.coverBytes = scan.firstPage
                }
            }
        }
        return m
    }

    private fun comicMeta(a: ImageArchive, m: ExtractedMeta) {
        a.comicInfo()?.let { runCatching { ComicInfo.read(it, m) } }
        m.pageCount = a.pages.size
        m.coverBytes = a.pages.firstOrNull()?.let { a.read(it) }
    }

    /** Lay the book out at a reference page size to count pages; title/author/cover fallbacks. */
    private fun muReflow(doc: Document, m: ExtractedMeta, cover: Boolean = true) {
        try {
            muInfo(doc, m)
            doc.layout(MuPdf.REF_PAGE_W_PT, MuPdf.REF_PAGE_H_PT, MuPdf.REF_FONT_PT)
            m.pageCount = doc.countPages()
            if (cover && m.coverBytes == null) m.coverBitmap = MuPdf.renderCover(doc)
        } finally {
            doc.destroy()
        }
    }

    private fun muInfo(doc: Document, m: ExtractedMeta) {
        if (m.title == null) m.title = MuPdf.meta(doc, Document.META_INFO_TITLE)
        if (m.authors.isEmpty()) MuPdf.meta(doc, Document.META_INFO_AUTHOR)?.let { m.authors += it }
        if (m.genres.isEmpty()) {
            MuPdf.meta(doc, Document.META_INFO_SUBJECT)?.let { m.genres += it }
            MuPdf.meta(doc, Document.META_INFO_KEYWORDS)?.let { k -> m.genres += k.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() } }
        }
    }

    /** Cover thumbnail (max 480 px tall) cached as JPEG in app storage. */
    private fun saveCover(m: ExtractedMeta): String? {
        val src: Bitmap = m.coverBitmap ?: m.coverBytes?.let { bytes ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= 480) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = (480f / src.height).coerceAtMost(1f)
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), 480, true) else src
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return out.absolutePath
    }

    /** In-progress status. Cancels any pending auto-clear from an earlier import. */
    private fun postStatus(s: String?) {
        scope.launch(Dispatchers.Main) {
            clearJob?.cancel()
            status = s
        }
    }
}

/**
 * Book fields from what the file itself says ([meta]) plus the file name, using the v0.2.0
 * rules: genres are clean short tags, long text is the description, writers then artists are the
 * authors. Fields edited by hand are kept. Used on import and by the one-time re-scan.
 */
fun withFileMeta(b: Book, meta: ExtractedMeta): Book {
    val format = runCatching { BookFormat.valueOf(b.format) }.getOrNull()
    val comic = format?.comic == true
    // A picture folder has no extension: don't let "Vol. 2" lose its number to the "." rule.
    val parsed = FilenameParser.parse(if (format == BookFormat.IMAGES) "${b.fileName}.dir" else b.fileName, comic)
    val seriesFromName = comic && format != BookFormat.IMAGE
    val genres = dev.thorreader.app.metadata.Genres.normalize(meta.genres, trusted = meta.trustedGenres)
    val description = meta.description?.takeIf { it.isNotBlank() } ?: dev.thorreader.app.metadata.Genres.descriptionFrom(meta.genres)
    val people = (meta.authors + meta.artists).distinct().joinToString(", ").ifEmpty { null }
    fun <T> keep(field: String, old: T, new: T): T = if (b.edited(field)) old else new
    return b.copy(
        title = keep("title", b.title, meta.title ?: parsed.title),
        author = keep("authors", b.author, people ?: parsed.author),
        series = keep("series", b.series, meta.series ?: if (seriesFromName) parsed.series else null),
        seriesNumber = keep("number", b.seriesNumber, if (meta.series != null) meta.seriesNumber else if (seriesFromName) parsed.number else null),
        genres = keep("genres", b.genres, dev.thorreader.app.metadata.Genres.join(genres)),
        description = keep("description", b.description, description),
        mangaRtl = meta.mangaRtl || b.mangaRtl,
        isManga = meta.isManga || b.isManga,
        publisher = meta.publisher ?: b.publisher,
        year = meta.year ?: parsed.year ?: b.year,
        isbn = meta.isbn ?: b.isbn,
    )
}
