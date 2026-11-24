package dev.thorreader.app

/** What a shoulder press does. */
enum class Turn(val label: String) {
    NEXT("next page"),
    PREVIOUS("previous page"),
    PAGE_LEFT("page on the left"),
    PAGE_RIGHT("page on the right"),
}

/**
 * The shoulder buttons (L1/L2, R1/R2). Normally they turn toward the page physically on that
 * side, so in right-to-left books R1 goes back. With "Swap L1/R1 for manga" on (default), in
 * right-to-left books the easy shoulder goes forward instead:
 * Mode 1: R1 next, L1 previous. Mode 2: L1 next, R1 previous. Modes 3 and 4: R1 next, L1 previous.
 * Left-to-right books never change.
 */
object ShoulderButtons {
    fun turn(right: Boolean, mode: ReadingMode, rtl: Boolean, mangaSwap: Boolean): Turn {
        if (!rtl || !mangaSwap) return if (right) Turn.PAGE_RIGHT else Turn.PAGE_LEFT
        val nextIsRight = mode != ReadingMode.BOOK_REVERSED
        return if (right == nextIsRight) Turn.NEXT else Turn.PREVIOUS
    }

    /** What a physical-side turn means in a book: next or previous page. */
    fun meaning(t: Turn, rtl: Boolean): Turn = when (t) {
        Turn.PAGE_RIGHT -> if (rtl) Turn.PREVIOUS else Turn.NEXT
        Turn.PAGE_LEFT -> if (rtl) Turn.NEXT else Turn.PREVIOUS
        else -> t
    }
}
