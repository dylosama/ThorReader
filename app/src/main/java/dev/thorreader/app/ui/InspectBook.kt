package dev.thorreader.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.PageTheme
import dev.thorreader.app.TAG
import dev.thorreader.app.ThorApp
import dev.thorreader.app.data.Book
import dev.thorreader.app.engine.Book3D
import dev.thorreader.app.engine.BookFace
import dev.thorreader.app.engine.Rot
import dev.thorreader.app.engine.V3
import dev.thorreader.app.importer.BookFormat
import dev.thorreader.app.io.BackCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The faces' pictures for the 3D book. */
class InspectTextures(
    val front: Bitmap,
    val back: Bitmap,
    val spine: Bitmap,
    /** Fore-edge (page lines run up and down). */
    val edgeSide: Bitmap,
    /** Top / bottom (page lines run across). */
    val edgeTopBottom: Bitmap,
    /** The back cover came from the book file (not generated). */
    val ownBack: Boolean,
) {
    fun recycle() = listOf(front, back, spine, edgeSide, edgeTopBottom).forEach { if (!it.isRecycled) it.recycle() }
}

/**
 * "Inspect book": the state of the 3D book on the top screen. Everything the Canvas reads is
 * snapshot state, so it only redraws when something changes (a drag, a coast, an animation).
 */
class InspectState(val book: Book, val from: RectF?) {
    val comic = runCatching { BookFormat.valueOf(book.format).comic }.getOrDefault(false)
    private val reflowable = runCatching { BookFormat.valueOf(book.format).reflowable }.getOrDefault(true)

    /** Pages as printed (books: estimated from the source count; comics / PDF: real pages). */
    val printedPages: Int = Book3D.printedPages(book.pageCount, reflowable)
    val pagesEstimated: Boolean = reflowable

    /** Thickness from the SOURCE page count (comic images / PDF pages / EPUB reference pages). */
    var model by mutableStateOf(Book3D(0.66f, Book3D.thicknessRatio(printedPages, comic && !book.rtl), book.rtl))
        private set
    var textures by mutableStateOf<InspectTextures?>(null)
        private set
    var rot by mutableStateOf(Rot.IDENTITY)
    var zoom by mutableFloatStateOf(1f)

    /** 0 = still in its library row, 1 = the 3D book in the middle (enter / exit animation). */
    var enter by mutableFloatStateOf(0f)

    /** Last touch / button (restarts the idle-spin timer). */
    var lastTouch by mutableLongStateOf(System.nanoTime())
    var closing by mutableStateOf(false)

    fun touched() {
        lastTouch = System.nanoTime()
    }

