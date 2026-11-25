package dev.thorreader.app.io

import android.content.Context
import android.util.Log
import dev.thorreader.app.TAG
import dev.thorreader.app.importer.BookFormat
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

fun isImageName(path: String): Boolean {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    if (name.startsWith(".") || path.contains("__MACOSX")) return false
    return name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
}

fun isComicInfoName(path: String): Boolean =
    path.substringAfterLast('/').substringAfterLast('\\').equals("ComicInfo.xml", ignoreCase = true)

/** Pages of a comic archive, in reading order (natural sort of file names). */
interface ImageArchive : Closeable {
    val pages: List<String>
    fun read(page: String): ByteArray
    fun open(page: String): java.io.InputStream
    fun comicInfo(): ByteArray?
}

/**
 * CBZ: random access straight from the user's file (nothing is extracted to disk). Reads are
 * size-capped, and symlink entries are ignored.
 */
class ZipImageArchive(channel: java.nio.channels.SeekableByteChannel) : ImageArchive {
    constructor(file: SafFile) : this(file.newChannel())

    private val zip: ZipFile = try {
        ZipFile.builder().setSeekableByteChannel(channel).get()
    } catch (e: Exception) {
        throw IOException("This comic file is damaged (not a readable ZIP).", e)
    }
    private val entries: Map<String, ZipArchiveEntry>
    override val pages: List<String>
    private val comicInfoEntry: ZipArchiveEntry?

    init {
        val all = zip.entries.toList().filter { !it.isDirectory && !it.isUnixSymlink }
        if (all.size > Limits.MAX_ENTRIES) {
            zip.close()
            throw TooLargeException("the number of files")
        }
        entries = all.associateBy { it.name }
        pages = all.map { it.name }.filter(::isImageName).sortedWith(NaturalOrder)
        comicInfoEntry = all.firstOrNull { isComicInfoName(it.name) }
    }

    override fun read(page: String): ByteArray {
        val e = entries[page] ?: throw IOException("Missing $page")
        return zip.getInputStream(e).use { Limits.readCapped(it, Limits.MAX_PAGE, "a page") }
    }

    override fun open(page: String): java.io.InputStream =
        zip.getInputStream(entries[page] ?: throw IOException("Missing $page"))

    override fun comicInfo(): ByteArray? = comicInfoEntry?.let { e -> zip.getInputStream(e).use { SafeXml.read(it) } }

    override fun close() = zip.close()
}

/** CBR/CB7 after unpacking into the cache (solid archives can't be read page-by-page quickly). */
class DirImageArchive(private val dir: File) : ImageArchive {
    override val pages: List<String> =
        (dir.list() ?: emptyArray()).filter { isImageName(it) }.sortedWith(NaturalOrder)

    override fun read(page: String): ByteArray = File(dir, page).readBytes()
    override fun open(page: String): java.io.InputStream = File(dir, page).inputStream()
    override fun comicInfo(): ByteArray? = File(dir, "ComicInfo.xml").takeIf { it.exists() }?.readBytes()
    override fun close() = Unit
}

/** What import needs from a comic archive without unpacking all of it to disk. */
class ComicScan(val pageCount: Int, val firstPage: ByteArray?, val comicInfo: ByteArray?)

object ComicArchives {

    /** Scan a CBR or CB7 sequentially, keeping only ComicInfo.xml and the first page. */
    fun scan(file: SafFile, format: BookFormat): ComicScan = when (format) {
        BookFormat.CBR -> scanRar(file)
        BookFormat.CB7 -> scan7z(file)
        else -> throw IllegalArgumentException(format.name)
    }

