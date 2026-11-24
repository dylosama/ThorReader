package dev.thorreader.app

import android.util.Log
import android.view.WindowManager

/**
 * Lives on the TOP screen (display 0). Hosts modes 1, 2 and 3.
 * Modes 1/2 also show the bottom screen through a Presentation; mode 3 releases the bottom
 * screen completely. For mode 4 it hands over to [BottomActivity] on the bottom display and
 * finishes, so the top screen is free — or, if Android refuses, falls back to a black top
 * screen with the reader on a Presentation.
 */
class MainActivity : HostActivity() {

    override val screen = Screen.TOP
    private var presentation: BottomScreenPresentation? = null

    override fun reconcile() {
        val mode = app.settings.mode
        if (mode == ReadingMode.BOTTOM && !app.bottomFallback) {
            val bottom = app.bottomDisplay()
            if (bottom != null && launchOnDisplay(BottomActivity::class.java, bottom.displayId)) {
                dismissPresentation()
                finish()
                return
            }
            Log.w(TAG, "Could not move the reader to the bottom display; using fallback (black top screen + Presentation)")
            app.bottomFallback = true // triggers reconcile() again
            return
        }
        val needBottom = mode.isBook || mode == ReadingMode.BOTTOM // BOTTOM here = fallback
        if (needBottom) showPresentation() else dismissPresentation()
    }

    private fun showPresentation() {
        val display = app.bottomDisplay() ?: run {
            Log.w(TAG, "No bottom display found")
            return
        }
        val current = presentation
        if (current != null && current.isShowing && current.display.displayId == display.displayId) return
        dismissPresentation()
        try {
            presentation = BottomScreenPresentation(this, display).also { it.show() }
            Log.i(TAG, "Bottom screen: Presentation shown on display ${display.displayId}")
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "Could not show Presentation on display ${display.displayId}", e)
            presentation = null
        }
    }

    private fun dismissPresentation() {
        presentation?.let {
            it.dismiss()
            Log.i(TAG, "Bottom screen released (Presentation dismissed)")
        }
        presentation = null
    }

    override fun onStop() {
        dismissPresentation()
        super.onStop()
    }
}

/**
 * Lives on the BOTTOM screen (display 4). Hosts mode 4 only; when the mode changes it starts
 * [MainActivity] back on the top screen and finishes.
 */
class BottomActivity : HostActivity() {

    override val screen = Screen.BOTTOM

    override fun reconcile() {
        if (app.settings.mode == ReadingMode.BOTTOM) return
        if (!launchOnDisplay(MainActivity::class.java, android.view.Display.DEFAULT_DISPLAY)) {
            startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
