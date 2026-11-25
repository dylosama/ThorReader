package dev.thorreader.app

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.thorreader.app.ui.LocalHost
import dev.thorreader.app.ui.PhysicalScreen

/**
 * The bottom screen while the reader lives on the top screen (modes 1 and 2, and the mode-4
 * fallback). A Presentation is a window on another display that belongs to our Activity.
 */
class BottomScreenPresentation(
    private val activity: HostActivity,
    display: Display,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(context).apply {
            // A Presentation (a Dialog) has no lifecycle owners of its own; borrow the Activity's.
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalHost provides activity) { PhysicalScreen(Screen.BOTTOM) }
            }
        }
        setContentView(composeView)
        window?.let { w ->
            w.decorView.setViewTreeLifecycleOwner(activity)
            w.decorView.setViewTreeSavedStateRegistryOwner(activity)
            w.decorView.setViewTreeViewModelStoreOwner(activity)
            w.keepScreenOn(ThorApp.instance.keepScreenOn)
            w.prefer60Hz(display)
            ThorApp.instance.windows[Screen.BOTTOM] = w // for the book open / close snapshots
            w.setBackgroundDrawableResource(android.R.color.black)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        // Follow "keep the screen on while reading" (released 10 min after the last input).
        keepOnJob = activity.lifecycleScope.launch {
            androidx.compose.runtime.snapshotFlow { ThorApp.instance.keepScreenOn }.collect { window?.keepScreenOn(it) }
        }
    }

    private var keepOnJob: kotlinx.coroutines.Job? = null

    override fun onStop() {
        keepOnJob?.cancel() // nothing of this window outlives it
        keepOnJob = null
        window?.let { w -> if (ThorApp.instance.windows[Screen.BOTTOM] === w) ThorApp.instance.windows.remove(Screen.BOTTOM) }
        super.onStop()
    }

    /** Buttons pressed while the bottom screen has focus go to the same handler. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        ThorApp.instance.userActive()
        return ThorApp.instance.handleKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) ThorApp.instance.userActive()
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!ThorApp.instance.handleBack("bottom-screen Presentation")) activity.onBackPressedDispatcher.onBackPressed()
    }
}