    private fun scanRar(file: SafFile): ComicScan = openRar(file).use { rar ->
        if (rar.isEncrypted) throw IOException("Password-protected RAR")
        val headers = rar.fileHeaders.filter { !it.isDirectory }
        val images = headers.map { it.fileName }.filter(::isImageName)
        val first = images.minWithOrNull(NaturalOrder)
        var firstBytes: ByteArray? = null
        var info: ByteArray? = null
        val solid = rar.mainHeader?.isSolid == true
        for (h in headers) {
            val wanted = h.fileName == first || isComicInfoName(h.fileName)
            if (!wanted && !solid) continue
            if (wanted) {
                val out = ByteArrayOutputStream()
                rar.extractFile(h, Limits.CappedOutput(out, Limits.MAX_PAGE, Limits.Budget(Limits.MAX_PAGE)))
                if (h.fileName == first) firstBytes = out.toByteArray() else info = SafeXml.strip(out.toByteArray())
            } else {
                rar.extractFile(h, OutputStream.nullOutputStream())
            }
            if (firstBytes != null && (info != null || headers.none { isComicInfoName(it.fileName) })) break
        }
        ComicScan(images.size, firstBytes, info)
    }

    private fun scan7z(file: SafFile): ComicScan =
        SevenZFile.builder().setSeekableByteChannel(file.newChannel()).get().use { z ->
            val names = z.entries.filter { !it.isDirectory }.map { it.name }
            val images = names.filter(::isImageName)
            val first = images.minWithOrNull(NaturalOrder)
            var firstBytes: ByteArray? = null
            var info: ByteArray? = null
            val hasInfo = names.any(::isComicInfoName)
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == first) firstBytes = Limits.readCapped(z.getInputStream(e), Limits.MAX_PAGE, "a page")
                else if (isComicInfoName(e.name)) info = SafeXml.read(z.getInputStream(e))
                if (firstBytes != null && (info != null || !hasInfo)) break
            }
            ComicScan(images.size, firstBytes, info)
        }

    /**
     * Unpack a CBR/CB7 into cacheDir/comics/<key>/ (pages renamed 00000.ext in reading order).
     * Reuses a previous unpack. Keeps only the 3 most recent unpacked books.
     */
    fun unpack(context: Context, key: String, file: SafFile, format: BookFormat, progress: (Float) -> Unit): File {
        val root = File(context.cacheDir, "comics").apply { mkdirs() }
        val dir = File(root, key)
        val done = File(dir, ".done")
        if (done.exists()) {
            dir.setLastModified(System.currentTimeMillis())
            return dir
        }
        dir.deleteRecursively()
        dir.mkdirs()
        Log.i(TAG, "Unpacking ${format.name} ($key)")
        val budget = Limits.Budget(Limits.MAX_UNPACKED_TOTAL)
        try {
            when (format) {
                BookFormat.CBR -> openRar(file).use { rar ->
                    if (rar.isEncrypted) throw IOException("Password-protected RAR")
                    val headers = rar.fileHeaders.filter { !it.isDirectory }
                    if (headers.size > Limits.MAX_ENTRIES) throw TooLargeException("the number of files")
                    val order = headers.map { it.fileName }.filter(::isImageName).sortedWith(NaturalOrder)
                        .withIndex().associate { it.value to it.index }
                    headers.forEachIndexed { i, h ->
                        val target = targetFile(dir, h.fileName, order)
                        if (target == null) {
                            if (rar.mainHeader?.isSolid == true) rar.extractFile(h, OutputStream.nullOutputStream())
                        } else {
                            target.outputStream().use { rar.extractFile(h, Limits.CappedOutput(it, Limits.MAX_UNPACKED_FILE, budget)) }
                        }
                        progress((i + 1f) / headers.size)
                    }
                }
                BookFormat.CB7 -> SevenZFile.builder().setSeekableByteChannel(file.newChannel()).get().use { z ->
                    val all = z.entries.filter { !it.isDirectory }
                    if (all.size > Limits.MAX_ENTRIES) throw TooLargeException("the number of files")
                    val order = all.map { it.name }.filter(::isImageName).sortedWith(NaturalOrder)
                        .withIndex().associate { it.value to it.index }
                    var n = 0
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        targetFile(dir, e.name, order)?.outputStream()?.use { out ->
                            z.getInputStream(e).copyTo(Limits.CappedOutput(out, Limits.MAX_UNPACKED_FILE, budget))
                        }
                        n++
                        progress(n.toFloat() / all.size.coerceAtLeast(1))
                    }
                }
                else -> throw IllegalArgumentException(format.name)
            }
        } catch (t: Throwable) {
            dir.deleteRecursively() // never leave half an unpack (or a bomb's output) behind
            throw t
        }
        done.createNewFile()
        // Clearing out older unpacked comics can take a while: not on the way to the first page.
        Thread({ runCatching { prune(root) } }, "ThorReader-prune").apply { priority = Thread.MIN_PRIORITY }.start()
        return dir
    }

    /**
     * Where an entry is written: pages are renamed "00042.jpg" by their reading order (the name
     * inside the archive is never used as a path), ComicInfo.xml keeps its name. Anything else
     * is skipped. Always a direct child of [dir].
     */
    internal fun targetFile(dir: File, name: String, order: Map<String, Int>): File? {
        if (isComicInfoName(name)) return Limits.childFile(dir, "ComicInfo.xml")
        val idx = order[name] ?: return null
        return Limits.childFile(dir, "%05d.%s".format(idx, Limits.safeExtension(name)))
    }

    private fun prune(root: File) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(3).forEach { it.deleteRecursively() }
    }
}