    suspend fun load(app: ThorApp) {
        val front = app.loadCover(book)
        val aspect = front.width.toFloat() / max(1, front.height)
        model = Book3D(aspect, Book3D.thicknessRatio(printedPages, comic && !book.rtl), book.rtl)
        textures = withContext(Dispatchers.IO) { buildTextures(app, book, front, model) }
        Log.i(TAG, "Inspect '${book.title}': ${book.pageCount} source pages (~$printedPages printed), thickness ${"%.3f".format(model.d)} of the height, ${if (textures?.ownBack == true) "the book's own back cover" else "generated back cover"}")
    }
}

// ---- textures ------------------------------------------------------------------------------------

private fun averageColor(b: Bitmap): Int {
    val s = Bitmap.createScaledBitmap(b, 8, 8, true)
    var r = 0
    var g = 0
    var bl = 0
    for (y in 0 until 8) for (x in 0 until 8) {
        val c = s.getPixel(x, y)
        r += c shr 16 and 0xFF
        g += c shr 8 and 0xFF
        bl += c and 0xFF
    }
    if (s != b) s.recycle()
    return android.graphics.Color.rgb(r / 64, g / 64, bl / 64)
}

private fun shade(c: Int, k: Float) = android.graphics.Color.rgb(
    (android.graphics.Color.red(c) * k).toInt().coerceIn(0, 255),
    (android.graphics.Color.green(c) * k).toInt().coerceIn(0, 255),
    (android.graphics.Color.blue(c) * k).toInt().coerceIn(0, 255),
)

private fun textOn(c: Int) = if (dev.thorreader.app.AccentMath.prefersDarkText(c)) android.graphics.Color.rgb(20, 20, 20) else android.graphics.Color.WHITE

private fun buildTextures(app: ThorApp, book: Book, front: Bitmap, model: Book3D): InspectTextures {
    val base = averageColor(front)
    val dark = shade(base, 0.55f)
    // Back cover: the book's own, else generated in the cover's colours.
    val own = BackCovers.find(app, book)?.let { bytes ->
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 1400) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    val back = own ?: generatedBack(book, front.width, front.height, base, dark)
    val texH = 1200
    val spineW = (texH * model.d).toInt().coerceIn(24, 700)
    return InspectTextures(front, back, generatedSpine(book, spineW, texH, base, dark), pageEdges(spineW, texH, vertical = true, dark), pageEdges((texH * model.w).toInt().coerceAtLeast(64), spineW, vertical = false, dark), own != null)
}

private fun generatedBack(book: Book, w0: Int, h0: Int, base: Int, dark: Int): Bitmap {
    val w = 800
    val h = (800f * h0 / max(1, w0)).toInt().coerceIn(400, 1600)
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), shade(base, 0.8f), dark, Shader.TileMode.CLAMP) })
    val ink = textOn(shade(base, 0.7f))
    var y = 70f
    fun block(text: String, size: Float, bold: Boolean, alpha: Int, maxLines: Int): Float {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            this.alpha = alpha
            textSize = size
            isFakeBoldText = bold
        }
        val l = StaticLayout.Builder.obtain(text, 0, text.length, tp, w - 120).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0f, 1.15f).build()
        c.save(); c.translate(60f, y); l.draw(c); c.restore()
        return l.height.toFloat()
    }
    y += block(book.title, 46f, true, 255, 3) + 14f
    book.author?.let { y += block(it, 30f, false, 210, 2) + 34f }
    book.description?.let { y += block(it, 25f, false, 200, ((h - y - 120) / 30f).toInt().coerceAtLeast(1)) }
    // A small barcode-like block, for the look of a real back cover.
    val p = Paint().apply { color = ink; alpha = 150 }
    var x = w - 220f
    while (x < w - 70f) {
        c.drawRect(x, h - 130f, x + (if ((x / 7).toInt() % 3 == 0) 5f else 2.5f), h - 70f, p)
        x += 7f
    }
    return b
}

private fun generatedSpine(book: Book, w: Int, h: Int, base: Int, dark: Int): Bitmap {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    // Rounded look: darker toward both edges.
    c.drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(dark, shade(base, 0.95f), dark), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    })
    if (w >= 28) {
        val ink = textOn(shade(base, 0.8f))
        val text = listOfNotNull(book.title, book.author).joinToString("   ·   ")
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = min(w * 0.5f, 44f)
            isFakeBoldText = true
        }
        val ell = android.text.TextUtils.ellipsize(text, tp, h - 80f, android.text.TextUtils.TruncateAt.END).toString()
        c.save()
        // Reads top to bottom, as on most spines.
        c.translate(w / 2f + tp.textSize * 0.35f, 40f)
        c.rotate(90f)
        c.drawText(ell, 0f, 0f, tp)
        c.restore()
    }
    return b
}

/** Paper-tone page block with fine page lines; [dark] = the covers' colour at both ends. */
private fun pageEdges(w: Int, h: Int, vertical: Boolean, dark: Int): Bitmap {
    val b = Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(android.graphics.Color.rgb(0xEC, 0xE4, 0xD2))
    val line = Paint().apply { strokeWidth = 1f }
    val across = if (vertical) w else h
    var i = 2
    while (i < across - 2) {
        line.color = android.graphics.Color.argb(if (i % 6 == 0) 46 else 22, 90, 70, 40)
        if (vertical) c.drawLine(i.toFloat(), 0f, i.toFloat(), h.toFloat(), line) else c.drawLine(0f, i.toFloat(), w.toFloat(), i.toFloat(), line)
        i += 3
    }
    // The cover boards at both ends.
    val board = Paint().apply { color = dark }
    val t = max(2f, across * 0.03f)
    if (vertical) {
        c.drawRect(0f, 0f, t, h.toFloat(), board); c.drawRect(w - t, 0f, w.toFloat(), h.toFloat(), board)
    } else {
        c.drawRect(0f, 0f, w.toFloat(), t, board); c.drawRect(0f, h - t, w.toFloat(), h.toFloat(), board)
    }
    return b
}

