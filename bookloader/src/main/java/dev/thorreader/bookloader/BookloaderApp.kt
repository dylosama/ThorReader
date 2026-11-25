package dev.thorreader.bookloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
