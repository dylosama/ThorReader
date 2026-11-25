package dev.thorreader.app.engine

import dev.thorreader.app.ReadingMode
import kotlin.math.min

/**
 * The cover during the open / close animation, in the virtual space of both screens: a cover
 * image [w] x [h] (unrotated) centred at ([cx], [cy]), turned by [rot] degrees.
 */
data class CoverBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val rot: Float) {
    val left get() = cx - w / 2f
    val right get() = cx + w / 2f
    val top get() = cy - h / 2f
    val bottom get() = cy + h / 2f
}

/** Which line the cover swings around: a vertical spine (rotate around Y) or a horizontal one (around X). */
enum class SwingAxis { VERTICAL, HORIZONTAL }

/**
 * Where the closed cover sits before it opens, and how it opens: [spine] is the x (VERTICAL) or
 * y (HORIZONTAL) of the line it swings around; [freeEdgeNegative] = its free edge is on the
 * smaller-coordinate side of the spine (so it travels toward larger coordinates when opening).
 */
data class CoverPlacement(val box: CoverBox, val axis: SwingAxis, val spine: Float, val freeEdgeNegative: Boolean, val onBottom: Boolean)

/**
 * Pure geometry and timing of the book open / close animation (no Android). The virtual space:
 * the top screen at (0, 0)-(wt, ht); the bottom screen right under it, centred:
 * (ox, ht)-(ox + wb, ht + hb), ox = (wt - wb) / 2. Times are in "open state" s: opening runs
 * s = t, closing s = 1 - t (the same motion in reverse).
 */
object BookAnimMath {
    const val DURATION_MS = 800
    const val FADE_MS = 250

    /** The cover lifts out of its row (scale-up, shadow). */
    const val LIFT_END = 0.15f

    /** ...then moves to its place (and turns for Modes 1/2), arriving closed at HOLD. */
    const val HOLD = 0.5f

    /** The library dims to this over s 0..DIM_END. */
    const val DIM = 0.85f
    const val DIM_END = 0.3f
    const val LIFT_SCALE = 1.08f

    /** The pages are revealed over this part of the swing. */
    const val REVEAL_START = 0.55f
    const val REVEAL_END = 0.85f