// ---- drawing -------------------------------------------------------------------------------------

private val faceMatrix = Matrix()
private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
private val src = FloatArray(8)
private val dst = FloatArray(8)

/** Draws the book: each face the camera sees, its picture mapped onto its projected corners. */
private fun drawBook(c: android.graphics.Canvas, s: InspectState, t: InspectTextures, heightPx: Float, cx: Float, cy: Float) {
    val m = s.model
    val r = s.rot
    for (face in BookFace.entries) {
        if (!m.visible(face, r)) continue
        val bmp = when (face) {
            BookFace.FRONT -> t.front
            BookFace.BACK -> t.back
            BookFace.SPINE -> t.spine
            BookFace.FORE_EDGE -> t.edgeSide
            BookFace.TOP, BookFace.BOTTOM -> t.edgeTopBottom
        }
        if (bmp.isRecycled) continue
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        src[0] = 0f; src[1] = 0f; src[2] = bw; src[3] = 0f; src[4] = bw; src[5] = bh; src[6] = 0f; src[7] = bh
        m.corners(face).forEachIndexed { i, p ->
            val (x, y) = m.project(p, r, heightPx, cx, cy)
            dst[i * 2] = x
            dst[i * 2 + 1] = y
        }
        if (!faceMatrix.setPolyToPoly(src, 0, dst, 0, 4)) continue
        // Warm studio light: brightness by how the face turns to the key light.
        val k = m.brightness(face, r)
        val mul = android.graphics.Color.rgb((255 * min(1f, k)).toInt(), (255 * min(1f, k * 0.96f)).toInt(), (255 * min(1f, k * 0.88f)).toInt())
        val add = ((k - 1f).coerceAtLeast(0f) * 60).toInt()
        facePaint.colorFilter = LightingColorFilter(mul, android.graphics.Color.rgb(add, add, (add * 0.7f).toInt()))
        c.save()
        c.concat(faceMatrix)
        c.drawBitmap(bmp, 0f, 0f, facePaint)
        c.restore()
    }
}

/**
 * The Inspect screen on the top screen: the library hidden behind a warm studio backdrop, the 3D
 * book, and a round X where the gear is. One finger spins it (with inertia), two fingers zoom,
 * a quick tap on the book reads it. Idle spin after 3 s, at most 20 s.
 */
