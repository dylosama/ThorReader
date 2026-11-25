package dev.thorreader.app.engine

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A 3D vector (book units: the book is 1 tall). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(dot(this))
    fun normalized(): V3 = length().let { if (it < 1e-6f) this else V3(x / it, y / it, z / it) }
}

/** A 3x3 rotation matrix, row-major. */
class Rot(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {
    fun apply(v: V3) = V3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    /** this then [o] (o · this). */
    fun then(o: Rot): Rot {
        val a = o.m
        val b = m
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return Rot(r)
    }

    /** Re-orthonormalise (avoids drift after many small rotations). */
    fun cleaned(): Rot {
        val x = V3(m[0], m[3], m[6]).normalized()
        var y = V3(m[1], m[4], m[7])
        y = (y - x * x.dot(y)).normalized()
        val z = V3(x.y * y.z - x.z * y.y, x.z * y.x - x.x * y.z, x.x * y.y - x.y * y.x)
        return Rot(floatArrayOf(x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z))
    }

    /** The rotation as (unit axis, angle in radians); the angle is 0..π. */
    fun toAxisAngle(): Pair<V3, Float> {
        val tr = m[0] + m[4] + m[8]
        val angle = kotlin.math.acos(((tr - 1f) / 2f).coerceIn(-1f, 1f))
        if (angle < 1e-4f) return V3(0f, 1f, 0f) to 0f
        if (kotlin.math.PI.toFloat() - angle < 1e-3f) {
            val x = sqrt(max(0f, (m[0] + 1f) / 2f))
            val y = sqrt(max(0f, (m[4] + 1f) / 2f))
            val z = sqrt(max(0f, (m[8] + 1f) / 2f))
            return V3(x, if (m[1] < 0f) -y else y, if (m[2] < 0f) -z else z).normalized() to angle
        }
        val s = 2f * sin(angle)
        return V3((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s).normalized() to angle
    }

    companion object {
        val IDENTITY = Rot()

        /** Rotation by [angle] radians around the unit [axis]. */
        fun axisAngle(axis: V3, angle: Float): Rot {
            val a = axis.normalized()
            val c = cos(angle)
            val s = sin(angle)
            val t = 1 - c
            return Rot(
                floatArrayOf(
                    t * a.x * a.x + c, t * a.x * a.y - s * a.z, t * a.x * a.z + s * a.y,
                    t * a.x * a.y + s * a.z, t * a.y * a.y + c, t * a.y * a.z - s * a.x,
                    t * a.x * a.z - s * a.y, t * a.y * a.z + s * a.x, t * a.z * a.z + c,
                ),
            )
        }
    }
}

/** The six faces of the book. */
enum class BookFace { FRONT, BACK, SPINE, FORE_EDGE, TOP, BOTTOM }

/**
 * The 3D book (Inspect book): a box 1 tall, [w] wide and [d] deep, centred at the origin; the
 * camera looks down +z from z = -[camDist] (x right, y down on screen). The front cover faces the
 * camera at rest; the spine is on the left (on the right for right-to-left books).
 */
class Book3D(val w: Float, val d: Float, val rtl: Boolean, val camDist: Float = CAM_DIST) {

    /** The face's corners as seen from outside, in texture order: top-left, top-right, bottom-right, bottom-left. */
    fun corners(face: BookFace): List<V3> {
        val x0 = -w / 2f
        val x1 = w / 2f
        val y0 = -0.5f
        val y1 = 0.5f
        val z0 = -d / 2f // front
        val z1 = d / 2f // back
        val spineX = if (rtl) x1 else x0
        val edgeX = if (rtl) x0 else x1
        return when (face) {
            BookFace.FRONT -> listOf(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y1, z0), V3(x0, y1, z0))
            BookFace.BACK -> listOf(V3(x1, y0, z1), V3(x0, y0, z1), V3(x0, y1, z1), V3(x1, y1, z1))
            // Looking at the spine, the front cover is on the right (left-to-right books).
            BookFace.SPINE -> if (!rtl) listOf(V3(spineX, y0, z1), V3(spineX, y0, z0), V3(spineX, y1, z0), V3(spineX, y1, z1))
            else listOf(V3(spineX, y0, z0), V3(spineX, y0, z1), V3(spineX, y1, z1), V3(spineX, y1, z0))
            BookFace.FORE_EDGE -> if (!rtl) listOf(V3(edgeX, y0, z0), V3(edgeX, y0, z1), V3(edgeX, y1, z1), V3(edgeX, y1, z0))
            else listOf(V3(edgeX, y0, z1), V3(edgeX, y0, z0), V3(edgeX, y1, z0), V3(edgeX, y1, z1))
            BookFace.TOP -> listOf(V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y0, z0), V3(x0, y0, z0))
            BookFace.BOTTOM -> listOf(V3(x0, y1, z0), V3(x1, y1, z0), V3(x1, y1, z1), V3(x0, y1, z1))
        }
    }

    /** Outward normal of a face (unrotated). */
    fun normal(face: BookFace): V3 = when (face) {
        BookFace.FRONT -> V3(0f, 0f, -1f)
        BookFace.BACK -> V3(0f, 0f, 1f)
        BookFace.SPINE -> V3(if (rtl) 1f else -1f, 0f, 0f)
        BookFace.FORE_EDGE -> V3(if (rtl) -1f else 1f, 0f, 0f)
        BookFace.TOP -> V3(0f, -1f, 0f)
        BookFace.BOTTOM -> V3(0f, 1f, 0f)
    }

    /** Is [face] turned toward the camera under rotation [r]? (A box is convex: these never overlap.) */
    fun visible(face: BookFace, r: Rot): Boolean {
        val c = corners(face).fold(V3(0f, 0f, 0f)) { a, v -> a + v } * 0.25f
        val center = r.apply(c)
        val toCam = V3(0f, 0f, -camDist) - center
        return r.apply(normal(face)).dot(toCam) > 1e-4f
    }

    /** Screen position (px) of book point [p] under [r], for a front height of [scalePx] px at rest. */
    fun project(p: V3, r: Rot, scalePx: Float, cx: Float, cy: Float): Pair<Float, Float> {
        val q = r.apply(p)
        val z = q.z + camDist
        // Focal length so that the front face (at z = camDist - d/2) is scalePx tall at rest.
        val f = scalePx * (camDist - d / 2f)
        return (cx + f * q.x / max(z, 0.05f)) to (cy + f * q.y / max(z, 0.05f))
    }

    /**
     * Brightness of a face under a soft warm studio light: a key light from the upper left in
     * front, plus ambient fill. 0.35 .. ~1.05.
     */
    fun brightness(face: BookFace, r: Rot): Float {
        val n = r.apply(normal(face))
        return 0.38f + 0.68f * max(0f, n.dot(LIGHT))
    }

    companion object {
        const val CAM_DIST = 4.5f

        /** Direction toward the key light (unit): up-left, in front of the book. */
        val LIGHT = V3(-0.45f, -0.55f, -1f).normalized()

        /** Trackball: a finger drag of ([dx], [dy]) px turns the book around the axis across the drag. */
        fun dragRotation(dx: Float, dy: Float, pxPerRadian: Float): Rot? {
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.01f) return null
            // Dragging right turns the front to the right (around +y); dragging down tips it down (around -x).
            return Rot.axisAngle(V3(dy / len, -dx / len, 0f), len / pxPerRadian)
        }

        /**
         * Printed pages for a book's source page count. Comics and PDFs count real pages. Books
         * (EPUB etc.) were counted at MuPDF's small reference page (196 × 227 pt, 10 pt text); a
         * printed paperback's text block is about 3.4 times that area, so ~3.4 reference pages
         * make one printed page (Dune: 1794 -> ~528, close to its real paperback).
         */
        fun printedPages(sourcePages: Int, reflowable: Boolean): Int =
            if (reflowable) (sourcePages / REF_PAGES_PER_PRINTED).toInt().coerceAtLeast(1) else sourcePages

        const val REF_PAGES_PER_PRINTED = 3.4f

        /**
         * Thickness / height of the book: ~0.1 mm per page plus 1 mm of covers, against an assumed
         * height (180 mm for books and manga, 260 mm for comics). [pages] = the source page count.
         */
        fun thicknessRatio(pages: Int, comic: Boolean): Float {
            val heightMm = if (comic) 260f else 180f
            val mm = pages.coerceAtLeast(1) * 0.1f + 1f
            return (mm / heightMm).coerceIn(0.008f, 0.6f)
        }
    }
}
