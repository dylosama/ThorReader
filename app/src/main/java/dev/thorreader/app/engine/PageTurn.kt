package dev.thorreader.app.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Page-turn animation styles (reader menu "Page turn"). */
enum class TurnStyle(val label: String) { CURL("Curl"), FLIP("Flip"), SLIDE("Slide"), FADE("Fade"), NONE("None") }

/** Animation speed (reader menu). Normal ≈ 350 ms. */
enum class TurnSpeed(val label: String, val ms: Int) { FAST("Fast", 220), NORMAL("Normal", 350), SLOW("Slow", 600) }

/** How a turn was asked for. */
enum class TurnCause {
    /** Tap, swipe, d-pad, shoulders, menu ±1/±10: the full animation. */
    NORMAL,

    /** A tap zone or a physical button (d-pad, shoulders): the full animation, curl from a corner. */
    BUTTON,

    /** A held button flipping continuously: a fast Slide. */
    REPEAT,

    /** Scrubber, go to page, chapter list, bookmark: fast Slide for one view, none for more. */
    JUMP,
}

/**
 * The part one page area plays in a turn. Two regions = the two pages either side of a hinge
 * (the two screens in book modes, or the two halves of a spread): SOURCE is where the sheet
 * starts (phase 1), DEST where it lands (phase 2). SINGLE = one page turning on its own.
 */
enum class RegionRole { SOURCE, DEST, SINGLE }

/**
 * The cylinder of a curl, in a region-local frame: x runs from the attached edge (the hinge /
 * spine, x = 0) to the free edge that lifts (x = W); y downwards. The axis line passes through
 * (a, H/2) and its normal (cos θ, sin θ) points toward the lifting side; r = cylinder radius.
 */
data class Curl(val a: Float, val theta: Float, val r: Float, val pivotY: Float? = null)

/** Where a point of the page ends up after the curl: x, y in the region frame, z = height. */
data class Projected(val x: Float, val y: Float, val z: Float)

/** Pure page-turn geometry and timing (no Android): shared by the drawing code and the tests. */
object PageTurnMath {
    /** Phase 1 (source) runs over t in [0, HANDOFF], phase 2 (destination) over [HANDOFF, 1]. */
    const val HANDOFF = 0.5f

    /** A released drag completes the turn past this much of the page width... */
    const val COMPLETE_AT = 0.35f

    /** ...or when flicked faster than this toward the turn (px per second). */
    const val FLICK_PX_PER_S = 1000f

    /** Held buttons and one-view jumps: a quick slide. */
    const val FAST_SLIDE_MS = 120

    /** Curl radius in inches (converted with each screen's own dpi, so both bends match). */
    const val RADIUS_INCH = 0.28f

    /** Below this battery level (and not charging) the simpler Fade is used. */
    const val LOW_BATTERY = 15

    /** A turn is "slow" if the 95th-percentile frame interval exceeds two 60 Hz frames. */
    const val SLOW_P95_MS = 34f

    /** This many slow curl turns in a row switch curl off for the session (Slide instead). */
    const val SLOW_TURNS_TO_FALL_BACK = 3

    /** The tilt of a corner curl is limited, so the page never folds absurdly. */
    const val MAX_THETA = 0.6f

    // ---- timing -----------------------------------------------------------------------------

    /** Natural ease in-out (cubic). */
    fun easeInOut(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return if (v < 0.5f) 4f * v * v * v else 1f - (-2f * v + 2f).let { it * it * it } / 2f
    }

    /** Ease out (cubic): for finishing a drag that is already moving. */
    fun easeOut(x: Float): Float {
        val v = 1f - x.coerceIn(0f, 1f)
        return 1f - v * v * v
    }

    /** Local progress (0..1) of a region with [role] at global progress [t]. */
    fun localProgress(role: RegionRole, t: Float): Float = when (role) {
        RegionRole.SOURCE -> (t / HANDOFF).coerceIn(0f, 1f)
        RegionRole.DEST -> ((t - HANDOFF) / (1f - HANDOFF)).coerceIn(0f, 1f)
        RegionRole.SINGLE -> t.coerceIn(0f, 1f)
    }

    /** Global progress for a SOURCE / SINGLE region's local progress [u] (inverse of [localProgress]). */
    fun globalProgress(role: RegionRole, u: Float): Float = when (role) {
        RegionRole.SOURCE -> u * HANDOFF
        RegionRole.DEST -> HANDOFF + u * (1f - HANDOFF)
        RegionRole.SINGLE -> u
    }

