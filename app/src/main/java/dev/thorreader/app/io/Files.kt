package dev.thorreader.app.io

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import com.github.junrar.Archive
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * A book file opened through the Storage Access Framework. Nothing is copied: we read the
 * user's file directly via its file descriptor. All readers use positional reads, so several
 * readers can share one [channel] safely.
 */
class SafFile private constructor(private val pfd: ParcelFileDescriptor) : Closeable {
    private val input = FileInputStream(pfd.fileDescriptor)
    val channel: FileChannel = input.channel
    val size: Long get() = channel.size()

    /** Independent read cursor over this file (for commons-compress). Closing it is a no-op. */
    fun newChannel(): SeekableByteChannel = PositionalChannel(channel)

    fun readAll(): ByteArray {
        val n = size
        if (n > Limits.MAX_FILE_IN_MEMORY) throw TooLargeException("the file")
        val buf = ByteBuffer.allocate(n.toInt())
        var pos = 0L
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r <= 0) break
            pos += r
        }
        return buf.array()
    }

    /** The first [n] bytes (or the whole file if it is shorter). */
    fun readHead(n: Int): ByteArray {
        val buf = ByteBuffer.allocate(minOf(n.toLong(), size).toInt())
        var pos = 0L
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r <= 0) break
            pos += r
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { pfd.close() }
    }

    companion object {
        /** One of our own files (e.g. a converted EPUB in app storage). */
        fun open(file: java.io.File): SafFile = SafFile(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

        fun open(context: Context, uri: Uri): SafFile {
            // Only files the user picked (persisted SAF grants). If that permission was taken
            // back (or the file moved), say so plainly instead of crashing.
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: SecurityException) {
                throw NoAccessException()
            } catch (e: java.io.FileNotFoundException) {
                throw IOException("The file can't be found any more (moved, renamed or deleted?).")
            } ?: throw IOException("The file can't be opened.")
            val f = SafFile(pfd)
            try {
                f.channel.size() // fails for non-seekable pipes (some cloud providers)
            } catch (e: Exception) {
                f.close()
                throw IOException("This file can't be read directly (cloud file?). Copy it to the device.", e)
            }
            return f
        }
    }
}

data class FileInfo(val name: String, val size: Long)

fun queryFileInfo(context: Context, uri: Uri): FileInfo? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: return null
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            FileInfo(name, size)
        }

/** SeekableByteChannel with its own position, reading [ch] with positional reads. */
private class PositionalChannel(private val ch: FileChannel) : SeekableByteChannel {
    private var pos = 0L
    override fun read(dst: ByteBuffer): Int {
        val n = ch.read(dst, pos)
        if (n > 0) pos += n
        return n
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel = apply { pos = newPosition }
    override fun size(): Long = ch.size()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = ch.isOpen
    override fun close() = Unit
}

/** MuPDF input stream over a SAF file. */
class MuPdfStream(private val ch: FileChannel) : SeekableInputStream {
    private var pos = 0L
    override fun read(buf: ByteArray): Int {
        val n = ch.read(ByteBuffer.wrap(buf), pos)
        if (n <= 0) return -1
        pos += n
        return n
    }

    override fun seek(offset: Long, whence: Int): Long {
        pos = when (whence) {
            SeekableStream.SEEK_SET -> offset
            SeekableStream.SEEK_CUR -> pos + offset
            SeekableStream.SEEK_END -> ch.size() + offset
            else -> pos
        }
        return pos
    }

    override fun position(): Long = pos
}

/** junrar access to a SAF file without loading it into memory. */
fun openRar(file: SafFile): Archive {
    val manager = object : VolumeManager {
        override fun nextVolume(archive: Archive, last: Volume?): Volume? {
            if (last != null) return null // single-volume archives only
            return object : Volume {
                override fun getChannel(): SeekableReadOnlyByteChannel = RarChannel(file.channel)
                override fun getLength(): Long = file.size
                override fun getArchive(): Archive = archive
            }
        }
    }
    return Archive(manager, null, null)
}

private class RarChannel(private val ch: FileChannel) : SeekableReadOnlyByteChannel {
    private var pos = 0L
    private val one = ByteBuffer.allocate(1)
    override fun getPosition(): Long = pos
    override fun setPosition(p: Long) {
        pos = p
    }

    override fun read(): Int {
        one.clear()
        val n = ch.read(one, pos)
        if (n <= 0) return -1
        pos++
        return one.get(0).toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, off: Int, count: Int): Int {
        if (count == 0) return 0
        val n = ch.read(ByteBuffer.wrap(buffer, off, count), pos)
        if (n <= 0) return -1
        pos += n
        return n
    }

    override fun readFully(buffer: ByteArray, count: Int): Int {
        var done = 0
        while (done < count) {
            val n = read(buffer, done, count - done)
            if (n <= 0) throw IOException("Unexpected end of RAR file")
            done += n
        }
        return done
    }

    override fun close() = Unit
}

/** "page2" < "page10", case-insensitive. Used to order comic pages. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** The permission to read a book's file (or folder) is gone. */
class NoAccessException : IOException(
    "Thor Reader is no longer allowed to read this file.\nAdd it again with + (Add files / Add folder).",
)
