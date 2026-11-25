package dev.thorreader.app.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.MainActivity
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.io.queryFileInfo
import dev.thorreader.app.ui.Accent
import dev.thorreader.app.ui.Chip
import dev.thorreader.app.ui.HouseStyle
import kotlinx.coroutines.launch

/** Keep read + write access to the chosen Imported books folder and remember it. */
fun keepImportFolder(context: Context, tree: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }.onFailure { Log.w(TAG, "Could not keep access to the Imported books folder", it) }
    ThorApp.instance.settings.updateImportFolder(tree.toString())
    Log.i(TAG, "Imported books folder: ${folderLabel(tree.toString())}")
}

/** "Books/Imported" from a tree URI, for display. */
fun folderLabel(tree: String?): String? = tree?.let {
    runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)) }.getOrNull()?.substringAfter(':')?.ifEmpty { "(storage root)" }
}

/**
 * dev.thorreader.action.CHOOSE_IMPORT_FOLDER (signature permission): Thor Bookloader opens this
 * when Thor Reader answered "needs folder". Shows the system folder picker once and finishes.
 */
class ChooseImportFolderActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            keepImportFolder(this, tree)
            setResult(RESULT_OK)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pick.launch(null)
    }
}

/**
 * Files shared with Thor Reader ("Share" / "Open with" from any app): a small house-style
 * screen "Import N books?" with the names, then progress and a result per file. The first time,
 * Import asks for the Imported books folder.
 */
class ShareImportActivity : ComponentActivity() {

    private sealed interface Stage {
        data object Confirm : Stage
        data class Working(val index: Int, val count: Int, val name: String) : Stage
        data class Done(val results: List<IncomingResult>) : Stage
    }

    private var stage by mutableStateOf<Stage>(Stage.Confirm)
    private var uris: List<Uri> = emptyList()
    private var names by mutableStateOf<List<String>>(emptyList())

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            keepImportFolder(this, tree)
            start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = incomingUris(intent)
        names = uris.map { u -> runCatching { queryFileInfo(this, u)?.name }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/') ?: "file" }
        Log.i(TAG, "Share to Thor Reader from ${source()}: ${uris.size} file(s) (${intent.action})")
        if (uris.isEmpty()) {
            finish()
            return
        }
        setContent { Screen() }
    }

    private fun source(): String = referrer?.host ?: callingPackage ?: "another app"

    private fun incomingUris(i: Intent): List<Uri> {
        val clip = i.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        return SharedLinks.pick(
            i.action,
            single = i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java),
            multiple = if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty() else emptyList(),
            data = i.data,
            clip = clip,
            scheme = { it.scheme },
        )
    }

    private fun start() {
        if (ThorApp.instance.settings.importFolder == null) {
            pickFolder.launch(null)
            return
        }
        val from = source()
        stage = Stage.Working(0, uris.size, names.firstOrNull() ?: "")
        ThorApp.instance.scope.launch {
            val results = ThorApp.instance.incoming.importAll(uris.map { IncomingItem(it) }, from) { i, n, name ->
                runOnUiThread { stage = Stage.Working(i, n, name) }
            }
            stage = Stage.Done(results)
        }
    }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(640.dp)
                    .clip(HouseStyle.PanelShape)
                    .background(HouseStyle.PanelBackground)
                    .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val s = stage) {
                    Stage.Confirm -> Confirm()
                    is Stage.Working -> {
                        Title("Importing ${s.index + 1} of ${s.count}")
                        Text(s.name, color = HouseStyle.Subtle, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    is Stage.Done -> Results(s.results)
                }
            }
        }
    }

    @Composable
    private fun Title(t: String) = Text(t, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

    @Composable
    private fun Confirm() {
        val n = names.size
        Title(if (n == 1) "Import 1 book?" else "Import $n books?")
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            items(names) { Text(it, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val folder = folderLabel(ThorApp.instance.settings.importFolder)
        Text(
            if (folder == null) "Thor Reader keeps a copy in a folder you choose next (asked only once)."
            else "A copy is kept in “$folder”.",
            color = HouseStyle.Subtle, fontSize = 13.sp,
        )
        val focus = remember { FocusRequester() }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.focusRequester(focus)) { Chip("Import", true) { start() } }
            Chip("Cancel") { finish() }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    @Composable
    private fun Results(results: List<IncomingResult>) {
        val added = results.count { it.status == ImportStatus.IMPORTED }
        Title(if (added == results.size) "Added $added" else "Added $added of ${results.size}")
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(results) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(r.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(statusText(r), color = if (r.status == ImportStatus.IMPORTED) Accent else HouseStyle.Subtle, fontSize = 14.sp)
                }
            }
        }
        val focus = remember { FocusRequester() }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.focusRequester(focus)) {
                Chip("Open library", true) {
                    startActivity(Intent(this@ShareImportActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }
            Chip("Done") { finish() }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    private fun statusText(r: IncomingResult) = when (r.status) {
        ImportStatus.IMPORTED -> "Added"
        ImportStatus.DUPLICATE -> "Already in library"
        ImportStatus.UNSUPPORTED -> if (r.message.isNotEmpty()) "Not supported (${r.message})" else "Not supported"
        ImportStatus.NEEDS_FOLDER -> "Choose the folder first"
        ImportStatus.FAILED -> "Failed" + if (r.message.isNotEmpty()) ": ${r.message}" else ""
    }
}

/**
 * Which links of a share / open intent we take: SEND = EXTRA_STREAM, SEND_MULTIPLE = the
 * EXTRA_STREAM list, VIEW = the data URI, plus anything in ClipData; content:// only (no
 * file:// paths, no http), no repeats, at most [ImportService.MAX_FILES]. Other actions: none.
 * Generic so it can be unit-tested without Android's Uri.
 */
object SharedLinks {
    fun <U> pick(action: String?, single: U?, multiple: List<U>, data: U?, clip: List<U>, scheme: (U) -> String?): List<U> {
        val out = ArrayList<U>()
        when (action) {
            Intent.ACTION_SEND -> single?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE -> out += multiple
            Intent.ACTION_VIEW -> data?.let { out += it }
            else -> return emptyList()
        }
        out += clip
        return out.filter { scheme(it) == ContentResolver.SCHEME_CONTENT }.distinct().take(ImportService.MAX_FILES)
    }
}
