package dev.thorreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
        if (!active) return@Box
        if (mode.isBook) {
            RotatedBox(mode.contentRotation) { ScreenBody(screen, mode) }
        } else {
            ScreenBody(screen, mode)
        }
    }
}

@Composable
private fun ScreenBody(screen: Screen, mode: ReadingMode) {
    val app = ThorApp.instance
    val session = app.session
    Box(Modifier.fillMaxSize()) {
        if (session == null) {
            val libraryHere = if (mode == ReadingMode.BOTTOM) screen == Screen.BOTTOM else screen == Screen.TOP
            if (libraryHere) {
                LibraryScreen()
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
            if (app.menuScreen == screen) ReaderMenu(session)
        }
        ModeToastOverlay()
    }
}
