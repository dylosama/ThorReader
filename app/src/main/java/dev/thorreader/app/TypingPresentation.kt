package dev.thorreader.app

import android.app.Presentation
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.thorreader.app.ui.BottomKeyboard
import dev.thorreader.app.ui.TypingSession

/**
 * The bottom screen while a text field in the library (Mode 3, top screen) is being edited: the
 * built-in keyboard. Exists only while typing; dismissed when typing ends, so the bottom screen
 * is free again for other apps.
 *
 * Why not the system keyboard? Tried first (approach A): on the Thor, Android refuses to open
 * it for a window on the bottom screen ("Ignoring showSoftInput() ... is not served") - it only
 * serves the focused top-screen app, where it would cover the list. So the app draws its own.
 */
class TypingPresentation(
    private val activity: HostActivity,
    display: Display,
    private val session: TypingSession,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { dev.thorreader.app.ui.BurnInGuard { BottomKeyboard(session) } }
        }
        setContentView(view)
        window?.let { w ->
            w.decorView.setViewTreeLifecycleOwner(activity)
            w.decorView.setViewTreeSavedStateRegistryOwner(activity)
            w.decorView.setViewTreeViewModelStoreOwner(activity)
            w.setBackgroundDrawableResource(android.R.color.black)
            w.prefer60Hz(display)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        Log.i(TAG, "Keyboard shown on the bottom screen (display ${display.displayId})")
    }

    /** The Thor's buttons and physical keyboards while this screen has focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ThorApp.instance.screenSaverTakesKey(event)) return true // black screens: only wakes them
        ThorApp.instance.userActive()
        if (ThorApp.instance.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ThorApp.instance.screenSaverTakesTouch(ev)) return true
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) ThorApp.instance.userActive()
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * The bottom screen while a book is inspected (Mode 3 library): its details, description and
 * "Read". Exists only while inspecting; dismissed afterwards so the bottom screen is free again.
 */
class InspectPresentation(
    private val activity: HostActivity,
    display: Display,
    val state: dev.thorreader.app.ui.InspectState,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                dev.thorreader.app.ui.BurnInGuard {
                    // Follows the book being inspected (a manga collection switches between its books).
                    val s = ThorApp.instance.inspecting ?: state
                    androidx.compose.runtime.key(s) {
                        when {
                            // "Read": the open animation's bottom-screen part, until the reader
                            // takes this screen over (it draws the same frame there).
                            s.handingOff -> dev.thorreader.app.ui.BookAnimOverlay(dev.thorreader.app.Screen.BOTTOM)
                            s.collection != null -> dev.thorreader.app.ui.InspectOverlay(s, fromRect = null)
                            else -> dev.thorreader.app.ui.InspectInfo(s)
                        }
                    }
                }
            }
        }
        setContentView(view)
        window?.let { w ->
            w.decorView.setViewTreeLifecycleOwner(activity)
            w.decorView.setViewTreeSavedStateRegistryOwner(activity)
            w.decorView.setViewTreeViewModelStoreOwner(activity)
            w.setBackgroundDrawableResource(android.R.color.black)
            w.prefer60Hz(display)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        Log.i(TAG, "Inspect: book details on the bottom screen (display ${display.displayId})")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ThorApp.instance.screenSaverTakesKey(event)) return true // black screens: only wakes them
        ThorApp.instance.userActive()
        if (ThorApp.instance.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ThorApp.instance.screenSaverTakesTouch(ev)) return true
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) ThorApp.instance.userActive()
        return super.dispatchTouchEvent(ev)
    }
}
