package dev.thorreader.app.ui

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.thorreader.app.PageTheme
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.Screen
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.BookAnimMath
import dev.thorreader.app.engine.CoverBox
import dev.thorreader.app.engine.SwingAxis
import kotlin.math.max

/**
 * The book open / close animation on one physical screen (unrotated screen pixels). Composed
 * only while the animation runs; reads the shared clock in the draw phase. Any tap skips to the
 * end. Drawn over everything else on the screen.
 */
@Composable
fun BookAnimOverlay(screen: Screen) {
    val app = ThorApp.instance
    val anim = app.bookAnim ?: return
    if (!anim.uses(screen)) return
    val painter = remember(anim) { BookAnimPainter(anim) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(anim) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    app.skipBookAnim()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = app.bookAnimT.floatValue
            drawIntoCanvas { painter.draw(it.nativeCanvas, screen, t, size.width, size.height) }
        }
        if (app.bookAnimWaiting && (screen == Screen.TOP) != (anim.mode == ReadingMode.BOTTOM || (anim.mode.isBook && anim.rtl))) {
            HouseProgressBadge("Opening…", Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

private class BookAnimPainter(val a: ThorApp.BookAnim) {
    private val app = ThorApp.instance
    private val m = app.screens
    private val wt = m.topW.toFloat()
    private val ht = m.topH.toFloat()
    private val wb = m.bottomW.toFloat()
    private val hb = m.bottomH.toFloat()
    private val offset = BookAnimMath.bottomOffset(wt, wb, ht)
    private val aspect = a.cover.width.toFloat() / max(1, a.cover.height)
    private val place = BookAnimMath.place(a.mode, a.rtl, wt, ht, wb, hb, aspect)
    private val night = app.settings.theme == PageTheme.NIGHT

    /** The cover's row in the library; if unknown, it rises from the middle of the top screen. */
    private val from: CoverBox = a.from?.let { CoverBox(it.centerX(), it.centerY(), it.width(), it.height(), 0f) }
        ?: CoverBox(wt / 2f, ht / 2f, ht * 0.25f * aspect, ht * 0.25f, 0f)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camera = Camera()
    private val swing = Matrix()

    /** Library rows' background (where the cover was lifted out of its row). */
    private val rowColor = android.graphics.Color.rgb(0x1A, 0x1A, 0x1A)

    fun draw(c: android.graphics.Canvas, screen: Screen, t: Float, w: Float, h: Float) {
        val s = BookAnimMath.openState(a.open, t)
        val shot = a.snapshots[screen]
        if (a.fade) {
            // Quick fade: what the screen showed before fades into what it shows now.
            val alpha = 1f - t
            if (shot != null) drawBitmapAlpha(c, shot, w, h, alpha) else dark(c, w, h, alpha)
            return
        }
        val reveal = BookAnimMath.revealFor(s, a.hinge)
        val dim = BookAnimMath.dimAt(s)
        if (a.open) {
            // Before: the library (top) or nothing of ours (bottom), dimming; it gives way to the pages.
            if (screen == Screen.TOP && shot != null) {
                drawBitmapAlpha(c, shot, w, h, 1f - reveal)
                // The cover has been lifted out of its row.
                if (a.fromRow) a.from?.let { r ->
                    fill.color = rowColor
                    fill.alpha = ((1f - reveal) * 255).toInt()
                    c.drawRect(r, fill)
                }
            } else if (shot == null) {
                dark(c, w, h, 1f - reveal)
            }
            dark(c, w, h, dim * (1f - reveal))
        } else {
            // Closing: the live library is underneath; the pages (snapshot) fade into it.
            if (screen == Screen.TOP && s > 0.001f && a.fromRow) a.from?.let { r ->
                // Its row stays empty until the cover lands back in it.
                fill.color = rowColor
                fill.alpha = 255
                c.drawRect(r, fill)
            }
            dark(c, w, h, dim * (1f - reveal))
            if (shot != null) drawBitmapAlpha(c, shot, w, h, reveal) else if (screen == Screen.BOTTOM) dark(c, w, h, reveal)
        }
        drawCover(c, screen, s)
    }

    private fun dark(c: android.graphics.Canvas, w: Float, h: Float, alpha: Float) {
        if (alpha <= 0.001f) return
        fill.color = android.graphics.Color.BLACK
        fill.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawRect(0f, 0f, w, h, fill)
    }

    private fun drawBitmapAlpha(c: android.graphics.Canvas, b: Bitmap, w: Float, h: Float, alpha: Float) {
        if (alpha <= 0.001f || b.isRecycled) return
        bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawBitmap(b, null, RectF(0f, 0f, w, h), bitmapPaint)
        bitmapPaint.alpha = 255
    }

    /** The cover in the virtual space of both screens, shifted to this screen. */
    private fun drawCover(c: android.graphics.Canvas, screen: Screen, s: Float) {
        if (a.cover.isRecycled) return
        val box = if (a.hinge) BookAnimMath.hingeBoxAt(s, from, place.box, wt, ht) else BookAnimMath.boxAt(s, from, place.box)
        val deg = BookAnimMath.swingDegFor(s, a.hinge)
        if (deg >= 179.5f) return
        c.save()
        if (screen == Screen.BOTTOM) c.translate(-offset.first, -offset.second)
        if (deg > 0f) {
            // Swing around the spine; the free edge lifts toward the reader.
            camera.save()
            camera.setLocation(0f, 0f, -3f * max(place.box.w, place.box.h) / 72f)
            when (place.axis) {
                SwingAxis.VERTICAL -> camera.rotateY(if (place.freeEdgeNegative) deg else -deg)
                SwingAxis.HORIZONTAL -> camera.rotateX(if (place.freeEdgeNegative) -deg else deg)
            }
            camera.getMatrix(swing)
            camera.restore()
            val px = if (place.axis == SwingAxis.VERTICAL) BookAnimMath.spineOf(box, place) else box.cx
            val py = if (place.axis == SwingAxis.HORIZONTAL) BookAnimMath.spineOf(box, place) else box.cy
            swing.preTranslate(-px, -py)
            swing.postTranslate(px, py)
            c.concat(swing)
        }
        c.rotate(box.rot, box.cx, box.cy)
        val dst = RectF(box.left, box.top, box.right, box.bottom)
        val radius = 6f
        // Soft lifted shadow (while the cover is still mostly flat).
        val sh = BookAnimMath.shadowFor(s, a.hinge)
        if (sh > 0.01f && deg < 60f) {
            shadowPaint.color = android.graphics.Color.BLACK
            shadowPaint.setShadowLayer(28f * sh + 4f, 0f, 10f * sh, android.graphics.Color.argb((140 * sh).toInt(), 0, 0, 0))
            c.drawRoundRect(dst, radius, radius, shadowPaint)
        }
        if (deg <= 90f) {
            c.drawBitmap(a.cover, null, dst, bitmapPaint)
            // Darker as it turns away from the light.
            if (deg > 0f) {
                fill.color = android.graphics.Color.BLACK
                fill.alpha = (deg / 90f * 0.45f * 255).toInt()
                c.drawRect(dst, fill)
            }
        } else {
            // The inside of the cover, fading out as it lands to reveal the page under it.
            val k = 1f - (deg - 90f) / 90f
            val paper = if (night) android.graphics.Color.rgb(0x26, 0x26, 0x26) else android.graphics.Color.rgb(0xEE, 0xE9, 0xDF)
            fill.color = paper
            fill.alpha = (k * 255).toInt()
            c.drawRect(dst, fill)
            // A little shade near the spine.
            fill.shader = LinearGradient(dst.left, 0f, dst.left + dst.width() * 0.15f, 0f, android.graphics.Color.argb((60 * k).toInt(), 0, 0, 0), 0, Shader.TileMode.CLAMP)
            c.drawRect(dst, fill)
            fill.shader = null
        }
        c.restore()
    }
}

/** A generated cover for books without one (like the library's placeholder), for the animation. */
fun placeholderCoverBitmap(title: String, author: String?): Bitmap {
    val w = 600
    val h = 900
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val hue = ((title.hashCode() and 0x7FFFFFFF) % 360).toFloat()
    val top = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.38f))
    val bottom = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.22f))
    val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    fun text(s: String, size: Float, color: Int, y: Float, max: Int) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
        }
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, tp, w - 80)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(max).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save()
        c.translate(40f, y)
        layout.draw(c)
        c.restore()
    }
    text(title, 54f, android.graphics.Color.WHITE, 90f, 6)
    if (author != null) text(author, 34f, android.graphics.Color.argb(180, 255, 255, 255), h - 170f, 2)
    return b
}