    // ---- sides, roles, mirroring --------------------------------------------------------------

    /** The physical side the turning sheet starts on (forward = the next-page side). */
    fun startSide(forward: Boolean, rtl: Boolean): Side = if (forward != rtl) Side.RIGHT else Side.LEFT

    /** Role of the region on [regionSide] of the hinge in a two-region turn. */
    fun roleOf(regionSide: Side, start: Side): RegionRole = if (regionSide == start) RegionRole.SOURCE else RegionRole.DEST

    /**
     * Is the region's free (lifting) edge on its physical LEFT? Then its local frame is mirrored
     * (x = W - pixel x). Two regions: the free edge is the outer edge (away from the hinge), so a
     * region LEFT of the hinge lifts on its left. One region: it lifts on the next-page side
     * (right in left-to-right books, left in right-to-left ones).
     */
    fun freeEdgeLeft(role: RegionRole, regionSide: Side?, rtl: Boolean): Boolean = when (role) {
        RegionRole.SINGLE -> rtl
        else -> regionSide == Side.LEFT
    }

    /** Which page is the moving sheet: the OLD page (true) or the NEW one (false)? */
    fun sheetIsOld(role: RegionRole, forward: Boolean): Boolean = when (role) {
        RegionRole.SOURCE -> true
        RegionRole.DEST -> false
        RegionRole.SINGLE -> forward // backward: the previous page comes back over the current one
    }

    /** One region: forward plays 0 -> 1, backward plays the same motion in reverse. */
    fun singleU(t: Float, forward: Boolean): Float = if (forward) t else 1f - t

    // ---- curl geometry ------------------------------------------------------------------------

    /** Past the hinge the axis keeps moving this many radii, so the bump at the hinge rolls away. */
    private const val BEYOND_HINGE_R = 2f * PI.toFloat()

    /** A single page is completely gone once the axis is this many radii past the far edge. */
    private const val PAST_EDGE_R = 1.5f

    /**
     * Axis position at global progress [t] for a region [w] wide with cylinder radius [r].
     * SOURCE: phase 1 runs from the free edge (flat) to the hinge, then (phase 2) keeps rolling
     * past the hinge out of the region. DEST: during phase 1 it approaches from beyond the hinge,
     * then (phase 2) runs from the hinge to the free edge (flat again). At t = HANDOFF both are
     * exactly at the hinge. SINGLE: from the free edge across and off the far side (the folded
     * flap travels twice as fast as the axis, so the corner leaves early and the fold rolls on).
     */
    fun axisAt(role: RegionRole, t: Float, w: Float, r: Float, forward: Boolean = true): Float {
        val beyond = BEYOND_HINGE_R * r
        return when (role) {
            RegionRole.SOURCE ->
                if (t <= HANDOFF) w * (1f - t / HANDOFF) else -beyond * ((t - HANDOFF) / (1f - HANDOFF))
            RegionRole.DEST ->
                if (t >= HANDOFF) w * ((t - HANDOFF) / (1f - HANDOFF)) else -beyond * ((HANDOFF - t) / HANDOFF)
            RegionRole.SINGLE -> w - (w + PAST_EDGE_R * r) * singleU(t, forward)
        }
    }

    /** Global progress for an axis position of a dragged SOURCE / forward SINGLE region (inverse of [axisAt]). */
    fun progressForAxis(role: RegionRole, a: Float, w: Float, r: Float): Float = when (role) {
        RegionRole.SOURCE -> ((1f - a / w) * HANDOFF).coerceIn(0f, HANDOFF)
        RegionRole.DEST -> (HANDOFF + a / w * (1f - HANDOFF)).coerceIn(HANDOFF, 1f)
        RegionRole.SINGLE -> ((w - a) / (w + PAST_EDGE_R * r)).coerceIn(0f, 1f)
    }

    /** Tilt of a button / tap corner curl at the start (about 26°). */
    const val CORNER_THETA = 0.45f

    /** Initial tilt for a corner curl: + lifts the bottom corner first, − the top one. */
    fun cornerTheta0(bottom: Boolean): Float = if (bottom) CORNER_THETA else -CORNER_THETA

    /** The fold of a corner curl pivots on the grabbed corner's edge (so nothing lifts before t = 0). */
    fun cornerPivotY(theta0: Float, h: Float): Float = if (theta0 >= 0f) h else 0f

