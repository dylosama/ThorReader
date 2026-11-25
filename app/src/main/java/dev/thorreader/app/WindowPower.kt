package dev.thorreader.app

import android.util.Log
import android.view.Display
import android.view.Window
import android.view.WindowManager
import kotlin.math.abs

/**
 * Ask the display for 60 Hz instead of its 120 Hz default. A reader shows still pages; at
 * 120 Hz the panel and GPU work twice as often for nothing (and short animations still look
 * smooth at 60 Hz). Android may still pick another rate if something else needs it.
 */
fun Window.prefer60Hz(display: Display?) {
    val d = display ?: return
    val cur = d.mode
    val mode = d.supportedModes
        .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        .minByOrNull { abs(it.refreshRate - 60f) } ?: return
    attributes = attributes.apply {
        preferredDisplayModeId = mode.modeId
        preferredRefreshRate = mode.refreshRate
    }
    Log.i(TAG, "Display ${d.displayId}: asking for ${"%.0f".format(mode.refreshRate)} Hz (mode ${mode.modeId}; was ${"%.0f".format(cur.refreshRate)} Hz)")
}

/** Keep the screen on only while [on] (see ThorApp.keepScreenOn). */
fun Window.keepScreenOn(on: Boolean) {
    if (on) addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
