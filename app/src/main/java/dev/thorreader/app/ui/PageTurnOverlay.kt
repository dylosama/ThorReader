package dev.thorreader.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Camera
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.thorreader.app.PageTheme
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.Screen
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.BlankUnit
import dev.thorreader.app.engine.PageTurnMath
import dev.thorreader.app.engine.PageUnit
import dev.thorreader.app.engine.Pagination
import dev.thorreader.app.engine.ReaderSession
import dev.thorreader.app.engine.RegionRole
import dev.thorreader.app.engine.Side
import dev.thorreader.app.engine.SlotSize
import dev.thorreader.app.engine.TurnStyle
import dev.thorreader.app.engine.TurnSupport
import dev.thorreader.app.engine.isSplitSpread
import dev.thorreader.app.engine.screenPages
import dev.thorreader.app.engine.unitHolds
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The page-turn animation over one screen's pages (step 22). Composed only while a turn runs:
 * it pictures the old and new page of each page area, then draws the chosen style every frame
 * from the session's shared progress (so both screens move together). Opaque over the pages:
 * when it goes away the real (already switched) page is underneath. No touch handling: taps and
 * drags reach the page below (a new turn mid-animation finishes this one instantly).
 */
@Composable
fun PageTurnOverlay(session: ReaderSession, p: Pagination, screen: Screen, mode: ReadingMode) {
    val turn = session.turn ?: return
    val dpi = LocalContext.current.resources.displayMetrics.xdpi
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val regions = remember(turn.id, w, h, p) { buildRegions(session, p, turn, screen, mode, w, h, dpi, density) }
        DisposableEffect(regions) { onDispose { regions?.forEach { it.release() } } }
        if (regions == null) {
            LaunchedEffect(turn.id) { session.finishTurnNow("a page picture was missing") }
            return@BoxWithConstraints
        }
        Canvas(Modifier.fillMaxSize()) {
            val t = session.turnProgress // read in the draw phase: only redraws, no recomposition
            val drag = session.dragCurl
            drawIntoCanvas { c -> regions.forEach { it.draw(c.nativeCanvas, turn, t, drag, screen) } }
        }
    }
}

// ---- building the page pictures ---------------------------------------------------------------

private fun buildRegions(
    session: ReaderSession,
    p: Pagination,
    turn: ReaderSession.Turn,
    screen: Screen,
    mode: ReadingMode,
    w: Int,
    h: Int,
    dpi: Float,
    density: Float,
): List<TurnRegion>? {
    if (w <= 0 || h <= 0) return null
    val rtl = p.config.rtl
    val oldV = p.views.getOrNull(turn.from) ?: return null
    val newV = p.views.getOrNull(turn.to) ?: return null
    val start = PageTurnMath.startSide(turn.forward, rtl)
    val style = Pictures(session, p, density)
    val hinge = session.turnHasHinge(p, turn, mode)

    fun region(left: Int, width: Int, side: Side?, role: RegionRole, oldPic: Picture?, newPic: Picture?): TurnRegion? {
        if (oldPic == null || newPic == null) return null
        return TurnRegion(
            left.toFloat(), width, h, role, side, PageTurnMath.freeEdgeLeft(role, side, rtl), oldPic, newPic,
            PageTurnMath.radiusPx(dpi, width.toFloat()), if (session.reflowable) 1f else 0.5f, style.theme, session.reflowable, start,
        )
    }

    return when {
        mode.isBook -> {
            // One page area per screen; the hinge is between the screens.
            val screenIsLeft = (screen == Screen.TOP) == (mode == ReadingMode.BOOK)
            val side = if (screenIsLeft) Side.LEFT else Side.RIGHT
            fun pic(vi: Int, swapped: Boolean): Picture? {
                val v = p.views[vi]
                val (unit, slot) = screenPages(p, vi, mode, screen, swapped).firstOrNull() ?: return style.blank(w, h)
                val dim = swapped && screen == Screen.BOTTOM && isSplitSpread(v)
                return style.page(unit, slot, w, h, side, dim)
            }
            listOfNotNull(region(0, w, side, PageTurnMath.roleOf(side, start), pic(turn.from, turn.fromSwapped), pic(turn.to, false)))
                .takeIf { it.size == 1 }
        }
        hinge -> {
            // Two pages side by side: each half is a page area, folding at the centre.
            val half = w / 2
            val leftIdx = if (rtl) 1 else 0
            fun pic(v: dev.thorreader.app.engine.PageView, vi: Int, left: Boolean): Picture? {
                val k = if (left) leftIdx else 1 - leftIdx
                return style.page(v.units[k], p.slotFor(v, k), if (left) half else w - half, h, if (left) Side.LEFT else Side.RIGHT, false)
            }
            listOfNotNull(
                region(0, half, Side.LEFT, PageTurnMath.roleOf(Side.LEFT, start), pic(oldV, turn.from, true), pic(newV, turn.to, true)),
                region(half, w - half, Side.RIGHT, PageTurnMath.roleOf(Side.RIGHT, start), pic(oldV, turn.from, false), pic(newV, turn.to, false)),
            ).takeIf { it.size == 2 }
        }
        else -> {
            // One sheet over the whole screen (a single page, a wide spread, or mixed views).
            listOfNotNull(region(0, w, null, RegionRole.SINGLE, style.view(oldV, w, h, rtl), style.view(newV, w, h, rtl))).takeIf { it.size == 1 }
        }
    }
}