    /**
     * Tilt of a button / tap corner curl at global progress [t]. SOURCE: starts diagonal (the
     * outer corner lifts first) and straightens out exactly at the hinge. DEST: straight at the
     * hinge, then the same diagonal mirrored, so the leading corner lands first, and straight
     * again when flat. Both sides are straight at the handoff, so the fold is continuous across
     * the hinge. SINGLE: diagonal at the start, straight when the page has gone.
     */
    fun cornerTheta(role: RegionRole, t: Float, theta0: Float, forward: Boolean): Float {
        if (theta0 == 0f) return 0f
        return when (role) {
            RegionRole.SOURCE -> if (t <= HANDOFF) theta0 * (1f - (t / HANDOFF).let { it * it }) else 0f
            RegionRole.DEST -> if (t >= HANDOFF) -theta0 * sin(PI.toFloat() * localProgress(role, t)) else 0f
            RegionRole.SINGLE -> theta0 * (1f - singleU(t, forward))
        }
    }

    /** Does a turn between these two views fold at a hinge (two regions) or turn as one sheet? */
    fun hasHinge(bookMode: Boolean, oldTwoPages: Boolean, newTwoPages: Boolean): Boolean =
        bookMode || (oldTwoPages && newTwoPages)

    /**
     * The tilt after a drag is released at [t0] with tilt [theta0]: it straightens out on the way
     * to [target], so the sheet reaches the hinge (or lies flat again) square.
     */
    fun thetaAt(theta0: Float, t0: Float, target: Float, t: Float): Float {
        if (theta0 == 0f || target == t0) return 0f
        return theta0 * ((target - t) / (target - t0)).coerceIn(0f, 1f)
    }

    /** Signed distance of page point (x, y) from the axis (positive = lifted side). */
    fun distance(x: Float, y: Float, c: Curl, h: Float): Float =
        (x - c.a) * cos(c.theta) + (y - (c.pivotY ?: (h / 2f))) * sin(c.theta)

    /**
     * Where page point (x, y) ends up. Points behind the axis stay flat; points past it wrap
     * around the cylinder (arc length = their distance), and past half the circumference lie
     * flat on top again, travelling back over the page (the folded flap).
     */
    fun project(x: Float, y: Float, c: Curl, h: Float): Projected {
        val d = distance(x, y, c, h)
        if (d <= 0f) return Projected(x, y, 0f)
        val nx = cos(c.theta)
        val ny = sin(c.theta)
        val half = (PI * c.r).toFloat()
        val (along, z) = if (d < half) {
            c.r * sin(d / c.r) to c.r * (1f - cos(d / c.r))
        } else {
            -(d - half) to 2f * c.r
        }
        // Move the point from distance d to distance `along` along the normal.
        return Projected(x + (along - d) * nx, y + (along - d) * ny, z)
    }

    /** How far a point at arc length [s] past the axis appears to have moved toward the axis. */
    private fun moved(s: Float, r: Float): Float {
        val half = (PI * r).toFloat()
        return if (s < half) s - r * sin(s / r) else 2f * s - half
    }

