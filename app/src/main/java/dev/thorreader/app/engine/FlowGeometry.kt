package dev.thorreader.app.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * The library's cover flow: each book stands turned [ANGLE_DEG] so its spine and its front cover
 * (at an angle) both show. Plain maths (unit-tested).
 */
object FlowGeometry {
    /** How far each book is turned (0 = cover facing you, 90 = only the spine). */
    const val ANGLE_DEG = 34f

    /** Book height in the row, as a share of the space it has. */
    const val HEIGHT_SHARE = 0.78f

    /**
     * Spine width as a share of the book's height: the book's real thickness ([Book3D.thicknessRatio]),
     * but never so thin the spine can't be seen, nor so thick it hides the cover.
     */
    fun spineShare(thickness: Float): Float = thickness.coerceIn(0.06f, 0.22f)

    /** The camera's distance for the turned books (Compose's `cameraDistance`, its default). */
    const val CAMERA = 8f

    /** [CAMERA] in pixels: the render node puts the camera this many units (72 a unit) away. */
    const val CAMERA_PX = CAMERA * 72f

    /**
     * How wide a face [w] wide looks when hinged on its near edge and turned [angleDeg] away,
     * seen from [cameraPx] (perspective: the far edge is further, so it comes in).
     */
    fun projected(w: Float, angleDeg: Float, cameraPx: Float = Float.POSITIVE_INFINITY): Float {
        val a = Math.toRadians(angleDeg.toDouble())
        val depth = w * sin(a)
        return (w * cos(a) / (1.0 + depth / cameraPx)).toFloat()
    }

    /** The width a turned book takes on screen: the foreshortened cover plus the visible spine. */
    fun footprint(coverW: Float, spineW: Float, angleDeg: Float = ANGLE_DEG, cameraPx: Float = Float.POSITIVE_INFINITY): Float =
        projected(coverW, angleDeg, cameraPx) + projected(spineW, 90f - angleDeg, cameraPx)
}
