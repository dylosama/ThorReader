package dev.thorreader.app

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors

/**
 * Background work (import, online lookups, the metadata re-scan) shares TWO low-priority
 * threads, and every job waits at [awaitAllowed] between steps, so it pauses while the reader
 * is turning pages, while the app is in the background, and while the screen is off. Reading
 * itself (rendering the current page) never waits here.
 */
object Background {

    /** At most 2 worker threads, at Android's background priority. */
    val dispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "ThorReader-bg").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private data class State(val visible: Boolean = true, val screenOn: Boolean = true)

    private val state = MutableStateFlow(State())

    /** Last page turn / swap animation (uptime ms): background work waits [BUSY_MS] after it. */
    @Volatile
    private var lastBusyAt = 0L

    /** The app has a visible activity (false = in the background / lid closed). */
    fun setVisible(v: Boolean) {
        state.value = state.value.copy(visible = v)
    }

    fun setScreenOn(on: Boolean) {
        state.value = state.value.copy(screenOn = on)
    }

    /** The reader just turned a page or started an animation. */
    fun markBusy() {
        lastBusyAt = SystemClock.uptimeMillis()
    }

    /** True if background work could run right now. */
    val allowed: Boolean
        get() = state.value.let { it.visible && it.screenOn } && SystemClock.uptimeMillis() - lastBusyAt >= BUSY_MS

    /** Suspend until background work may run (app visible, screen on, no page turning). */
    suspend fun awaitAllowed() {
        while (true) {
            val s = state.value
            if (!s.visible || !s.screenOn) {
                state.first { it.visible && it.screenOn }
                continue
            }
            val wait = lastBusyAt + BUSY_MS - SystemClock.uptimeMillis()
            if (wait > 0) {
                delay(wait)
                continue
            }
            return
        }
    }

    const val BUSY_MS = 1500L
}
