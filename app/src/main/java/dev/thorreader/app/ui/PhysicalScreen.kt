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
        // Book open / close animation: over everything, in unrotated screen pixels (also on a
        // screen that is otherwise idle in the new mode, while the animation still needs it).
        BookAnimOverlay(screen)
    }
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
                Text(
                    "Choose a book on the top screen",
                    color = Color(0xFF444444),
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center),
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
