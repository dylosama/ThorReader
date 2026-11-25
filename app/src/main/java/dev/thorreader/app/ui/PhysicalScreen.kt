package dev.thorreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.Screen
import dev.thorreader.app.ThorApp
import dev.thorreader.app.TutorialStep
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/** The idle bottom screen's "Choose a book" hint shows this long, then fades to black. */
private const val HINT_MS = 8_000L

/**
 * Everything drawn on one physical screen. The same composable runs on the top screen
 * (MainActivity) and the bottom screen (Presentation or BottomActivity); it decides from the
 * current mode what that screen should show, and rotates it for the book modes.
 */
@Composable
fun PhysicalScreen(screen: Screen) {
    val app = ThorApp.instance
    val mode = app.settings.mode
    val active = when (screen) {
        Screen.TOP -> mode != ReadingMode.BOTTOM
        Screen.BOTTOM -> mode != ReadingMode.TOP
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Burn-in protection: everything sits a pixel or two off and drifts slowly (BurnIn).
        Box(
            Modifier.fillMaxSize().offset {
                val (x, y) = dev.thorreader.app.engine.BurnIn.shiftAt(app.pixelShiftStep)
                IntOffset(x, y)
            },
        ) {
            if (active) {
                if (mode.isBook) {
                    RotatedBox(mode.contentRotation) { ScreenBody(screen, mode) }
                } else {
                    ScreenBody(screen, mode)
                }
            }
            // Inspect book: the 3D book over the library (top screen).
            val inspect = ThorApp.instance.inspecting
            if (inspect != null && screen == Screen.TOP && ThorApp.instance.session == null) InspectOverlay(inspect)
        }
        // Book open / close animation: over everything, in unrotated screen pixels (also on a
        // screen that is otherwise idle in the new mode, while the animation still needs it).
        BookAnimOverlay(screen)
        ScreenSaverCover()
    }
}

/**
 * Burn-in protection: [content] drifts a pixel or two (slowly), and everything goes black (pixels
 * off) after Settings → Screen minutes without input. For the screens' extra windows (keyboard,
 * Inspect details); [PhysicalScreen] does the same itself.
 */
@Composable
fun BurnInGuard(content: @Composable () -> Unit) {
    val app = ThorApp.instance
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().offset {
                val (x, y) = dev.thorreader.app.engine.BurnIn.shiftAt(app.pixelShiftStep)
                IntOffset(x, y)
            },
        ) { content() }
        ScreenSaverCover()
    }
}

/** Black over everything while the screen saver is on (fades in over 1.5 s). */
@Composable
private fun ScreenSaverCover() {
    val app = ThorApp.instance
    val black by animateFloatAsState(
        if (app.screenSaver) 1f else 0f, tween(if (app.screenSaver) 1500 else 150), label = "screen saver",
    )
    if (black > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = black }.background(Color.Black))
}

@Composable
private fun ScreenBody(screen: Screen, mode: ReadingMode) {
    val app = ThorApp.instance
    val session = app.session
    Box(Modifier.fillMaxSize().tutorialTapCatcher()) {
        if (session == null) {
            val libraryHere = if (mode == ReadingMode.BOTTOM) screen == Screen.BOTTOM else screen == Screen.TOP
            val typing = app.typing
            if (libraryHere) {
                LibraryScreen()
                // Mode 4 (one screen): the keyboard covers the lower part while typing.
                if (typing != null && mode == ReadingMode.BOTTOM) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)) { BottomKeyboard(typing) }
                }
            } else if (typing != null) {
                // Modes 1 and 2: the bottom screen is the keyboard while typing in the library.
                BottomKeyboard(typing)
            } else {
                // A hint, then black: still text on an idle screen is what burns in.
                var shown by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(HINT_MS)
                    shown = false
                }
                val a by animateFloatAsState(if (shown) 1f else 0f, tween(1500), label = "bottom hint")
                if (a > 0f) Text(
                    "Choose a book on the top screen",
                    color = Color(0xFF444444),
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = a },
                )
            }
        } else {
            ReaderScreen(session, screen, mode)
            if (app.menuScreen == null) {
                BackPill(session, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
            }
            if (session.scrubScreen == screen) ScrubberBar(session)
            // First open: how to reach the menu. Shown on the screen where Start opens it.
            val menuHere = if (mode == ReadingMode.BOTTOM) Screen.BOTTOM else Screen.TOP
            if (screen == menuHere && app.menuScreen == null && session.pagination != null && session.scrubScreen == null &&
                app.tutorialShows(TutorialStep.OPEN_MENU)
            ) {
                val ms = app.settings.menuHoldMs
                val secs = if (ms % 1000 == 0) "${ms / 1000} second${if (ms == 1000) "" else "s"}" else "${ms / 1000.0} seconds"
                TutorialBadge(
                    TutorialStep.OPEN_MENU, "Hold anywhere for $secs, or press Start, to open the menu", ArrowDir.NONE,
                    Modifier.align(Alignment.Center),
                )
            }
            if (app.menuScreen == screen) ReaderMenu(session)
        }
        ModeToastOverlay()
    }
}
