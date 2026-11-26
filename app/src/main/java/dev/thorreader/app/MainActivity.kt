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
    private var bottomLaunched = false

    override fun reconcile() {
        val mode = app.settings.mode
        if (mode == ReadingMode.BOTTOM && !app.bottomFallback) {
            if (bottomLaunched) {
                // The book opening into Mode 4 still slides off this screen: finish once it's done.
                if (app.bookAnim == null) finish()
                return
            }
            val bottom = app.bottomDisplay()
            if (bottom != null && launchOnDisplay(BottomActivity::class.java, bottom.displayId)) {
                dismissPresentation()
                dismissInspect() // opened from Inspect: the reader has the bottom screen now

                bottomLaunched = true
                if (app.bookAnim == null) finish()
                return
            }
            Log.w(TAG, "Could not move the reader to the bottom display; using fallback (black top screen + Presentation)")
            app.bottomFallback = true // triggers reconcile() again
            return
        }
        bottomLaunched = false
        // BOTTOM here = fallback. A book closing from Modes 1/2 keeps the bottom screen until done.
        val needBottom = mode.isBook || mode == ReadingMode.BOTTOM || app.bookAnim?.let { it.mode.isBook && !it.open } == true
        if (needBottom) showPresentation() else dismissPresentation()
        // Typing in the library (Mode 3): the keyboard takes over the bottom screen, only while typing.
        val t = app.typing
        if (t != null && mode == ReadingMode.TOP && app.session == null) showTyping(t) else dismissTyping()
        // Inspect book: the book's details take over the bottom screen, only while inspecting.
        val ins = app.inspecting
        if (ins != null && mode == ReadingMode.TOP && app.session == null && t == null) {
            showInspect(ins)
        } else {
            // A book opened from Inspect: keep Inspect on the bottom screen until the reader's
            // window there has drawn its first frame, else the launcher shows through for a frame.
            val p = presentation
            if (inspectPresentation != null && p != null && !p.drawn && !handoverTimedOut) {
                if (!waitingForReader) {
                    waitingForReader = true
                    p.afterFirstFrame { finishInspectHandover() }
                    window.decorView.postDelayed({ handoverTimedOut = true; finishInspectHandover() }, 300) // never wait longer
                }
            } else {
                dismissInspect()
            }
        }
    }

    private var waitingForReader = false
    private var handoverTimedOut = false

    private fun finishInspectHandover() {
        if (!waitingForReader && !handoverTimedOut) return
        waitingForReader = false
        reconcile() // still right to close Inspect? (it re-checks everything)
    }

    private var inspectPresentation: InspectPresentation? = null

    private fun showInspect(s: dev.thorreader.app.ui.InspectState) {
        // Already showing: it follows app.inspecting itself (no blink when switching books).
        if (inspectPresentation?.isShowing == true) return
        dismissInspect()
        val display = app.bottomDisplay() ?: return
        try {
            inspectPresentation = InspectPresentation(this, display, s).also { it.show() }
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "Could not show the book details on display ${display.displayId}", e)
        }
    }

    private fun dismissInspect() {
        waitingForReader = false
        handoverTimedOut = false
        inspectPresentation?.let {
            it.dismiss()
            Log.i(TAG, "Bottom screen released (book details closed)")
        }
        inspectPresentation = null
    }

    private var typingPresentation: TypingPresentation? = null
    private var typingFor: dev.thorreader.app.ui.TypingSession? = null

    private fun showTyping(s: dev.thorreader.app.ui.TypingSession) {
        if (typingPresentation?.isShowing == true && typingFor === s) return
        dismissTyping()
        val display = app.bottomDisplay() ?: return
        try {
            typingPresentation = TypingPresentation(this, display, s).also { it.show() }
            typingFor = s
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "Could not show the keyboard on display ${display.displayId}", e)
        }
    }

    private fun dismissTyping() {
        typingPresentation?.let {
            it.dismiss()
            Log.i(TAG, "Bottom screen released (keyboard closed)")
        }
        typingPresentation = null
        typingFor = null
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
        app.stopTyping("app hidden")
        app.inspecting?.let { app.endInspect(it) } // no frames while hidden: end at once
        dismissInspect()
        dismissTyping()
        super.onStop()
    }
}

/**
 * Lives on the BOTTOM screen (display 4). Hosts mode 4 only; when the mode changes it starts
 * [MainActivity] back on the top screen and finishes.
 */
class BottomActivity : HostActivity() {

    override val screen = Screen.BOTTOM

    private var topLaunched = false

    override fun reconcile() {
        if (app.settings.mode == ReadingMode.BOTTOM) {
            topLaunched = false
            return
        }
        if (!topLaunched) {
            if (!launchOnDisplay(MainActivity::class.java, android.view.Display.DEFAULT_DISPLAY)) {
                startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            topLaunched = true
        }
        // A book closing from Mode 4 still slides up off this screen: finish once it's done.
        if (app.bookAnim?.uses(Screen.BOTTOM) == true) return
        finish()
    }
}