@Composable
fun InspectOverlay(s: InspectState) {
    val app = ThorApp.instance
    val night = app.settings.theme == PageTheme.NIGHT
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(s) { s.load(app) }
    // Enter: lift out of the row into the middle (and the reverse when closing).
    LaunchedEffect(s, s.closing) {
        val from = s.enter
        val to = if (s.closing) 0f else 1f
        val startRot = s.rot
        val (axis, angle) = startRot.toAxisAngle()
        val ms = if (s.closing) 420f else 480f
        var start = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (start < 0) start = now
            val x = ((now - start) / 1_000_000f / ms).coerceIn(0f, 1f)
            val e = dev.thorreader.app.engine.PageTurnMath.easeInOut(x)
            s.enter = from + (to - from) * e
            // Closing: turn back to the front while shrinking into the row.
            if (s.closing) {
                s.rot = Rot.axisAngle(axis, angle * (1f - min(1f, x * 1.6f)))
                s.zoom = s.zoom + (1f - s.zoom) * 0.2f
            }
            if (x >= 1f) break
        }
        if (s.closing) app.endInspect(s)
    }
    // Idle spin: starts 3 s after the last touch, stops on touch and after 20 s.
    LaunchedEffect(s, s.lastTouch, s.textures != null, s.closing) {
        if (s.textures == null || s.closing) return@LaunchedEffect
        delay(3000)
        var last = -1L
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            if (last >= 0) s.rot = s.rot.then(Rot.axisAngle(V3(0f, 1f, 0f), 0.45f * (now - last) / 1e9f)).cleaned()
            last = now
            if (now - start > 20_000_000_000L) break
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(s) {
                    // One finger: spin (trackball) with inertia; two fingers: zoom; a quick, still
                    // tap on the book: read it (straight away - there is no double-tap to wait for).
                    val pxPerRad = 260.dp.toPx()
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        s.touched()
                        val vt = VelocityTracker()
                        var multi = false
                        var moved = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                multi = true
                                s.zoom = (s.zoom * ev.calculateZoom()).coerceIn(0.6f, 2.4f)
                            } else if (!multi) {
                                val ch = pressed[0]
                                val d = ch.positionChange()
                                vt.addPosition(ch.uptimeMillis, ch.position)
                                moved = max(moved, (ch.position - down.position).getDistance())
                                // Within the tap slop the book doesn't turn yet (a tap stays a tap).
                                if (moved > slop) Book3D.dragRotation(d.x, d.y, pxPerRad)?.let { s.rot = s.rot.then(it).cleaned() }
                            }
                            ev.changes.forEach { it.consume() }
                            s.touched()
                        }
                        if (multi) return@awaitEachGesture
                        val heldMs = (currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis) - down.uptimeMillis
                        if (InspectGestures.isTap(moved, heldMs, slop)) {
                            if (s.bookRect(w, h).contains(down.position.x, down.position.y)) app.readFromInspect()
                            return@awaitEachGesture
                        }
                        // Coast: keep turning and slow down (stops when touched again).
                        val v = vt.calculateVelocity()
                        val touchedAt = s.lastTouch
                        scope.launch {
                            var vx = v.x
                            var vy = v.y
                            var last = -1L
                            while (sqrt(vx * vx + vy * vy) > 30f && s.lastTouch == touchedAt) {
                                val now = withFrameNanos { it }
                                if (last >= 0) {
                                    val dt = (now - last) / 1e9f
                                    Book3D.dragRotation(vx * dt, vy * dt, pxPerRad)?.let { s.rot = s.rot.then(it).cleaned() }
                                    vx *= 0.93f
                                    vy *= 0.93f
                                }
                                last = now
                            }
                        }
                    }
                },
        ) {
            val e = s.enter
            // Studio backdrop (the library behind fades away).
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                val bgPaint = Paint().apply {
                    shader = RadialGradient(
                        w * 0.5f, h * 0.42f, max(w, h) * 0.75f,
                        if (night) android.graphics.Color.rgb(0x22, 0x1D, 0x18) else android.graphics.Color.rgb(0x4A, 0x3E, 0x32),
                        if (night) android.graphics.Color.rgb(0x05, 0x05, 0x05) else android.graphics.Color.rgb(0x14, 0x11, 0x0E),
                        Shader.TileMode.CLAMP,
                    )
                    alpha = (e * 255).toInt()
                }
                nc.drawRect(0f, 0f, w, h, bgPaint)
                val tex = s.textures ?: s.let { null }
                // Where the book is: from its row (e = 0) to the middle of the screen (e = 1).
                val target = h * 0.66f * s.zoom
                val rowH = s.from?.height() ?: (h * 0.2f)
                val heightPx = rowH + (target - rowH) * e
                val fx = s.from?.centerX() ?: (w / 2f)
                val fy = s.from?.centerY() ?: (h / 2f)
                val cx = fx + (w / 2f - fx) * e
                val cy = fy + (h * 0.47f - fy) * e
                // Soft floor shadow under the book.
                val sw = heightPx * s.model.w * 0.75f
                val shadow = Paint().apply {
                    shader = RadialGradient(cx, cy + heightPx * 0.55f, max(1f, sw), android.graphics.Color.argb((120 * e).toInt(), 0, 0, 0), 0, Shader.TileMode.CLAMP)
                }
                nc.save()
                nc.scale(1f, 0.18f, cx, cy + heightPx * 0.55f)
                nc.drawCircle(cx, cy + heightPx * 0.55f, sw, shadow)
                nc.restore()
                if (tex != null) {
                    drawBook(nc, s, tex, heightPx, cx, cy)
                } else {
                    // Still loading: the flat cover from the row.
                    s.from?.let { r ->
                        val p = Paint().apply { color = android.graphics.Color.rgb(0x2A, 0x2A, 0x2A) }
                        nc.drawRect(cx - r.width() / 2, cy - r.height() / 2, cx + r.width() / 2, cy + r.height() / 2, p)
                    }
                }
            }
        }
        // The only control: a round X where the gear normally is.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .border(1.dp, HouseStyle.Hairline, CircleShape)
                .focusClick(CircleShape) { app.closeInspect("X") },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color.White, fontSize = 20.sp)
        }
    }
}

