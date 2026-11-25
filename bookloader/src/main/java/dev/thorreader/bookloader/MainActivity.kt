package dev.thorreader.bookloader

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.thorreader.bookloader.source.CatalogItem
import dev.thorreader.bookloader.ui.DetailsScreen
import dev.thorreader.bookloader.ui.DetailsState
import dev.thorreader.bookloader.ui.MetaScreen
import dev.thorreader.bookloader.ui.QueueScreen
import dev.thorreader.bookloader.ui.SearchScreen
import dev.thorreader.bookloader.ui.SearchState
import dev.thorreader.bookloader.ui.SourcesScreen
import dev.thorreader.bookloader.work.Jobs
import dev.thorreader.bookloader.work.ThorImportClient

sealed interface Screen {
    data object Search : Screen
    class Details(val state: DetailsState) : Screen
    class Meta(val state: DetailsState) : Screen
    data object Queue : Screen
    data object Sources : Screen
}

/** What screens ask the activity for: navigation and the system pickers. */
interface Host {
    fun go(s: Screen)
    fun back()
    fun openItem(item: CatalogItem)
    /** Choose the "Download to storage" folder, then run [then]. */
    fun pickDownloadFolder(then: () -> Unit = {})
    fun pickJsonFile(onText: (String) -> Unit)
    /** Thor Reader's own folder picker (its Imported books folder). */
    fun chooseThorFolder()
}

val LocalHost = staticCompositionLocalOf<Host> { error("no host") }

/**
 * Thor Bookloader's only window, on the top screen. Screens form a simple stack; Back (or the
 * controller's B) goes back one.
 */
class MainActivity : ComponentActivity(), Host {

    private val stack = mutableStateListOf<Screen>(Screen.Search)
    private val search = SearchState()

    private var afterFolder: (() -> Unit)? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            BookloaderApp.instance.downloadFolder = tree.toString()
            Log.i(TAG, "Download folder chosen")
            afterFolder?.invoke()
        }
        afterFolder = null
    }

    private var onJson: ((String) -> Unit)? = null
    private val jsonPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val cb = onJson
        onJson = null
        if (uri != null && cb != null) {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { dev.thorreader.bookloader.source.Http.readCapped(it, 1L shl 20) }?.toString(Charsets.UTF_8)
            }.getOrNull()
            cb(text ?: "")
        }
    }

    private val thorFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Top screen only: if we were started on the bottom screen, move to the top one.
        if (display?.displayId != Display.DEFAULT_DISPLAY && savedInstanceState == null) {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts.toBundle())
            finish()
            return
        }
        Jobs.cleanOrphans(this)
        setContent {
            CompositionLocalProvider(LocalHost provides this) {
                BackHandler(enabled = stack.size > 1) { back() }
                Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp, vertical = 16.dp)) {
                    when (val s = stack.last()) {
                        Screen.Search -> SearchScreen(search)
                        is Screen.Details -> DetailsScreen(s.state)
                        is Screen.Meta -> MetaScreen(s.state)
                        Screen.Queue -> QueueScreen()
                        Screen.Sources -> SourcesScreen()
                    }
                }
            }
        }
    }

    /** The controller's B button = Back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun go(s: Screen) {
        stack += s
    }

    override fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
    }

    override fun openItem(item: CatalogItem) = go(Screen.Details(DetailsState(item)))

    override fun pickDownloadFolder(then: () -> Unit) {
        afterFolder = then
        folderPicker.launch(null)
    }

    override fun pickJsonFile(onText: (String) -> Unit) {
        onJson = onText
        jsonPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
    }

    override fun chooseThorFolder() {
        runCatching { thorFolder.launch(ThorImportClient.chooseFolderIntent()) }
            .onFailure { Log.w(TAG, "Couldn't open Thor Reader's folder picker: ${it.message}") }
    }
}
