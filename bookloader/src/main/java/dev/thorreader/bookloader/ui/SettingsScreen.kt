package dev.thorreader.bookloader.ui

import android.content.Intent
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.TAG
import dev.thorreader.bookloader.LocalHost
import dev.thorreader.bookloader.source.ContentType
import dev.thorreader.bookloader.source.Http
import dev.thorreader.bookloader.source.NetConfig
import dev.thorreader.bookloader.source.NetMode
import dev.thorreader.bookloader.source.OpdsSource
import dev.thorreader.bookloader.source.ProxyKind
import dev.thorreader.bookloader.source.SourceProvider
import dev.thorreader.bookloader.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Settings (the gear), laid out like Thor Reader's: Connection, Downloads, Sources, your OPDS
 * feeds, Add a feed, Load a source list, About. [addFeed] (the + button) scrolls to "Add".
 */
@Composable
fun SettingsScreen(addFeed: Boolean) {
    val host = LocalHost.current
    val app = BookloaderApp.instance
    val reg = app.sources
    @Suppress("UNUSED_VARIABLE") val v = reg.version
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

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

    androidx.compose.foundation.layout.Column {
        TopBar {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton()
                BarTitle("Settings")
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 24.dp),
        ) {
            note?.let { (t, ok) -> item { Message(t, if (ok) House.Ok else House.Warning) } }

            item { SectionLabel("Connection") }
            item { ConnectionSection() }

            item { SectionLabel("Sound") }
            item { SoundSection() }

            item { SectionLabel("Downloads") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val label = folder?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':') }.getOrNull() }
                    Column(Modifier.weight(1f)) {
                        Text("Download to storage folder", color = Color.White, fontSize = 15.sp)
                        Text(label ?: "Not chosen yet - asked the first time you download", color = House.Subtle, fontSize = 13.sp)
                    }
                    Chip(if (folder == null) "Choose" else "Change") { host.pickDownloadFolder { folder = app.downloadFolder } }
                }
            }

            item { SectionLabel("Sources") }
            items(reg.builtIn(), key = { it.id }) { src -> SourceRow(src, reg.isEnabled(src.id), onToggle = { reg.setEnabled(src.id, it) }) }

            val feeds = reg.feeds()
            item { SectionLabel("Your OPDS feeds (${feeds.size})") }
            if (feeds.isEmpty()) item { Message("None yet. Add a Komga, Kavita or Calibre-web server, or any public OPDS catalogue.", House.Faint) }
            items(feeds, key = { it.id }) { f ->
                val src = reg.all().firstOrNull { it.id == f.id } ?: OpdsSource(f)
                SourceRow(src, reg.isEnabled(f.id), onToggle = { reg.setEnabled(f.id, it) }, onRemove = { reg.removeFeed(f.id) })
            }

            item(key = "add") { SectionLabel("Add an OPDS feed") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(name, { name = it }, "Name (optional)", Modifier.width(220.dp))
                            Field(url, { url = it }, "https://… (OPDS 1.2 or 2.0 address)", Modifier.weight(1f), keyboard = KeyboardType.Uri, autoFocus = addFeed)
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
                                    note = "Added \"${f.name}\" - it's in the source row on the main screen" to true
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

            item { SectionLabel("About") }
            item {
                val version = remember { runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?" }
                Text("Thor Bookloader $version", color = Color.White, fontSize = 15.sp)
                Text(
                    "Companion of Thor Reader. Finds public-domain books (Project Gutenberg), MangaDex chapters and your own OPDS catalogues, " +
                        "and sends them to Thor Reader. No accounts, no tracking; https only.",
                    color = House.Subtle, fontSize = 13.sp,
                )
            }
        }
    }
    // "+" in the pill: jump to "Add an OPDS feed".
    LaunchedEffect(addFeed) {
        // Items above "Add an OPDS feed": note?, Connection (2), Sound (2), Downloads (2), Sources label + rows,
        // feeds label + rows (or the "None yet" line).
        if (addFeed) list.scrollToItem((if (note != null) 1 else 0) + 7 + reg.builtIn().size + 1 + maxOf(reg.feeds().size, 1))
    }
}

/**
 * Connection: Direct, Tor through Orbot, or your own proxy. Whatever is chosen carries every
 * request; a protected route that is down stops requests instead of sending them directly.
 */
