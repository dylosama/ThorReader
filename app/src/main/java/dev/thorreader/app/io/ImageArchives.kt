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

/** CBZ: random access straight from the user's file. */
class ZipImageArchive(file: SafFile) : ImageArchive {
    private val zip: ZipFile = ZipFile.builder().setSeekableByteChannel(file.newChannel()).get()
    private val entries: Map<String, ZipArchiveEntry>
    override val pages: List<String>
    private val comicInfoEntry: ZipArchiveEntry?

    init {
        val all = zip.entries.toList().filter { !it.isDirectory }
        entries = all.associateBy { it.name }
        pages = all.map { it.name }.filter(::isImageName).sortedWith(NaturalOrder)
        comicInfoEntry = all.firstOrNull { isComicInfoName(it.name) }
    }

    override fun read(page: String): ByteArray {
        val e = entries[page] ?: throw IOException("Missing $page")
        return zip.getInputStream(e).use { it.readBytes() }
    }

    override fun open(page: String): java.io.InputStream =
        zip.getInputStream(entries[page] ?: throw IOException("Missing $page"))

    override fun comicInfo(): ByteArray? = comicInfoEntry?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

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
                rar.extractFile(h, out)
                if (h.fileName == first) firstBytes = out.toByteArray() else info = out.toByteArray()
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
                if (e.name == first) firstBytes = z.getInputStream(e).readBytes()
                else if (isComicInfoName(e.name)) info = z.getInputStream(e).readBytes()
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
        Log.i(TAG, "Unpacking ${format.name} into ${dir.path}")
        when (format) {
            BookFormat.CBR -> openRar(file).use { rar ->
                if (rar.isEncrypted) throw IOException("Password-protected RAR")
                val headers = rar.fileHeaders.filter { !it.isDirectory }
                val order = headers.map { it.fileName }.filter(::isImageName).sortedWith(NaturalOrder)
                    .withIndex().associate { it.value to it.index }
                headers.forEachIndexed { i, h ->
                    val target = targetFile(dir, h.fileName, order)
                    if (target == null) {
                        if (rar.mainHeader?.isSolid == true) rar.extractFile(h, OutputStream.nullOutputStream())
                    } else {
                        target.outputStream().use { rar.extractFile(h, it) }
                    }
                    progress((i + 1f) / headers.size)
                }
            }
            BookFormat.CB7 -> SevenZFile.builder().setSeekableByteChannel(file.newChannel()).get().use { z ->
                val all = z.entries.filter { !it.isDirectory }
                val order = all.map { it.name }.filter(::isImageName).sortedWith(NaturalOrder)
                    .withIndex().associate { it.value to it.index }
                var n = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    targetFile(dir, e.name, order)?.outputStream()?.use { out -> z.getInputStream(e).copyTo(out) }
                    n++
                    progress(n.toFloat() / all.size.coerceAtLeast(1))
                }
            }
            else -> throw IllegalArgumentException(format.name)
        }
        done.createNewFile()
        prune(root)
        return dir
    }

    private fun targetFile(dir: File, name: String, order: Map<String, Int>): File? {
        if (isComicInfoName(name)) return File(dir, "ComicInfo.xml")
        val idx = order[name] ?: return null
        val ext = name.substringAfterLast('.', "jpg").lowercase()
        return File(dir, "%05d.%s".format(idx, ext))
    }

    private fun prune(root: File) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(3).forEach { it.deleteRecursively() }
    }
}
