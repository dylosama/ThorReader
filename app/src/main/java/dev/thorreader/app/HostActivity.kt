package dev.thorreader.app

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.thorreader.app.ui.HostActions
import dev.thorreader.app.ui.LocalHost
import dev.thorreader.app.ui.PhysicalScreen
import kotlinx.coroutines.launch

/**
 * Shared base of the two activities. [screen] is the physical screen this activity's own
 * window is on. Subclasses decide in [reconcile] whether they should be running at all for the
 * current mode and whether the bottom-screen Presentation is needed.
 */
abstract class HostActivity : ComponentActivity(), HostActions {

    protected val app: ThorApp get() = ThorApp.instance
    abstract val screen: Screen

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::persist)
        app.importer.importFiles(uris)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri)
        app.importer.importFolder(uri)
    }

    private fun persist(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(TAG, "Could not keep permission for ${uri.lastPathSegment?.substringAfterLast('/')}", it) }
    }

    override fun pickFiles() = pickFiles.launch(arrayOf("*/*"))
    override fun pickFolder() = pickFolder.launch(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prefer60Hz(display)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            CompositionLocalProvider(LocalHost provides this) { PhysicalScreen(screen) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFinishing) {
                    Log.w(TAG, "Back ignored: ${javaClass.simpleName} is finishing (screen handover)")
                    return
                }
                if (!app.handleBack(this@HostActivity.javaClass.simpleName)) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { Triple(app.settings.mode, app.bottomFallback, app.typing) }.collect { reconcile() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { app.keepScreenOn }.collect { window.keepScreenOn(it) }
            }
        }
        Log.i(TAG, "${javaClass.simpleName} created on display ${display?.displayId}")
    }

    override fun onStart() {
        super.onStart()
        app.refreshScreens()
        app.onHostStart(javaClass.simpleName)
    }

    override fun onStop() {
        app.onHostStop(javaClass.simpleName)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else app.stopRepeating()
    }

    override fun onPause() {
        app.stopRepeating() // a held button's release may never reach us now
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        app.userActive()
        return app.handleKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) app.userActive()
        return super.dispatchTouchEvent(ev)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Called whenever the mode changes while this activity is visible. */
    protected abstract fun reconcile()

    /** Start [cls] on [displayId] in its own task. Returns false if Android doesn't allow it. */
    protected fun launchOnDisplay(cls: Class<*>, displayId: Int): Boolean {
        val intent = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val am = getSystemService(ActivityManager::class.java)
        val allowed = runCatching { am.isActivityStartAllowedOnDisplay(this, displayId, intent) }.getOrDefault(false)
        if (!allowed) {
            Log.w(TAG, "Android says ${cls.simpleName} may not start on display $displayId")
            return false
        }
        return try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            Log.i(TAG, "Launched ${cls.simpleName} on display $displayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Launching ${cls.simpleName} on display $displayId failed", e)
            false
        }
    }
}
