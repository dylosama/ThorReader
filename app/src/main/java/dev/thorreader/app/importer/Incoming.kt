package dev.thorreader.app.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.convert.DrmProtectedException
import dev.thorreader.app.data.BookDao
import dev.thorreader.app.io.queryFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** One file to import from another app, with the metadata it came with (may be empty). */
data class IncomingItem(val uri: Uri, val meta: IncomingMeta = IncomingMeta())

/** What happened to one incoming file. [bookId] is the new (or, for a duplicate, the existing) book. */
data class IncomingResult(val uri: Uri, val name: String, val status: ImportStatus, val bookId: Long = -1, val message: String = "")

/**
 * Imports files handed to Thor Reader by other apps (sharing, "Open with", Thor Bookloader):
 * content:// links only -> copied to a private temp file (size-capped, SHA-256 on the way) ->
 * type from the bytes ([Sniff]) -> archive safety ([ArchiveSafety]) -> "Already in library"
 * check by hash -> copied into the Imported books folder (the other app's link is temporary) ->
 * imported like any book. Each file is logged with the calling package and its result.
 */
class IncomingImporter(private val context: Context, private val dao: BookDao) {

    val hashes = ContentHashes(context)

    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun importAll(
        items: List<IncomingItem>,
        from: String,
        onProgress: (index: Int, count: Int, name: String) -> Unit = { _, _, _ -> },
    ): List<IncomingResult> = withContext(Dispatchers.IO) {
        items.mapIndexed { i, item ->
            val name = displayName(item.uri)
            onProgress(i, items.size, name)
            val r = try {
                importOne(item, name, from)
            } catch (e: DrmProtectedException) {
                IncomingResult(item.uri, name, ImportStatus.UNSUPPORTED, message = "DRM-protected")
            } catch (e: UnsafeArchiveException) {
                IncomingResult(item.uri, name, ImportStatus.FAILED, message = "Unsafe archive: ${e.message}")
            } catch (e: SecurityException) {
                IncomingResult(item.uri, name, ImportStatus.FAILED, message = "No permission to read this file")
            } catch (e: Exception) {
                Log.w(TAG, "Import from $from failed: '$name'", e)
                IncomingResult(item.uri, name, ImportStatus.FAILED, message = e.message ?: e.javaClass.simpleName)
            }
            // Kept in release builds too (Log.i is stripped there): one line per import, no contents.
            Log.println(Log.INFO, TAG, "Import from $from: '$name' -> ${r.status}" + (if (r.bookId > 0) " (book ${r.bookId})" else "") + (if (r.message.isNotEmpty()) ": ${r.message}" else ""))
            r
        }
    }

    private suspend fun importOne(item: IncomingItem, name: String, from: String): IncomingResult {
        val uri = item.uri
        // Never raw file paths (or anything else) from another app: only content:// links.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return IncomingResult(uri, name, ImportStatus.FAILED, message = "Only content:// links are accepted")
        }
        val folder = ThorApp.instance.settings.importFolder
            ?: return IncomingResult(uri, name, ImportStatus.NEEDS_FOLDER, message = "Choose the Imported books folder first")

        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val temp = File(dir, UUID.randomUUID().toString())
        try {
            val sha = resolver.openInputStream(uri)?.use { copyHashing(it, temp) }
                ?: throw IOException("The file can't be opened")
            val head = temp.inputStream().use { s -> ByteArray(Sniff.HEAD).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
            val format = Sniff.detect(head, name)
                ?: return IncomingResult(uri, name, ImportStatus.UNSUPPORTED, message = "Not a supported book, comic or document")
            if (format == BookFormat.IMAGES) return IncomingResult(uri, name, ImportStatus.UNSUPPORTED)
            ArchiveSafety.check(temp, format)

            findDuplicate(sha, temp.length())?.let { id ->
                return IncomingResult(uri, name, ImportStatus.DUPLICATE, id, "Already in library")
            }

            val fileName = safeFileName(name, format)
            val doc = try {
                createInFolder(Uri.parse(folder), fileName)
            } catch (e: Exception) {
                // The folder was deleted or its permission taken back: ask for it again.
                Log.w(TAG, "Imported books folder not usable: ${e.message}")
                ThorApp.instance.settings.updateImportFolder(null)
                return IncomingResult(uri, name, ImportStatus.NEEDS_FOLDER, message = "The Imported books folder is gone; choose it again")
            }
            try {
                resolver.openOutputStream(doc, "w")?.use { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
                    ?: throw IOException("Can't write to the Imported books folder")
                val finalName = queryFileInfo(context, doc)?.name ?: fileName
                val id = ThorApp.instance.importer.importIncoming(doc, finalName, temp.length(), format, sha, item.meta, from)
                if (id <= 0) throw IOException("The library refused the book")
                return IncomingResult(uri, finalName, ImportStatus.IMPORTED, id)
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) } // no half-imported leftovers
                throw e
            }
        } finally {
            temp.delete()
        }
    }

    /** Same content already in the library? Older books are hashed now, but only those of the same size. */
    private suspend fun findDuplicate(sha: String, size: Long): Long? {
        val candidates = dao.ofSize(size).filter { it.format != BookFormat.IMAGES.name && hashes.get(it.id) == null }
        return duplicateOf(
            sha,
            known = hashes.idsWith(sha).firstOrNull { id -> dao.get(id) != null },
            candidates = candidates.map { b -> b.id to { runCatching { resolver.openInputStream(Uri.parse(b.uri))?.use { sha256(it) } }.getOrNull() } },
            store = { id, h -> hashes.set(id, h) },
        )
    }

    private fun createInFolder(tree: Uri, fileName: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, parent, "application/octet-stream", fileName)
            ?: throw IOException("Could not create the file")
    }

    private fun displayName(uri: Uri): String =
        runCatching { queryFileInfo(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "book"

    companion object {
        /**
         * The duplicate decision: a book already known by this hash ([known]), otherwise each
         * same-size book without a hash is hashed now (lazily, and remembered via [store]) until
         * one matches. Null = new content.
         */
        suspend fun duplicateOf(
            sha: String,
            known: Long?,
            candidates: List<Pair<Long, () -> String?>>,
            store: suspend (Long, String) -> Unit,
        ): Long? {
            known?.let { return it }
            for ((id, hash) in candidates) {
                val h = hash() ?: continue
                store(id, h)
                if (h == sha) return id
            }
            return null
        }

        /** Larger files are refused (nothing real comes close). */
        const val MAX_INCOMING = 2L shl 30

        /** Copy [input] to [out] (capped at [max]) and return the SHA-256 of what was copied. */
        fun copyHashing(input: InputStream, out: File, max: Long = MAX_INCOMING): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(256 * 1024)
            var total = 0L
            out.outputStream().use { o ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) throw IOException("The file is too large")
                    md.update(buf, 0, n)
                    o.write(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /**
         * A plain file name for the Imported books folder: no folders, control or reserved
         * characters, at most 120 characters, and an extension that matches what the file is.
         */
        fun safeFileName(name: String, format: BookFormat): String {
            val base0 = name.substringAfterLast('/').substringAfterLast('\\')
                .filter { it >= ' ' && it !in "<>:\"|?*" }.trim()
            val ext = base0.substringAfterLast('.', "").lowercase()
            val stem = (if (base0.contains('.')) base0.substringBeforeLast('.') else base0)
                .trim().trimStart('.').ifEmpty { "book" }.take(120)
            val finalExt = if (ext in format.extensions) ext else format.extensions.firstOrNull() ?: "bin"
            return "$stem.$finalExt"
        }
    }
}
