package dev.thorreader.app.importer

import dev.thorreader.app.io.Limits
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** An archive from another app failed the safety check; [message] says why, in plain words. */
class UnsafeArchiveException(message: String) : IOException(message)

/**
 * Checks an archive handed to us by another app BEFORE it is imported: entry names that could
 * escape a folder ("zip slip": "..", absolute paths, drive letters, NUL), too many entries, and
 * too much unpacked data - zip entries are really decompressed (with caps), because the sizes an
 * archive declares can lie. RAR / 7z / TAR are checked from their headers; their readers enforce
 * the same caps again while reading.
 */
object ArchiveSafety {

    data class Caps(
        val maxEntries: Int = Limits.MAX_ENTRIES,
        val maxEntry: Long = Limits.MAX_UNPACKED_FILE,
        val maxTotal: Long = Limits.MAX_UNPACKED_TOTAL,
        /** Unpacked / packed ratio above which a big archive counts as a decompression bomb. */
        val maxRatio: Long = 200,
    )

    fun check(file: File, format: BookFormat, caps: Caps = Caps()) {
        when (format) {
            BookFormat.EPUB, BookFormat.CBZ, BookFormat.DOCX, BookFormat.ODT, BookFormat.XPS -> checkZip(file, caps)
            BookFormat.CBT -> checkTar(file, caps)
            BookFormat.CB7 -> check7z(file, caps)
            BookFormat.CBR -> checkRar(file, caps)
            else -> Unit
        }
    }

    /** A name that would stay inside the folder it is unpacked to. */
    fun safeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.contains('\u0000')) return false
        val n = name.replace('\\', '/')
        if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) return false
        return n.split('/').none { it == ".." }
    }

    private class Counter(private val caps: Caps, private val packed: Long) {
        var entries = 0
        var total = 0L
        fun entry(name: String) {
            if (!safeEntryName(name)) throw UnsafeArchiveException("unsafe entry name in the archive")
            if (++entries > caps.maxEntries) throw UnsafeArchiveException("too many files in the archive")
        }

        fun add(n: Long) {
            total += n
            if (total > caps.maxTotal) throw UnsafeArchiveException("the archive unpacks to too much data")
            if (total > 64L shl 20 && total > packed.coerceAtLeast(1) * caps.maxRatio) {
                throw UnsafeArchiveException("the archive looks like a decompression bomb")
            }
        }
    }

    private fun checkZip(file: File, caps: Caps) {
        val c = Counter(caps, file.length())
        val buf = ByteArray(64 * 1024)
        try {
            ZipFile(file).use { zip ->
                for (e in zip.entries()) {
                    c.entry(e.name)
                    if (e.isDirectory) continue
                    var size = 0L
                    zip.getInputStream(e).use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > caps.maxEntry) throw UnsafeArchiveException("a file in the archive is too large")
                            c.add(n.toLong())
                        }
                    }
                }
            }
        } catch (e: UnsafeArchiveException) {
            throw e
        } catch (e: Exception) {
            throw UnsafeArchiveException("the archive is damaged (${e.javaClass.simpleName})")
        }
    }

    private fun checkTar(file: File, caps: Caps) {
        val c = Counter(caps, file.length())
        try {
            TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    c.entry(e.name)
                    if (e.isSymbolicLink || e.isLink) throw UnsafeArchiveException("links inside the archive")
                    if (e.size > caps.maxEntry) throw UnsafeArchiveException("a file in the archive is too large")
                    c.add(e.size)
                }
            }
        } catch (e: UnsafeArchiveException) {
            throw e
        } catch (e: Exception) {
            throw UnsafeArchiveException("the archive is damaged (${e.javaClass.simpleName})")
        }
    }

    private fun check7z(file: File, caps: Caps) {
        val c = Counter(caps, file.length())
        try {
            SevenZFile.builder().setFile(file).get().use { z ->
                for (e in z.entries) {
                    c.entry(e.name ?: "")
                    if (e.size > caps.maxEntry) throw UnsafeArchiveException("a file in the archive is too large")
                    c.add(e.size)
                }
            }
        } catch (e: UnsafeArchiveException) {
            throw e
        } catch (e: Exception) {
            throw UnsafeArchiveException("the archive is damaged (${e.javaClass.simpleName})")
        }
    }

    private fun checkRar(file: File, caps: Caps) {
        val c = Counter(caps, file.length())
        try {
            com.github.junrar.Archive(file).use { rar ->
                for (h in rar.fileHeaders) {
                    c.entry(h.fileName ?: "")
                    if (h.fullUnpackSize > caps.maxEntry) throw UnsafeArchiveException("a file in the archive is too large")
                    c.add(h.fullUnpackSize)
                }
            }
        } catch (e: UnsafeArchiveException) {
            throw e
        } catch (e: Exception) {
            throw UnsafeArchiveException("the archive is damaged or unsupported (${e.javaClass.simpleName})")
        }
    }
}
