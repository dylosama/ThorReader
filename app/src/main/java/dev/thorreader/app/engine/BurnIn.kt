package dev.thorreader.app.engine

/**
 * Burn-in protection for the Thor's AMOLED screens (both screens):
 * - Pixel shift: everything on screen sits a pixel or two off its place, and the place moves
 *   one small step every [SHIFT_EVERY_MS], around a closed loop - so nothing (page number pill,
 *   running header, library controls) stays on exactly the same pixels for hours. Too small and
 *   too slow to notice.
 * - Screen saver: see ThorApp.screenSaver (black screens after Settings → Screen minutes).
 */
object BurnIn {
    /** Each step moves the picture by one unit of [UNIT_PX]: a loop around a 3 x 3 square. */
    val SHIFTS: List<Pair<Int, Int>> = listOf(
        0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1,
    ).map { (x, y) -> x * UNIT_PX to y * UNIT_PX }

    /** Pixels per unit of [SHIFTS] (so at most 2 px off the true place). */
    const val UNIT_PX = 2

    /** How often the picture takes its next step. */
    const val SHIFT_EVERY_MS = 3 * 60_000L

    fun shiftAt(step: Int): Pair<Int, Int> = SHIFTS[Math.floorMod(step, SHIFTS.size)]
}