/** CBT (tar): random access straight from the user's file, like CBZ. */
class TarImageArchive(channel: java.nio.channels.SeekableByteChannel) : ImageArchive {
    constructor(file: SafFile) : this(file.newChannel())

    private val tar = try {
        org.apache.commons.compress.archivers.tar.TarFile(channel)
    } catch (e: Exception) {
        throw IOException("This comic file is damaged (not a readable TAR).", e)
    }

    // Regular files only: links (symlinks / hard links) are ignored.
    private val entries = tar.entries.filter { it.isFile && !it.isSymbolicLink && !it.isLink }.also {
        if (it.size > Limits.MAX_ENTRIES) {
            tar.close()
            throw TooLargeException("the number of files")
        }
    }.associateBy { it.name }
    override val pages: List<String> = entries.keys.filter(::isImageName).sortedWith(NaturalOrder)
    private val infoEntry = entries.values.firstOrNull { isComicInfoName(it.name) }

    override fun read(page: String): ByteArray = open(page).use { Limits.readCapped(it, Limits.MAX_PAGE, "a page") }
    override fun open(page: String): java.io.InputStream = tar.getInputStream(entries[page] ?: throw IOException("Missing $page"))
    override fun comicInfo(): ByteArray? = infoEntry?.let { e -> tar.getInputStream(e).use { SafeXml.read(it) } }
    override fun close() = tar.close()
}

/** A single picture file shown as a one-page comic. */
class SingleImageArchive(private val file: SafFile, name: String) : ImageArchive {
    override val pages: List<String> = listOf(name)
    override fun read(page: String): ByteArray {
        if (file.size > Limits.MAX_PAGE) throw TooLargeException("this picture")
        return file.readAll()
    }
    override fun open(page: String): java.io.InputStream = read(page).inputStream()
    override fun comicInfo(): ByteArray? = null
    override fun close() = Unit
}

/**
 * A folder of pictures (added with "Add folder") shown as one comic, read in place through the
 * folder permission. [folder] is the folder's document uri inside the granted tree.
 */
class SafFolderImageArchive(private val context: Context, folder: android.net.Uri) : ImageArchive {
    private val children = HashMap<String, android.net.Uri>()
    override val pages: List<String>

    init {
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(
            folder.authority, android.provider.DocumentsContract.getTreeDocumentId(folder),
        )
        val list = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, android.provider.DocumentsContract.getDocumentId(folder),
        )
        val cols = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        (try { context.contentResolver.query(list, cols, null, null, null) } catch (e: SecurityException) { throw NoAccessException() })?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) continue
                children[name] = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        pages = children.keys.filter(::isImageName).sortedWith(NaturalOrder)
        if (pages.isEmpty()) throw IOException("No pictures in this folder any more")
    }

    override fun read(page: String): ByteArray = open(page).use { Limits.readCapped(it, Limits.MAX_PAGE, "a page") }
    override fun open(page: String): java.io.InputStream =
        context.contentResolver.openInputStream(children[page] ?: throw IOException("Missing $page")) ?: throw IOException("Cannot read $page")
    override fun comicInfo(): ByteArray? = children.entries.firstOrNull { isComicInfoName(it.key) }?.let { e ->
        context.contentResolver.openInputStream(e.value)?.use { SafeXml.read(it) }
    }
    override fun close() = Unit
}