/** Records pages exactly as the reader draws them: page, theme colours, ribbon, page-number pill. */
private class Pictures(val session: ReaderSession, val p: Pagination, val density: Float) {
    val settings = ThorApp.instance.settings
    val theme: PageTheme = settings.theme
    private val reflow = session.reflowable
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        if (reflow) colorFilter = ColorMatrixColorFilter(theme.colorMatrix.values)
    }
    private val background = if (reflow) theme.paper.toArgb() else android.graphics.Color.BLACK

    fun blank(w: Int, h: Int): Picture = Picture().also { pic ->
        pic.beginRecording(w, h).let { if (reflow) it.drawColor(background) }
        pic.endRecording()
    }

    /** A whole view in a [w] x [h] box: one page (or a wide spread), or two pages side by side. */
    fun view(v: dev.thorreader.app.engine.PageView, w: Int, h: Int, rtl: Boolean): Picture? {
        val pic = Picture()
        val c = pic.beginRecording(w, h)
        if (reflow) c.drawColor(background)
        val ok = if (v.wide || v.units.size == 1) {
            drawPage(c, v.units[0], p.slotFor(v, 0), w, h, if (rtl) Side.RIGHT else Side.LEFT, false)
        } else {
            val half = w / 2
            val leftIdx = if (rtl) 1 else 0
            val a = drawPage(c, v.units[leftIdx], p.slotFor(v, leftIdx), half, h, Side.LEFT, false)
            c.save()
            c.translate(half.toFloat(), 0f)
            val b = drawPage(c, v.units[1 - leftIdx], p.slotFor(v, 1 - leftIdx), w - half, h, Side.RIGHT, false)
            c.restore()
            a && b
        }
        pic.endRecording()
        return if (ok) pic else null
    }

    fun page(unit: PageUnit, slot: SlotSize, w: Int, h: Int, ribbonSide: Side, dim: Boolean): Picture? {
        val pic = Picture()
        val c = pic.beginRecording(w, h)
        if (reflow) c.drawColor(background)
        val ok = drawPage(c, unit, slot, w, h, ribbonSide, dim)
        pic.endRecording()
        return if (ok) pic else null
    }

    /** Same layout maths as PageSlot. False if the page isn't rendered (then no animation). */
    private fun drawPage(c: android.graphics.Canvas, unit: PageUnit, slot: SlotSize, w: Int, h: Int, ribbonSide: Side, dim: Boolean): Boolean {
        c.save()
        c.clipRect(0, 0, w, h)
        if (reflow) c.drawColor(background)
        if (unit !is BlankUnit) {
            val r = session.cached(p, unit, slot, 1f) ?: run {
                c.restore()
                return false
            }
            val bw = r.bitmap.width.toFloat()
            val bh = r.bitmap.height.toFloat()
            val scale = if (r.overflow) w / bw else min(w / bw, h / bh)
            val dw = bw * scale
            val dh = bh * scale
            val dx = (w - dw) / 2f
            val dy = if (r.overflow) 0f else (h - dh) / 2f
            c.drawBitmap(r.bitmap, null, RectF(dx, dy, dx + dw, dy + dh), bitmapPaint)
            if (dim) c.drawColor(android.graphics.Color.argb(153, 0, 0, 0))
            // Comics / PDF: the page number pill (books have it printed in the page).
            if (!reflow && settings.showHeader) p.pageNumber(unit)?.let { drawNumberPill(c, it.toString(), w, h, p.config.rtl) }
            // The bookmark ribbon on its page.
            if (session.bookmark?.let { unitHolds(unit, it) } == true) {
                drawRibbonNative(c, settings.bookmarkColor, ribbonSide, w.toFloat(), density)
            }
        }
        c.restore()
        return true
    }

    /** The HouseBadge pill (11 sp white on translucent black), bottom-right (bottom-left in RTL). */
    private fun drawNumberPill(c: android.graphics.Canvas, text: String, w: Int, h: Int, rtl: Boolean) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 11f * density
        }
        val padH = 10f * density
        val padV = 3f * density
        val gap = 6f * density
        val fm = tp.fontMetrics
        val pw = tp.measureText(text) + padH * 2
        val ph = (fm.descent - fm.ascent) + padV * 2
        val left = if (rtl) gap else w - gap - pw
        val top = h - gap - ph
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HouseStyle.Background.toArgb() }
        c.drawRoundRect(left, top, left + pw, top + ph, ph / 2f, ph / 2f, bg)
        c.drawText(text, left + padH, top + padV - fm.ascent, tp)
    }
}

