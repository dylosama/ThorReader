package dev.thorreader.bookloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.thorreader.bookloader.source.Http
import dev.thorreader.bookloader.source.NetConfig
import dev.thorreader.bookloader.source.NetMode
import dev.thorreader.bookloader.source.ProxyKind
import dev.thorreader.bookloader.source.SourceRegistry

const val TAG = "ThorBookloader"

class BookloaderApp : Application() {
    lateinit var prefs: SharedPreferences
        private set
    lateinit var sources: SourceRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("bookloader", Context.MODE_PRIVATE)
        sources = SourceRegistry(prefs)
        net = NetConfig(
            mode = runCatching { NetMode.valueOf(prefs.getString("net_mode", "DIRECT")!!) }.getOrDefault(NetMode.DIRECT),
            kind = runCatching { ProxyKind.valueOf(prefs.getString("proxy_kind", "SOCKS")!!) }.getOrDefault(ProxyKind.SOCKS),
            host = prefs.getString("proxy_host", "")!!,
            port = prefs.getInt("proxy_port", 0),
        )
        Http.route = net
    }

    /** Connection route (Direct / Tor / proxy). Changing it applies to every later request at once. */
    var net by mutableStateOf(NetConfig())
        private set

    fun updateNet(c: NetConfig) {
        net = c
        Http.route = c
        prefs.edit().putString("net_mode", c.mode.name).putString("proxy_kind", c.kind.name)
            .putString("proxy_host", c.host).putInt("proxy_port", c.port).apply()
        android.util.Log.i(TAG, "Connection: ${c.mode.label}")
    }

    /** SAF tree for "Download to storage"; null = not chosen yet. */
    var downloadFolder: String?
        get() = prefs.getString("download_folder", null)
        set(v) = prefs.edit().putString("download_folder", v).apply()

    /** Items already sent to Thor Reader ("sourceId|itemId"), for the "In Thor Reader" badge. */
    fun inThorReader(sourceId: String, itemId: String) = "$sourceId|$itemId" in prefs.getStringSet("in_thor", emptySet())!!

    fun markInThorReader(sourceId: String, itemId: String) {
        val s = HashSet(prefs.getStringSet("in_thor", emptySet())!!)
        s += "$sourceId|$itemId"
        prefs.edit().putStringSet("in_thor", s).apply()
    }

    companion object {
        lateinit var instance: BookloaderApp
            private set
    }
}
