package dev.thorreader.bookloader

import android.app.Presentation
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.thorreader.bookloader.ui.BottomKeyboard
import dev.thorreader.bookloader.ui.TypingSession

/**
 * The bottom screen while a Bookloader text field is being edited: Thor Reader's built-in
 * keyboard. Exists only while typing, then it is dismissed and the bottom screen is free again.
 * (The Thor won't open Android's own keyboard for the bottom screen; on the top screen it would
 * cover the results.)
 */
class KeyboardPresentation(
    private val activity: ComponentActivity,
    display: Display,
    val session: TypingSession,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { BottomKeyboard(session) }
        }
        setContentView(view)
        window?.let { w ->
            w.decorView.setViewTreeLifecycleOwner(activity)
            w.decorView.setViewTreeSavedStateRegistryOwner(activity)
            w.decorView.setViewTreeViewModelStoreOwner(activity)
            w.setBackgroundDrawableResource(android.R.color.black)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        Log.i(TAG, "Keyboard shown on the bottom screen (display ${display.displayId})")
    }

    /** The Thor's buttons while the bottom screen has focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BookloaderApp.instance.handleTypingKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