// ---- drawing one page area ----------------------------------------------------------------------

/**
 * One page area of the turn on this screen ([left], [w] x [h] px): its role, the old and new
 * page pictures, and (for the curl) GPU textures + shader made on first use and released when
 * the turn ends.
 */
private class TurnRegion(
    val left: Float,
    val w: Int,
    val h: Int,
    val role: RegionRole,
    val side: Side?,
    /** The free (lifting) edge is on the left: the region's frame is mirrored. */
    val mirror: Boolean,
    val oldPic: Picture,
    val newPic: Picture,
    val radius: Float,
    /** Texture size factor: books full size (crisp), comics / PDF half size. */
    val texScale: Float,
    val theme: PageTheme,
    val reflow: Boolean,
    val startSide: Side,
) {
    private var oldBmp: Bitmap? = null
    private var newBmp: Bitmap? = null
    private var shader: RuntimeShader? = null
    private var curlTried = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint()
    private val camera = Camera()
    private val matrix = Matrix()

    fun release() {
        oldBmp?.recycle()
        newBmp?.recycle()
        oldBmp = null
        newBmp = null
        shader = null
    }

    fun draw(c: android.graphics.Canvas, turn: ReaderSession.Turn, t: Float, drag: ReaderSession.DragCurl?, screen: Screen) {
        c.save()
        c.translate(left, 0f)
        c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        // Comic pictures have see-through margins; the reader shows them black.
        if (!reflow) c.drawColor(android.graphics.Color.BLACK)
        val style = if (turn.style == TurnStyle.CURL && !prepareCurl()) TurnStyle.SLIDE else turn.style
        when (style) {
            TurnStyle.CURL -> drawCurl(c, turn, t, drag, screen)
            TurnStyle.FLIP -> drawFlip(c, turn, t)
            TurnStyle.SLIDE -> drawSlide(c, t)
            TurnStyle.FADE -> drawFade(c, t)
            TurnStyle.NONE -> c.drawPicture(newPic)
        }
        c.restore()
    }

    private fun sheet(forward: Boolean) = if (PageTurnMath.sheetIsOld(role, forward)) oldPic else newPic
    private fun under(forward: Boolean) = if (PageTurnMath.sheetIsOld(role, forward)) newPic else oldPic

    // FADE: a quick crossfade (the old page fades out as the new one fades in).
    private fun drawFade(c: android.graphics.Canvas, t: Float) {
        val a = (t.coerceIn(0f, 1f) * 255).toInt()
        if (reflow) {
            c.drawPicture(oldPic) // opaque pages: the new one simply fades in on top
        } else {
            c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), 255 - a)
            c.drawPicture(oldPic)
            c.restore()
        }
        c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), a)
        c.drawPicture(newPic)
        c.restore()
    }

    // SLIDE: the old page moves out, the new one in from the side the turn starts on, with a
    // light shadow on the new page's leading edge.
    private fun drawSlide(c: android.graphics.Canvas, t: Float) {
        val dir = if (startSide == Side.RIGHT) -1f else 1f
        val oldX = dir * w * t
        val newX = oldX - dir * w
        c.save(); c.translate(oldX, 0f); c.drawPicture(oldPic); c.restore()
        val edge = if (dir < 0) newX else newX + w
        val sw = w * 0.04f
        val k = (0.28f * sin(PI.toFloat() * t)).coerceAtLeast(0f)
        if (k > 0.01f) {
            val outward = if (dir < 0) -sw else sw
            shadePaint.shader = LinearGradient(edge, 0f, edge + outward, 0f, android.graphics.Color.argb((k * 255).toInt(), 0, 0, 0), 0, Shader.TileMode.CLAMP)
            c.drawRect(min(edge, edge + outward), 0f, max(edge, edge + outward), h.toFloat(), shadePaint)
            shadePaint.shader = null
        }
        c.save(); c.translate(newX, 0f); c.drawPicture(newPic); c.restore()
    }

    // FLIP: the sheet rotates around the spine like a door (slight perspective, darker toward 90°).
    private fun drawFlip(c: android.graphics.Canvas, turn: ReaderSession.Turn, t: Float) {
        val deg = when (role) {
            RegionRole.SOURCE -> 90f * PageTurnMath.localProgress(role, t)
            RegionRole.DEST -> 90f * (1f - PageTurnMath.localProgress(role, t))
            RegionRole.SINGLE -> 90f * PageTurnMath.singleU(t, turn.forward)
        }
        c.drawPicture(under(turn.forward))
        if (deg >= 89.5f) return
        // The spine (attached edge) is opposite the free edge.
        val spineX = if (mirror) w.toFloat() else 0f
        camera.save()
        camera.setLocation(0f, 0f, -3f * max(w, h) / 72f)
        camera.rotateY(if (mirror) deg else -deg) // the free edge lifts toward the reader
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-spineX, -h / 2f)
        matrix.postTranslate(spineX, h / 2f)
        c.save()
        c.concat(matrix)
        c.drawPicture(sheet(turn.forward))
        shadePaint.color = android.graphics.Color.argb((deg / 90f * 0.45f * 255).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shadePaint)
        c.restore()
    }

    // CURL: the GPU cylinder shader (see CURL_AGSL).
    private fun drawCurl(c: android.graphics.Canvas, turn: ReaderSession.Turn, t: Float, drag: ReaderSession.DragCurl?, screen: Screen) {
        val s = shader ?: return
        val sheetOld = PageTurnMath.sheetIsOld(role, turn.forward)
        var a = PageTurnMath.axisAt(role, t, w.toFloat(), radius, turn.forward)
        var theta = 0f
        if (drag != null && !turn.released && drag.screen == screen && drag.regionSide == side) {
            a = drag.curl.a
            theta = drag.curl.theta
        } else if (turn.released && role != RegionRole.DEST) {
            theta = PageTurnMath.thetaAt(turn.releaseTheta, turn.releaseT, turn.target, t)
        }
        s.setInputShader("sheet", textureShader(if (sheetOld) oldBmp!! else newBmp!!))
        s.setInputShader("under", textureShader(if (sheetOld) newBmp!! else oldBmp!!))
        s.setFloatUniform("size", w.toFloat(), h.toFloat())
        s.setFloatUniform("mirror", if (mirror) 1f else 0f)
        s.setFloatUniform("axisA", a)
        s.setFloatUniform("theta", theta)
        s.setFloatUniform("radius", radius)
        paint.shader = s
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    private val shaders = HashMap<Bitmap, BitmapShader>(2)

    private fun textureShader(b: Bitmap): BitmapShader = shaders.getOrPut(b) {
        BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(1f / texScale, 1f / texScale) })
        }
    }

    /** GPU textures + the shader, once per turn. On any failure: Slide, and curl off (logged). */
    private fun prepareCurl(): Boolean {
        if (TurnSupport.curlDisabledReason != null) return false
        if (curlTried) return shader != null
        curlTried = true
        return try {
            oldBmp = texture(oldPic)
            newBmp = texture(newPic)
            shader = RuntimeShader(CURL_AGSL).apply {
                // Back of the page: books show a faint, lightened mirror image of the page (85 %
                // paper tone); comics a plain paper-coloured back. Night stays dark.
                val night = theme == PageTheme.NIGHT
                val back = when {
                    reflow -> theme.paper
                    night -> androidx.compose.ui.graphics.Color(0xFF2A2A2A)
                    else -> androidx.compose.ui.graphics.Color(0xFFE9E4D8)
                }
                setFloatUniform("paper", back.red, back.green, back.blue, 1f)
                setFloatUniform("backPlain", if (reflow) 0f else 1f)
                setFloatUniform("shadowK", if (night && reflow) 0.5f else 0.32f)
            }
            true
        } catch (e: Throwable) {
            release()
            TurnSupport.disableCurl("${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "Curl could not start", e)
            false
        }
    }

    /** A picture rasterised on the GPU (hardware bitmap), at [texScale]. */
    private fun texture(pic: Picture): Bitmap {
        val tw = max(1, (w * texScale).toInt())
        val th = max(1, (h * texScale).toInt())
        val scaled = Picture()
        val c = scaled.beginRecording(tw, th)
        c.scale(texScale, texScale)
        c.drawPicture(pic)
        scaled.endRecording()
        return Bitmap.createBitmap(scaled, tw, th, Bitmap.Config.HARDWARE)
    }
}