@Composable
private fun ConnectionSection() {
    val app = BookloaderApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val net = app.net
    var host by remember { mutableStateOf(net.host) }
    var port by remember { mutableStateOf(if (net.port > 0) net.port.toString() else "") }
    var test by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val orbot = remember { runCatching { ctx.packageManager.getPackageInfo(NetConfig.ORBOT_PACKAGE, 0) }.isSuccess }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NetMode.entries.forEach { m ->
                Chip(m.label, net.mode == m) {
                    test = null
                    app.updateNet(net.copy(mode = m))
                }
            }
        }
        Text(
            when (net.mode) {
                NetMode.DIRECT -> "Sites see this device's internet address. Choose Tor or your proxy to hide it."
                NetMode.TOR -> "Everything Bookloader fetches goes through Tor (Orbot must be running). Sites see a Tor address, not yours; " +
                    "your internet provider sees Tor, not the sites. If Tor is off, nothing is sent at all. Tor is slower, and some sites limit it."
                NetMode.PROXY -> "Everything Bookloader fetches goes through your proxy (for example your VPN provider's SOCKS5 proxy). " +
                    "If it can't be reached, nothing is sent at all."
            },
            color = House.Subtle, fontSize = 13.sp,
        )
        if (net.mode == NetMode.TOR) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (orbot) {
                    Chip("Open Orbot") {
                        ctx.packageManager.getLaunchIntentForPackage(NetConfig.ORBOT_PACKAGE)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    Text("Start Orbot, then Test.", color = House.Faint, fontSize = 13.sp)
                } else {
                    Chip("Get Orbot") {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${NetConfig.ORBOT_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://orbot.app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    Text("Orbot (by the Tor Project) isn't installed.", color = House.Warning, fontSize = 13.sp)
                }
            }
        }
        if (net.mode == NetMode.PROXY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProxyKind.entries.forEach { k -> Chip(if (k == ProxyKind.SOCKS) "SOCKS5" else "HTTP", net.kind == k) { app.updateNet(net.copy(kind = k)) } }
                Field(host, { host = it }, "Proxy address", Modifier.weight(1f), keyboard = KeyboardType.Uri)
                Field(port, { port = it.filter(Char::isDigit).take(5) }, "Port", Modifier.width(110.dp), keyboard = KeyboardType.Number)
                Chip("Save", selected = true) {
                    val c = net.copy(host = host.trim(), port = port.toIntOrNull() ?: 0)
                    val bad = c.problem()
                    if (bad != null) test = bad to false else { app.updateNet(c); test = "Saved" to true }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Test connection") {
                test = "Testing…" to true
                scope.launch {
                    test = try {
                        val o = JSONObject(withContext(Dispatchers.IO) { Http.text(NetConfig.CHECK_URL).first })
                        val tor = o.optBoolean("IsTor")
                        "Sites see ${o.optString("IP")}" + (if (tor) " - a Tor address ✓" else if (net.mode == NetMode.TOR) " - NOT Tor" else "") to (tor || net.mode != NetMode.TOR)
                    } catch (e: Exception) {
                        (e.message ?: "Couldn't connect") to false
                    }
                }
            }
            test?.let { (t, ok) -> Text(t, color = if (ok) House.Ok else House.Warning, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Text("Test asks check.torproject.org which address it sees - only when you tap it.", color = House.Faint, fontSize = 12.sp)
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
            Text(src.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(src.types.joinToString { it.label } + "  ·  " + src.description, color = House.Faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chip(if (on) "On" else "Off", selected = on) { onToggle(!on) }
        onRemove?.let { Chip("Remove") { it() } }
    }
}

/** Music on / off, its volume (d-pad left / right too), and the "sent" sound effects. */
@Composable
private fun SoundSection() {
    val app = BookloaderApp.instance
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Music", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(150.dp))
            Chip("On", app.musicOn) { dev.thorreader.bookloader.music.Music.setEnabled(true) }
            Chip("Off", !app.musicOn) { dev.thorreader.bookloader.music.Music.setEnabled(false) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Music volume", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(150.dp))
            fun set(v: Float) {
                app.updateMusicVolume(Math.round(v * 20) / 20f) // 5 % steps
                dev.thorreader.bookloader.music.Music.volumeChanged()
            }
            androidx.compose.material3.Slider(
                value = app.musicVolume,
                onValueChange = ::set,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = House.Accent, activeTrackColor = House.Accent, inactiveTrackColor = House.Chip,
                ),
                modifier = Modifier
                    .weight(1f)
                    .onKeyEventLeftRight { dir -> set(app.musicVolume + dir * 0.05f) },
            )
            Text("${(app.musicVolume * 100).toInt()} %", color = House.Subtle, fontSize = 14.sp, modifier = Modifier.width(56.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Send sound effects", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(150.dp))
            Chip("On", app.sfxOn) { app.updateSfxOn(true) }
            Chip("Off", !app.sfxOn) { app.updateSfxOn(false) }
        }
        Text(
            "Original music, composed in code and played by Bookloader's own little synthesizer. " +
                "A bloop when a book has been sent, a chime when the queue is done. Music pauses for calls and other apps' audio, and whenever Bookloader isn't on screen.",
            color = House.Faint, fontSize = 12.sp,
        )
    }
}

/** D-pad left / right on a focused slider change it ([dir] = -1 / +1). */
private fun Modifier.onKeyEventLeftRight(change: (Int) -> Unit): Modifier = this.then(
    Modifier.onPreviewKeyEvent {
        if (it.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (it.key) {
            androidx.compose.ui.input.key.Key.DirectionLeft -> { change(-1); true }
            androidx.compose.ui.input.key.Key.DirectionRight -> { change(1); true }
            else -> false
        }
    },
)
