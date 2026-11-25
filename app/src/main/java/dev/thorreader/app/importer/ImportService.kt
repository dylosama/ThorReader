package dev.thorreader.app.importer

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The trusted import API ([ImportContract]) for Thor Bookloader. The system only lets apps that
 * hold dev.thorreader.permission.IMPORT bind (signature level = signed with our key); every
 * message is checked again (permission AND same signature), in case the binder leaks. Imports
 * without a confirm screen and replies with one result per file.
 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == ImportContract.MSG_IMPORT) handleImport(msg)
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == ImportContract.ACTION_IMPORT_BOOKS) messenger.binder else null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The calling app, if it is allowed: holds the permission and is signed like us. */
    private fun trustedCaller(uid: Int): String? {
        val pm = packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return null
        return pkgs.firstOrNull { p ->
            pm.checkPermission(ImportContract.PERMISSION_IMPORT, p) == PackageManager.PERMISSION_GRANTED &&
                pm.checkSignatures(packageName, p) == PackageManager.SIGNATURE_MATCH
        }
    }

    private fun handleImport(msg: Message) {
        val reply = msg.replyTo ?: return
        val from = trustedCaller(msg.sendingUid)
        if (from == null) {
            Log.w(TAG, "Import API: refused uid ${msg.sendingUid} (no permission or different signature)")
            send(reply, ImportContract.MSG_RESULT, Bundle().apply { putString(ImportContract.R_MESSAGE, "Not allowed") })
            return
        }
        val data = msg.data.apply { classLoader = Uri::class.java.classLoader }
        val uris = data.getParcelableArrayList(ImportContract.EXTRA_URIS, Uri::class.java).orEmpty().take(MAX_FILES)
        val metas = data.getParcelableArrayList(ImportContract.EXTRA_METAS, Bundle::class.java).orEmpty()
        val items = uris.mapIndexed { i, u ->
            @Suppress("DEPRECATION")
            val meta = metas.getOrNull(i)?.let { b -> IncomingMeta.parse { k -> b.get(k) } } ?: IncomingMeta()
            IncomingItem(u, meta)
        }
        Log.i(TAG, "Import API: ${items.size} file(s) from $from")
        scope.launch {
            val results = ThorApp.instance.incoming.importAll(items, from) { i, n, _ ->
                handler.post { send(reply, ImportContract.MSG_PROGRESS, null, i, n) }
            }
            val out = ArrayList(results.map { r ->
                Bundle().apply {
                    putParcelable(ImportContract.R_URI, r.uri)
                    putString(ImportContract.R_STATUS, r.status.name)
                    putLong(ImportContract.R_BOOK_ID, r.bookId)
                    putString(ImportContract.R_MESSAGE, r.message)
                }
            })
            send(reply, ImportContract.MSG_RESULT, Bundle().apply { putParcelableArrayList(ImportContract.EXTRA_RESULTS, out) })
        }
    }

    private fun send(to: Messenger, what: Int, data: Bundle?, arg1: Int = 0, arg2: Int = 0) {
        runCatching {
            to.send(Message.obtain(null, what, arg1, arg2).also { m -> data?.let { m.data = it } })
        }.onFailure { Log.w(TAG, "Import API: the caller went away (${it.javaClass.simpleName})") }
    }

    companion object {
        const val MAX_FILES = 200
    }
}