/**
 * Where the front cover is on screen right now, as the book is turned (for "Read": the open
 * animation starts exactly there).
 */
fun InspectState.frontRect(w: Float, h: Float): RectF = projectedRect(w, h, listOf(BookFace.FRONT))

/** The whole book on screen right now (a tap inside it = Read). */
fun InspectState.bookRect(w: Float, h: Float): RectF = projectedRect(w, h, BookFace.entries)

private fun InspectState.projectedRect(w: Float, h: Float, faces: List<BookFace>): RectF {
    val target = h * 0.66f * zoom
    val cx = w / 2f
    val cy = h * 0.47f
    val pts = faces.flatMap { model.corners(it) }.map { model.project(it, rot, target, cx, cy) }
    return RectF(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
}

/** Inspect's tap rule, kept apart so it can be tested. */
object InspectGestures {
    /** Longest press that still counts as a tap. */
    const val TAP_MS = 300L

    /** A tap = the finger moved less than [slop] px and lifted within [TAP_MS]; else a spin. */
    fun isTap(movedPx: Float, heldMs: Long, slop: Float): Boolean = movedPx < slop && heldMs <= TAP_MS
}

/**
 * The bottom screen while inspecting (taken over like the keyboard): the book's details, the
 * full description (scrolls) and a "Read" button.
 */
@Composable
fun InspectInfo(s: InspectState) {
    val app = ThorApp.instance
    val b = s.book
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E0E))
            .padding(20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                Text(b.title, color = Color.White, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2)
                b.author?.let { Text(it, color = SubtleText, fontSize = 15.sp, maxLines = 2) }
                b.series?.let { sr ->
                    Text(sr + (b.seriesNumber?.let { " #" + dev.thorreader.app.importer.formatNumber(it) } ?: ""), color = Accent, fontSize = 14.sp)
                }
            }
            // ↺: back to the front view (also the Y button).
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E2E2E))
                    .border(1.dp, HouseStyle.Hairline, CircleShape)
                    .focusClick(CircleShape) { app.resetInspectView("↺") },
                contentAlignment = Alignment.Center,
            ) {
                Text("↺", color = Color.White, fontSize = 22.sp)
            }
            // "Read": opens the book with the normal open animation, from the 3D book.
            Box(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(Accent)
                    .focusClick(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { app.readFromInspect() }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Text("Read", color = OnAccent, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
        val facts = listOfNotNull(
            LibraryLogic.typeOf(b),
            b.year?.let { "Published $it" },
            if (b.pageCount > 0) (if (s.pagesEstimated) "≈ ${s.printedPages} printed pages" else "${s.printedPages} pages") else null,
            when {
                b.completed -> "Finished"
                b.lastOpenedAt == 0L -> "Not started"
                else -> "${(b.progress * 100).toInt()} % read" + (if (b.lastPage > 0 && b.layoutPages > 0) " (page ${b.lastPage} of ${b.layoutPages})" else "")
            },
        )
        Text(facts.joinToString("  ·  "), color = Color.White, fontSize = 14.sp)
        dev.thorreader.app.metadata.Genres.split(b.genres).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), color = SubtleText, fontSize = 13.sp)
        }
        Box(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(b.description ?: "No description.", color = Color(0xFFCCCCCC), fontSize = 15.sp, lineHeight = 22.sp)
        }
        Text(INSPECT_HINT, color = Color(0xFF777777), fontSize = 12.sp)
    }
}

/** The help line under Inspect's details (bottom screen). */
const val INSPECT_HINT = "Tap the book to read · Drag to spin · Pinch to zoom · ↺ or Y resets · B or ✕ closes"
