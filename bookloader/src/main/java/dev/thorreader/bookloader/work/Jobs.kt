package dev.thorreader.bookloader.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.TAG
import dev.thorreader.bookloader.source.BookMeta
import dev.thorreader.bookloader.source.CatalogItem
import dev.thorreader.bookloader.source.Entry
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class JobAction { IMPORT, SAVE }

/** One queued download: what, from where, with which metadata, and where it goes. */
data class JobSpec(
    val action: JobAction,
    val sourceId: String,
    val item: CatalogItem,
    val entry: Entry,
    val meta: BookMeta,
    /** Already downloaded (a retry, or "Save to storage instead"): skip the download. */
    val localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val title get() = meta.title ?: "${item.title} - ${entry.title}"

    fun toJson(): JSONObject = JSONObject().put("action", action.name).put("sourceId", sourceId).put("item", item.toJson())
        .put("entry", entry.toJson()).put("meta", meta.toJson()).put("localPath", localPath).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = JobSpec(
            action = JobAction.valueOf(o.getString("action")),
            sourceId = o.getString("sourceId"),
            item = CatalogItem.fromJson(o.getJSONObject("item")),
            entry = Entry.fromJson(o.getJSONObject("entry")),
            meta = BookMeta.fromJson(o.getJSONObject("meta")),
            localPath = if (o.isNull("localPath")) null else o.optString("localPath").ifEmpty { null },
            createdAt = o.optLong("createdAt"),
        )
    }
}

/** Job descriptions by WorkManager id (WorkInfo can't show its input, so we keep them here). */
object JobStore {
    private val prefs get() = BookloaderApp.instance.getSharedPreferences("jobs", Context.MODE_PRIVATE)

    fun put(id: String, spec: JobSpec) = prefs.edit().putString(id, spec.toJson().toString()).apply()
    fun get(id: String): JobSpec? = prefs.getString(id, null)?.let { runCatching { JobSpec.fromJson(JSONObject(it)) }.getOrNull() }
    fun remove(id: String) {
        prefs.edit().remove(id).apply()
        File(BookloaderApp.instance.cacheDir, "jobs/$id").deleteRecursively()
    }

    fun ids(): Set<String> = prefs.all.keys
}

object Jobs {
    const val TAG_JOB = "thor-job"
    private const val QUEUE = "bookloader-queue"

    /** Downloads run one after another (kind to the servers), even with the app closed. */
    fun enqueue(ctx: Context, spec: JobSpec): String {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(TAG_JOB)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (spec.localPath == null) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .build()
        val id = req.id.toString()
        JobStore.put(id, spec)
        WorkManager.getInstance(ctx).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        Log.i(TAG, "Queued ${spec.action} '${spec.title}' (${spec.sourceId})")
        return id
    }

    /** Temporary files of jobs that no longer exist (e.g. after "Clear finished"). */
    fun cleanOrphans(ctx: Context) {
        val known = JobStore.ids()
        File(ctx.cacheDir, "jobs").listFiles()?.filter { it.name !in known }?.forEach { it.deleteRecursively() }
    }
}

/** Copies a finished file into the "Download to storage" folder (SAF). Returns the saved name. */
object Storage {
    fun save(ctx: Context, file: File, tree: String): String {
        val treeUri = Uri.parse(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val mime = when (file.extension.lowercase()) {
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "cbz" -> "application/vnd.comicbook+zip"
            "cbr" -> "application/vnd.comicbook-rar"
            else -> "application/octet-stream"
        }
        val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, file.name)
            ?: throw IOException("Couldn't create the file in the download folder")
        ctx.contentResolver.openOutputStream(doc, "w")?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
            ?: throw IOException("Couldn't write to the download folder")
        return file.name
    }
}

/**
 * Runs one job: download (or reuse the earlier download), then save it to the folder or import
 * it into Thor Reader. Always "succeeds" as far as WorkManager is concerned (so the rest of the
 * queue keeps going); the real outcome is in the output: status, message, bookId, localPath.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = this.id.toString()
        val spec = JobStore.get(id) ?: return Result.success(out("FAILED", "This download was removed"))
        val app = BookloaderApp.instance
        val dir = File(applicationContext.cacheDir, "jobs/$id").apply { mkdirs() }
        var lastShown = -1
        suspend fun progress(stage: String, p: Float) {
            val pct = (p * 100).toInt()
            if (pct != lastShown) {
                lastShown = pct
                setProgress(workDataOf("stage" to stage, "p" to p))
            }
        }
        return try {
            progress("Downloading", 0f)
            val file = spec.localPath?.let(::File)?.takeIf { it.exists() } ?: run {
                val src = app.sources.get(spec.sourceId) ?: throw IOException("The source '${spec.sourceId}' is gone")
                var last = 0L
                src.download(spec.item, spec.entry, spec.meta, dir) { p ->
                    val now = System.currentTimeMillis()
                    if (now - last > 400) {
                        last = now
                        setProgressAsync(workDataOf("stage" to "Downloading", "p" to p))
                    }
                }
            }
            when (spec.action) {
                JobAction.SAVE -> {
                    val folder = app.downloadFolder ?: return Result.success(out("FAILED", "Choose a download folder first", file.path))
                    progress("Saving", 1f)
                    val name = Storage.save(applicationContext, file, folder)
                    dir.deleteRecursively()
                    Log.i(TAG, "Saved '${spec.title}' as $name")
                    Result.success(out("SAVED", "Saved as $name"))
                }
                JobAction.IMPORT -> {
                    progress("Importing", 1f)
                    val r = ThorImportClient.import(applicationContext, file, spec.meta)
                    Log.i(TAG, "Import '${spec.title}' into Thor Reader: ${r.status}${if (r.message.isNotEmpty()) " (${r.message})" else ""}")
                    if (r.status == ThorStatus.IMPORTED || r.status == ThorStatus.DUPLICATE) {
                        app.markInThorReader(spec.sourceId, spec.item.id)
                        dir.deleteRecursively() // temp cache gone once Thor Reader has its copy
                        Result.success(out(r.status.name, if (r.status == ThorStatus.DUPLICATE) "Already in Thor Reader" else "Added to Thor Reader", bookId = r.bookId))
                    } else {
                        // Keep the file: "Retry" or "Save to storage instead" won't download it again.
                        Result.success(out(r.status.name, r.message, file.path))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Job '${spec.title}' failed", e)
            dir.deleteRecursively()
            Result.success(out("FAILED", e.message ?: e.javaClass.simpleName))
        }
    }

    private fun out(status: String, message: String, localPath: String? = null, bookId: Long = -1) =
        workDataOf("status" to status, "message" to message, "localPath" to localPath, "bookId" to bookId)
}
