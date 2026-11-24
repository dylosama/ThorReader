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
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.setBackgroundDrawableResource(android.R.color.black)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** Buttons pressed while the bottom screen has focus go to the same handler. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        ThorApp.instance.handleKey(event) || super.dispatchKeyEvent(event)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!ThorApp.instance.handleBack("bottom-screen Presentation")) activity.onBackPressedDispatcher.onBackPressed()
    }
}