/**
 * The page curl: a cylinder-deformation fragment shader, run on the GPU by Android's renderer
 * (OpenGL ES on the Thor). Region-local frame: x from the attached edge (hinge / spine) to the
 * free edge that lifts; the axis passes through (axisA, H/2) with normal (cos θ, sin θ). For
 * every pixel it inverts the mapping: the folded-back flap on top (back of the page), the bent
 * part (front, darker toward the crest, back with a soft highlight), the flat page, and the
 * page underneath with soft shadows cast by the bend and the flap.
 */
private const val CURL_AGSL = """
uniform shader sheet;
uniform shader under;
uniform float2 size;
uniform float mirror;
uniform float axisA;
uniform float theta;
uniform float radius;
uniform float backPlain;
uniform float4 paper;
uniform float shadowK;

const float PI = 3.14159265;

float2 toPx(float2 q) {
    return float2(mirror > 0.5 ? size.x - q.x : q.x, q.y);
}

float inside(float2 q) {
    return (q.x >= 0.0 && q.x <= size.x && q.y >= 0.0 && q.y <= size.y) ? 1.0 : 0.0;
}

float outsideDist(float2 q) {
    float2 e = max(max(-q, q - size), float2(0.0));
    return length(e);
}

// The back of the sheet at page point q. Its alpha is the page's own: a comic page's margins
// are transparent in the textures, so the back is only as big as the printed page.
half4 backOf(float2 q, float light) {
    half4 c = sheet.eval(toPx(q));
    half3 b = backPlain > 0.5 ? half3(paper.rgb) : mix(c.rgb, half3(paper.rgb), 0.85);
    return half4(b * half(light), c.a);
}

half4 main(float2 frag) {
    float2 p = float2(mirror > 0.5 ? size.x - frag.x : frag.x, frag.y);
    float2 n = float2(cos(theta), sin(theta));
    float2 A = float2(axisA, size.y * 0.5);
    float d = dot(p - A, n);
    float r = radius;
    if (d < 0.0) {
        // Flat part of the sheet, unless the folded-back flap lies on top of it.
        float2 qf = p + n * (PI * r - 2.0 * d);
        if (inside(qf) > 0.5) {
            half4 b = backOf(qf, 0.95);
            if (b.a > 0.5) return half4(b.rgb, 1.0);
        }
        // (Premultiplied colours: a transparent comic margin comes out black, as on screen.)
        half4 c = sheet.eval(frag);
        float sh = shadowK * 0.8 * (1.0 - smoothstep(0.0, r, outsideDist(qf)));
        return half4(c.rgb * half(1.0 - sh), 1.0);
    }
    if (d < r) {
        // On the cylinder: the back (upper) half first, then the front (lower) half.
        float s1 = r * asin(d / r);
        float s2 = PI * r - s1;
        float2 q2 = p + n * (s2 - d);
        if (inside(q2) > 0.5) {
            float k = (s2 / r - PI * 0.5) / (PI * 0.5);
            half4 b = backOf(q2, 0.80 + 0.15 * k);
            if (b.a > 0.5) return half4(b.rgb, 1.0);
        }
        float2 q1 = p + n * (s1 - d);
        if (inside(q1) > 0.5) {
            half4 c = sheet.eval(toPx(q1));
            if (c.a > 0.5) {
                float a1 = s1 / r;
                float light = 1.0 - 0.28 * sin(a1);
                float hl = max(0.0, 0.06 * (1.0 - abs(a1 - 0.35) * 3.0));
                return half4(c.rgb * half(light) + half(hl), 1.0);
            }
        }
    }
    // The page underneath, with the shadow cast by the curl's edge.
    half4 u = under.eval(frag);
    float sh = shadowK * (1.0 - smoothstep(0.0, r * 1.6, d - r * 0.6));
    return half4(u.rgb * half(1.0 - clamp(sh, 0.0, 1.0)), 1.0);
}
"""