    private fun ease(x: Float) = PageTurnMath.easeInOut(x)
    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)

    fun openState(open: Boolean, t: Float): Float = if (open) t else 1f - t

    /** The bottom screen's offset in the virtual space. */
    fun bottomOffset(wt: Float, wb: Float, ht: Float): Pair<Float, Float> = (wt - wb) / 2f to ht

    /** Where the closed cover sits for [mode] (see PLAN.md "Book open / close animation"). */
    fun place(mode: ReadingMode, rtl: Boolean, wt: Float, ht: Float, wb: Float, hb: Float, aspect: Float): CoverPlacement {
        val (ox, oy) = bottomOffset(wt, wb, ht)
        val a = aspect.coerceIn(0.3f, 2f)
        return when (mode) {
            ReadingMode.TOP, ReadingMode.BOTTOM -> {
                // Two pages side by side; the cover lies on the first page's half, spine on the centre line.
                val bottom = mode == ReadingMode.BOTTOM
                val sw = if (bottom) wb else wt
                val sh = if (bottom) hb else ht
                val x0 = if (bottom) ox else 0f
                val y0 = if (bottom) oy else 0f
                var h = sh * 0.9f
                var w = h * a
                if (w > sw / 2f * 0.95f) {
                    w = sw / 2f * 0.95f
                    h = w / a
                }
                val spine = x0 + sw / 2f
                // Left-to-right: the cover is on the right half and opens to the left.
                val cx = if (!rtl) spine + w / 2f else spine - w / 2f
                CoverPlacement(CoverBox(cx, y0 + sh / 2f, w, h, 0f), SwingAxis.VERTICAL, spine, freeEdgeNegative = rtl, onBottom = bottom)
            }
            ReadingMode.BOOK, ReadingMode.BOOK_REVERSED -> {
                // Sideways book: the cover turned like the pages, spine on the hinge between the
                // screens. Left-to-right: on the top screen, swinging down onto the bottom one;
                // right-to-left: on the bottom screen, swinging up.
                val rot = mode.contentRotation
                val onBottom = rtl
                val sw = if (onBottom) wb else wt
                val sh = if (onBottom) hb else ht
                // Turned 90°: the image's height runs across the screen, its width up and down.
                val imgH = min(sw * 0.92f, sh * 0.95f / a)
                val imgW = imgH * a
                val physH = imgW
                val cy = if (onBottom) ht + physH / 2f else ht - physH / 2f
                CoverPlacement(CoverBox(wt / 2f, cy, imgW, imgH, rot), SwingAxis.HORIZONTAL, ht, freeEdgeNegative = !onBottom, onBottom = onBottom)
            }
        }
    }

    /** The cover's box at open state [s]: lifted in its row, then moving (and turning) to [target]. */
    fun boxAt(s: Float, from: CoverBox, target: CoverBox): CoverBox {
        if (s <= LIFT_END) {
            val k = 1f + (LIFT_SCALE - 1f) * ease(clamp01(s / LIFT_END))
            return from.copy(w = from.w * k, h = from.h * k)
        }
        val lifted = from.copy(w = from.w * LIFT_SCALE, h = from.h * LIFT_SCALE)
        val k = ease(clamp01((s - LIFT_END) / (HOLD - LIFT_END)))
        fun lerp(a: Float, b: Float) = a + (b - a) * k
        return CoverBox(lerp(lifted.cx, target.cx), lerp(lifted.cy, target.cy), lerp(lifted.w, target.w), lerp(lifted.h, target.h), lerp(lifted.rot, target.rot))
    }

    /** How far the cover has swung open, 0..180°. */
    fun swingDeg(s: Float): Float = if (s <= HOLD) 0f else 180f * ease(clamp01((s - HOLD) / (1f - HOLD)))

    /** Darkness laid over the library behind the cover. */
    fun dimAt(s: Float): Float = DIM * ease(clamp01(s / DIM_END))

    /** How much of the real pages shows (0 = none: the cover / backdrop hides them). */
    fun revealAt(s: Float): Float = ease(clamp01((s - REVEAL_START) / (REVEAL_END - REVEAL_START)))

    /** Strength of the cover's lifted shadow. */
    fun shadowAt(s: Float): Float = when {
        s <= LIFT_END -> ease(clamp01(s / LIFT_END))
        s <= HOLD -> 1f
        else -> 1f - clamp01((s - HOLD) / 0.2f)
    }

    // ---- Hinge style (book modes 1 and 2) -------------------------------------------------------
    //
    // glide to the top screen's centre (0 .. 22 %), turn sideways there (22 .. 44 %), travel down
    // so the spine lands on the hinge (44 .. 78 %), and swing open starting 80 % into the travel
    // (71.2 % .. 100 %), so travel and opening overlap as one motion.

    const val H_GLIDE_END = 0.22f
    const val H_ROTATE_END = 0.44f
    const val H_TRAVEL_END = 0.78f
    const val H_OPEN_START = H_ROTATE_END + 0.8f * (H_TRAVEL_END - H_ROTATE_END) // 0.712

    /** At the centre the cover turns at this share of its final size, then grows as it travels. */
    const val H_CENTRE_SCALE = 0.65f

    /** Where an opening waits for its first pages (the cover closed, before it swings). */
    fun holdPoint(hinge: Boolean): Float = if (hinge) H_OPEN_START else HOLD

    /** The cover's box at open state [s] for the Hinge style. */
    fun hingeBoxAt(s: Float, from: CoverBox, target: CoverBox, wt: Float, ht: Float): CoverBox {
        // Upright at the centre of the top screen, as big as fits (not yet turned).
        val uprightH = ht * 0.8f
        val upright = CoverBox(wt / 2f, ht / 2f, uprightH * (from.w / from.h), uprightH, 0f)
        // Turned at the centre, a little smaller than its final size.
        val turned = CoverBox(wt / 2f, ht / 2f, target.w * H_CENTRE_SCALE, target.h * H_CENTRE_SCALE, target.rot)
        fun mix(a: CoverBox, b: CoverBox, k: Float) = CoverBox(
            a.cx + (b.cx - a.cx) * k, a.cy + (b.cy - a.cy) * k, a.w + (b.w - a.w) * k, a.h + (b.h - a.h) * k, a.rot + (b.rot - a.rot) * k,
        )
        return when {
            s <= H_GLIDE_END -> mix(from, upright, ease(clamp01(s / H_GLIDE_END)))
            s <= H_ROTATE_END -> mix(upright, turned, ease(clamp01((s - H_GLIDE_END) / (H_ROTATE_END - H_GLIDE_END))))
            else -> mix(turned, target, ease(clamp01((s - H_ROTATE_END) / (H_TRAVEL_END - H_ROTATE_END))))
        }
    }

    /** How far the cover has swung open (0..180°) for either style. */
    fun swingDegFor(s: Float, hinge: Boolean): Float =
        if (!hinge) swingDeg(s) else if (s <= H_OPEN_START) 0f else 180f * ease(clamp01((s - H_OPEN_START) / (1f - H_OPEN_START)))

    /** How much of the pages shows, for either style. */
    fun revealFor(s: Float, hinge: Boolean): Float {
        if (!hinge) return revealAt(s)
        val a = H_OPEN_START + 0.08f
        val b = H_OPEN_START + 0.65f * (1f - H_OPEN_START)
        return ease(clamp01((s - a) / (b - a)))
    }

    /** Lifted shadow, for either style. */
    fun shadowFor(s: Float, hinge: Boolean): Float = when {
        !hinge -> shadowAt(s)
        s <= H_GLIDE_END * 0.5f -> ease(clamp01(s / (H_GLIDE_END * 0.5f)))
        s <= H_OPEN_START -> 1f
        else -> 1f - clamp01((s - H_OPEN_START) / 0.15f)
    }

    /**
     * The line the cover swings around, from where the cover IS (in the Hinge style it is still
     * travelling when it starts to open): its spine edge. At the end of the travel this is the hinge.
     */
    fun spineOf(box: CoverBox, place: CoverPlacement): Float = when (place.axis) {
        // Turned ±90°: its width runs up and down.
        SwingAxis.HORIZONTAL -> if (place.freeEdgeNegative) box.cy + box.w / 2f else box.cy - box.w / 2f
        SwingAxis.VERTICAL -> if (place.freeEdgeNegative) box.right else box.left
    }

    /** May the animation go past [HOLD] (opening): only when the first pages are rendered. */
    fun canPassHold(open: Boolean, pagesReady: Boolean): Boolean = !open || pagesReady
}
