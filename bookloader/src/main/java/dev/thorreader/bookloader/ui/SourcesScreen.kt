package dev.thorreader.bookloader.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.LocalHost
import dev.thorreader.bookloader.TAG
import dev.thorreader.bookloader.source.ContentType
import dev.thorreader.bookloader.source.Http
import dev.thorreader.bookloader.source.OpdsSource
import dev.thorreader.bookloader.source.SourceProvider
import dev.thorreader.bookloader.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sources: switch built-ins on / off, add or remove OPDS feeds, load a JSON source list; the download folder. */
@Composable
fun SourcesScreen() {
    val host = LocalHost.current
    val app = BookloaderApp.instance
    val reg = app.sources
    @Suppress("UNUSED_VARIABLE") val v = reg.version
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ContentType.COMIC) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var listUrl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // message, ok?
    var folder by remember { mutableStateOf(app.downloadFolder) }

    fun loadList(json: String) {
        try {
            val (feeds, skipped) = SourceRegistry.parseSourceList(json)
            feeds.forEach(reg::addFeed)
            note = "Added ${feeds.size} feed(s)" + (if (skipped > 0) ", skipped $skipped (only https OPDS feeds can be added)" else "") to true
        } catch (e: Exception) {
            note = "That isn't a source list (${e.message})" to false
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("← Back") { host.back() }
                Text("Sources", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        note?.let { (t, ok) -> item { Message(t, if (ok) House.Ok else House.Warning) } }

        item { SectionLabel("Download to storage") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = folder?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':') }.getOrNull() }
                Text(label?.let { "Folder: $it" } ?: "No folder chosen yet (asked the first time you download)", color = House.Subtle, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Chip(if (folder == null) "Choose" else "Change") { host.pickDownloadFolder { folder = app.downloadFolder } }
            }
        }

        item { SectionLabel("Built in") }
        items(reg.builtIn(), key = { it.id }) { src -> SourceRow(src, reg.isEnabled(src.id), onToggle = { reg.setEnabled(src.id, it) }) }

        val feeds = reg.feeds()
        item { SectionLabel("Your OPDS feeds (${feeds.size})") }
        if (feeds.isEmpty()) item { Message("None yet. Add a Komga, Kavita or Calibre-web server, or any public OPDS catalogue.", House.Faint) }
        items(feeds, key = { it.id }) { f ->
            val src = reg.all().firstOrNull { it.id == f.id } ?: OpdsSource(f)
            SourceRow(src, reg.isEnabled(f.id), onToggle = { reg.setEnabled(f.id, it) }, onRemove = { reg.removeFeed(f.id) })
        }

        item { SectionLabel("Add an OPDS feed") }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(name, { name = it }, "Name (optional)", Modifier.width(240.dp))
                        Field(url, { url = it }, "https://… (OPDS 1.2 or 2.0 address)", Modifier.weight(1f), keyboard = KeyboardType.Uri)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Holds", color = House.Faint, fontSize = 13.sp)
                        ContentType.entries.forEach { t -> Chip(t.label, type == t) { type = t } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(user, { user = it }, "User name (if the server asks)", Modifier.weight(1f))
                        Field(pass, { pass = it }, "Password", Modifier.weight(1f), password = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip("Add feed", selected = true) {
                            val bad = SourceRegistry.checkFeedUrl(url)
                            if (bad != null) {
                                note = bad to false
                            } else {
                                val f = SourceRegistry.newFeed(name, url, type, user, pass)
                                reg.addFeed(f)
                                Log.i(TAG, "OPDS feed added: ${f.name}")
                                note = "Added \"${f.name}\" - pick it under Source on the search screen" to true
                                name = ""; url = ""; user = ""; pass = ""
                            }
                        }
                        Text("The password is only sent to that server, over https.", color = House.Faint, fontSize = 12.sp)
                    }
                }
            }
        }

        item { SectionLabel("Load a JSON source list") }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A list of OPDS feeds: {\"sources\": [{\"type\": \"opds\", \"name\": \"…\", \"url\": \"https://…\", \"contentType\": \"comics\"}]}. Only addresses are loaded, never code.",
                        color = House.Faint, fontSize = 13.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field(listUrl, { listUrl = it }, "https://… (address of the list)", Modifier.weight(1f), keyboard = KeyboardType.Uri)
                        Chip("Load") {
                            val bad = SourceRegistry.checkFeedUrl(listUrl)
                            if (bad != null) {
                                note = bad to false
                            } else {
                                scope.launch {
                                    val text = runCatching { withContext(Dispatchers.IO) { Http.text(listUrl.trim()).first } }
                                    text.onSuccess(::loadList).onFailure { note = "Couldn't load the list: ${it.message}" to false }
                                }
                            }
                        }
                        Chip("From a file…") { host.pickJsonFile(::loadList) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(src: SourceProvider, on: Boolean, onToggle: (Boolean) -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(src.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(src.types.joinToString { it.label } + "  ·  " + src.description, color = House.Faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chip(if (on) "On" else "Off", selected = on) { onToggle(!on) }
        onRemove?.let { Chip("Remove") { it() } }
    }
}
