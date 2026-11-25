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
        musicOn = prefs.getBoolean("music_on", true)
        musicVolume = prefs.getFloat("music_volume", 0.4f)
        sfxOn = prefs.getBoolean("sfx_on", true)
    }

    // ---- typing on the bottom screen (as in Thor Reader) ------------------------------------

    /** The Thor's bottom screen (display 4), if this device has a second screen. */
    fun bottomDisplay(): android.view.Display? {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        return dm.getDisplay(4) ?: dm.displays.firstOrNull { it.displayId != android.view.Display.DEFAULT_DISPLAY }
    }

    /** The field being edited, or null. While set, the bottom screen shows the keyboard. */
    var typing by mutableStateOf<dev.thorreader.bookloader.ui.TypingSession?>(null)
        private set

    fun startTyping(s: dev.thorreader.bookloader.ui.TypingSession) {
        if (typing != null) stopTyping("another field")
        typing = s
        android.util.Log.i(TAG, "Typing started (\"${s.hint}\")")
    }

    fun stopTyping(reason: String) {
        val s = typing ?: return
        typing = null
        android.util.Log.i(TAG, "Typing ended ($reason)")
        s.onDone()
    }

    /** A light tick on key presses. */
    fun tick() {
        runCatching {
            val v = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
            if (v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
        }
    }

    /**
     * Keys while typing: d-pad moves the key highlight, A types it, B = backspace (empty field:
     * done), Start = Done; a physical keyboard types directly. True = handled.
     */
    fun handleTypingKey(e: android.view.KeyEvent): Boolean {
        val s = typing ?: return false
        val code = e.keyCode
        if (e.action != android.view.KeyEvent.ACTION_DOWN) {
            return code in setOf(
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_BUTTON_B,
                android.view.KeyEvent.KEYCODE_BUTTON_START, android.view.KeyEvent.KEYCODE_MENU, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_BACK,
            ) || e.unicodeChar != 0
        }
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> s.moveHighlight(-1, 0)
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> s.moveHighlight(1, 0)
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> s.moveHighlight(0, -1)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> s.moveHighlight(0, 1)
            android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                val k = s.highlighted() ?: return true
                tick()
                if (!s.press(k)) stopTyping("Done (A)")
            }
            android.view.KeyEvent.KEYCODE_BUTTON_B -> if (s.text.isEmpty()) stopTyping("B on an empty field") else s.backspace()
            android.view.KeyEvent.KEYCODE_BUTTON_START, android.view.KeyEvent.KEYCODE_MENU -> stopTyping("Start")
            android.view.KeyEvent.KEYCODE_DEL -> s.backspace()
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> stopTyping("Enter")
            android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_BACK -> stopTyping("Back")
            else -> {
                val ch = e.unicodeChar
                if (ch == 0) return false
                s.insert(String(Character.toChars(ch)))
            }
        }
        return true
    }

    /** Background music on (♪ button / Settings → Sound). Remembered. */
    var musicOn by mutableStateOf(true)
        private set

    fun updateMusicOn(on: Boolean) {
        musicOn = on
        prefs.edit().putBoolean("music_on", on).apply()
    }

    /** Music volume, 0..1 of the media volume (default 40 %). */
    var musicVolume by mutableStateOf(0.4f)
        private set

    fun updateMusicVolume(v: Float) {
        musicVolume = v.coerceIn(0f, 1f)
        prefs.edit().putFloat("music_volume", musicVolume).apply()
    }

    /** "Send sound effects": the bloop per book sent and the chime when the queue is done. */
    var sfxOn by mutableStateOf(true)
        private set

    fun updateSfxOn(on: Boolean) {
        sfxOn = on
        prefs.edit().putBoolean("sfx_on", on).apply()
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