    /**
     * The curl that puts the grabbed [corner] (cx, cy) under the finger (fx, fy): the axis is
     * perpendicular to corner -> finger, at the distance where the corner's projection lands on
     * the finger (solved for the cylinder, not a flat fold). Null if the finger hasn't moved
     * toward the hinge.
     */
    fun curlFromDrag(cx: Float, cy: Float, fx: Float, fy: Float, r: Float, h: Float): Curl? {
        val dx = cx - fx
        val dy = cy - fy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f || dx <= 0f) return null
        var theta = atan2(dy, dx).coerceIn(-MAX_THETA, MAX_THETA)
        val nx = cos(theta)
        val ny = sin(theta)
        // Distance actually covered along the (possibly clamped) normal.
        val want = dx * nx + dy * ny
        // Solve moved(s) = want for the corner's arc length s (monotonic: bisection).
        var lo = 0f
        var hi = want + (PI * r).toFloat() + 1f
        repeat(40) {
            val mid = (lo + hi) / 2f
            if (moved(mid, r) < want) lo = mid else hi = mid
        }
        val s = (lo + hi) / 2f
        // The axis passes through corner - n * s; express it at y = h/2.
        val px = cx - nx * s
        val py = cy - ny * s
        val a = px + (py - h / 2f) * ny / nx
        if (abs(nx) < 1e-3f) theta = 0f
        return Curl(a, theta, r)
    }

    /** The corner a drag grabs: top or bottom third lifts that corner, the middle curls straight. */
    fun grabbedCornerY(touchY: Float, h: Float): Float = when {
        touchY < h / 3f -> 0f
        touchY > h * 2f / 3f -> h
        else -> touchY
    }

    // ---- drags --------------------------------------------------------------------------------

    /** Complete a released drag? [fraction] = finger travel / page width; [velocity] toward the turn. */
    fun shouldComplete(fraction: Float, velocity: Float): Boolean =
        velocity > FLICK_PX_PER_S || (fraction >= COMPLETE_AT && velocity > -FLICK_PX_PER_S)

    // ---- style choice -------------------------------------------------------------------------

    /** The style and duration actually used for a turn. */
    fun chooseStyle(
        cause: TurnCause,
        steps: Int,
        preferred: TurnStyle,
        speed: TurnSpeed,
        lowBattery: Boolean,
        curlAvailable: Boolean,
    ): Pair<TurnStyle, Int> {
        if (preferred == TurnStyle.NONE) return TurnStyle.NONE to 0
        return when (cause) {
            TurnCause.REPEAT -> TurnStyle.SLIDE to FAST_SLIDE_MS
            TurnCause.JUMP -> if (abs(steps) == 1) TurnStyle.SLIDE to FAST_SLIDE_MS else TurnStyle.NONE to 0
            TurnCause.NORMAL, TurnCause.BUTTON -> {
                val style = when {
                    lowBattery -> TurnStyle.FADE
                    preferred == TurnStyle.CURL && !curlAvailable -> TurnStyle.SLIDE
                    else -> preferred
                }
                style to speed.ms
            }
        }
    }

    // ---- measuring ----------------------------------------------------------------------------

    /** The [p]-th percentile (0..100) of [values] (nearest rank). */
    fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val rank = (p / 100f * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    /** Pixels for the curl radius on a screen with [dpi] (clamped to sensible sizes). */
    fun radiusPx(dpi: Float, regionWidth: Float): Float = min(max(RADIUS_INCH * dpi, regionWidth * 0.06f), regionWidth * 0.2f)

    /** The front of the bent part gets darker toward the crest (lighting along the bend). */
    fun frontShade(arc: Float, r: Float): Float = 1f - 0.28f * sin((arc / r).coerceIn(0f, (PI / 2).toFloat()))

    // ---- texture quality (v0.3.0 landing fix) --------------------------------------------------

    /** Comics / PDF: size factor of the lighter texture the OLD page (the moving flap) may use. */
    const val LIGHT_TEXTURE = 0.5f

    /** A landing turn waits at most this long for full-quality pages before committing anyway. */
    const val LANDING_WAIT_MAX_MS = 1500L

    /**
     * Curl texture size factors (old page, new page). The NEW page is what the turn lands on
     * (revealed under the flap, or the sheet settling flat), so it is always full quality: the
     * animation's last frame is then identical to the page at rest. Only the old page (moving
     * away) may be lighter, and only for comics / PDF.
     */
    fun textureScales(reflow: Boolean): Pair<Float, Float> = (if (reflow) 1f else LIGHT_TEXTURE) to 1f

    /**
     * Where a [bw] x [bh] page bitmap is drawn in a [w] x [h] page area, in whole pixels. The
     * reader's page view (PageSlot) and the page-turn pictures both use this, so the animation's
     * final frame and the page at rest line up pixel for pixel. Normal pages fit the area,
     * centred; overflowing pages fill its width from the top, scrolled by [scroll].
     */
    fun fitRect(bw: Float, bh: Float, w: Float, h: Float, overflow: Boolean, scroll: Float = 0f): PixelRect {
        val scale = if (overflow) w / bw else min(w / bw, h / bh)
        val dw = bw * scale
        val dh = bh * scale
        val dx = (w - dw) / 2f
        val dy = if (overflow) -scroll else (h - dh) / 2f
        return PixelRect(Math.round(dx), Math.round(dy), Math.round(dw), Math.round(dh))
    }
}

/** A rectangle in whole pixels: left, top, width, height. */
data class PixelRect(val x: Int, val y: Int, val w: Int, val h: Int)
