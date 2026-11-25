package dev.thorreader.bookloader.work

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.content.FileProvider
import dev.thorreader.bookloader.TAG
import dev.thorreader.bookloader.ThorImportContract as C
import dev.thorreader.bookloader.source.BookMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

enum class ThorStatus { IMPORTED, DUPLICATE, UNSUPPORTED, FAILED, NEEDS_FOLDER, NOT_INSTALLED, NO_PERMISSION }

data class ThorResult(val status: ThorStatus, val bookId: Long = -1, val message: String = "")

/**
 * Sends a downloaded file to Thor Reader through its trusted import API: bind to the
 * signature-protected service, grant read access to just this file (through our FileProvider),
 * send it with its metadata, wait for the per-file result, revoke the access again.
 */
object ThorImportClient {
    const val AUTHORITY = "dev.thorreader.bookloader.files"

    fun installed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(C.THOR_PACKAGE, 0) }.isSuccess

    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(C.PERMISSION_IMPORT) == PackageManager.PERMISSION_GRANTED

    /** The metadata as the API's Bundle. */
    fun metaBundle(m: BookMeta): Bundle = Bundle().apply {
        m.title?.let { putString(C.M_TITLE, it) }
        if (m.authors.isNotEmpty()) putStringArray(C.M_AUTHORS, m.authors.toTypedArray())
        m.series?.let { putString(C.M_SERIES, it) }
        m.seriesNumber?.let { putFloat(C.M_SERIES_NUMBER, it) }
        m.type?.let { putString(C.M_TYPE, it.apiName) }
        if (m.genres.isNotEmpty()) putStringArray(C.M_GENRES, m.genres.toTypedArray())
        m.rtl?.let { putString(C.M_DIRECTION, if (it) "rtl" else "ltr") }
        m.mode?.let { putInt(C.M_MODE, it) }
    }

    suspend fun import(context: Context, file: File, meta: BookMeta, onProgress: () -> Unit = {}): ThorResult {
        val ctx = context.applicationContext
        if (!installed(ctx)) return ThorResult(ThorStatus.NOT_INSTALLED, message = "Thor Reader isn't installed")
        if (!permitted(ctx)) {
            return ThorResult(
                ThorStatus.NO_PERMISSION,
                message = "Thor Reader didn't allow the import (different signing key, or Bookloader was installed before Thor Reader - reinstall Bookloader)",
            )
        }
        val uri: Uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        ctx.grantUriPermission(C.THOR_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var conn: ServiceConnection? = null
        try {
            val binder = suspendCancellableCoroutine<IBinder?> { cont ->
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (cont.isActive) cont.resume(service)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                    override fun onNullBinding(name: ComponentName?) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                conn = c
                val ok = try {
                    ctx.bindService(Intent(C.ACTION_IMPORT_BOOKS).setPackage(C.THOR_PACKAGE), c, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Thor Reader refused the bind: ${e.message}")
                    false
                }
                if (!ok && cont.isActive) cont.resume(null)
            } ?: return ThorResult(ThorStatus.NO_PERMISSION, message = "Thor Reader's import service refused the connection")

            val answer = CompletableDeferred<Bundle?>()
            val handler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        C.MSG_PROGRESS -> onProgress()
                        C.MSG_RESULT -> answer.complete(msg.data)
                    }
                }
            }
            val request = Message.obtain(null, C.MSG_IMPORT).apply {
                replyTo = Messenger(handler)
                data = Bundle().apply {
                    putParcelableArrayList(C.EXTRA_URIS, arrayListOf(uri))
                    putParcelableArrayList(C.EXTRA_METAS, arrayListOf(metaBundle(meta)))
                }
            }
            Messenger(binder).send(request)
            val reply = withTimeoutOrNull(10 * 60_000L) { answer.await() }
                ?: return ThorResult(ThorStatus.FAILED, message = "Thor Reader didn't answer in time")
            return parse(reply)
        } catch (e: Exception) {
            Log.w(TAG, "Import into Thor Reader failed", e)
            return ThorResult(ThorStatus.FAILED, message = e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.let { runCatching { ctx.unbindService(it) } }
            ctx.revokeUriPermission(C.THOR_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun parse(reply: Bundle): ThorResult {
        reply.classLoader = Uri::class.java.classLoader
        val r = reply.getParcelableArrayList(C.EXTRA_RESULTS, Bundle::class.java)?.firstOrNull()
            ?: return ThorResult(ThorStatus.NO_PERMISSION, message = reply.getString(C.R_MESSAGE) ?: "Thor Reader refused the import")
        val status = runCatching { ThorStatus.valueOf(r.getString(C.R_STATUS) ?: "") }.getOrDefault(ThorStatus.FAILED)
        return ThorResult(status, r.getLong(C.R_BOOK_ID, -1), r.getString(C.R_MESSAGE).orEmpty())
    }

    /** Opens Thor Reader's folder picker (signature-protected activity). */
    fun chooseFolderIntent(): Intent = Intent(C.ACTION_CHOOSE_FOLDER).setPackage(C.THOR_PACKAGE)
}
