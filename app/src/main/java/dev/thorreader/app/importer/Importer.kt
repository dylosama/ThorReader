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

    private class Candidate(val uri: Uri, val name: String, val size: Long)

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
                    runCatching { scan(tree, DocumentsContract.getTreeDocumentId(tree), found, 0) }
                        .onFailure { Log.e(TAG, "Folder scan failed", it) }
                    Log.i(TAG, "Folder scan found ${found.size} supported files")
                    run(found)
                }
            }
        }
    }

    private fun scan(tree: Uri, docId: String, out: MutableList<Candidate>, depth: Int) {
        if (depth > 12) return
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
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scan(tree, id, out, depth + 1)
                } else if (BookFormat.fromFileName(name) != null) {
                    out += Candidate(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, if (c.isNull(3)) -1 else c.getLong(3))
                    if (out.size % 25 == 0) postStatus("Scanning folder… ${out.size} books found")
                }
            }
        }
    }

    private suspend fun run(cands: List<Candidate>) {
        val existing = dao.allUris().toHashSet()
        var added = 0
        var skipped = 0
        cands.forEachIndexed { i, c ->
            val format = BookFormat.fromFileName(c.name)
            if (format == null || c.uri.toString() in existing) {
                skipped++
                return@forEachIndexed
            }
            postStatus("Importing ${i + 1} of ${cands.size}: ${c.name}")
            val meta = try {
                extract(c.uri, c.name, format)
            } catch (t: Throwable) {
                Log.w(TAG, "Metadata extraction failed for ${c.name}", t)
                ExtractedMeta()
            }
            val parsed = FilenameParser.parse(c.name, format.comic)
            val title = meta.title ?: parsed.title
            val book = Book(
                uri = c.uri.toString(),
                fileName = c.name,
                format = format.name,
                sizeBytes = c.size,
                title = title,
                author = meta.authors.distinct().joinToString(", ").ifEmpty { null } ?: parsed.author,
                series = meta.series ?: if (format.comic) parsed.series else null,
                seriesNumber = if (meta.series != null) meta.seriesNumber else if (format.comic) parsed.number else null,
                genres = meta.genres.distinct().joinToString("; ").ifEmpty { null },
                pageCount = meta.pageCount,
                coverPath = saveCover(meta),
                mangaRtl = meta.mangaRtl,
                // New books start in the last mode used while reading.
                mode = ThorApp.instance.settings.lastBookMode.name,
            )
            // A direction chosen earlier for this series applies to new volumes too.
            val seriesRtl = seriesKey(book.series)?.let { dao.seriesRtl(it) }
            if (dao.insert(book.copy(rtlOverride = seriesRtl)) > 0) {
                added++
                Log.i(TAG, "Imported '${book.title}' (${format.label}, ${book.pageCount} pages, series=${book.series} #${book.seriesNumber}, manga flag=${book.mangaRtl}, series direction=${seriesRtl ?: "none"})")
            }
        }
        finalStatus("Added $added book(s)" + if (skipped > 0) ", skipped $skipped already in library or unsupported" else "")
    }

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

    private fun extract(uri: Uri, name: String, format: BookFormat): ExtractedMeta {
        val m = ExtractedMeta()
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
                BookFormat.MOBI -> muReflow(MuPdf.open(f, name), m)
                BookFormat.TXT -> muReflow(MuPdf.openText(f), m, cover = false)
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
