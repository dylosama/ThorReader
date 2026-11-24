package dev.thorreader.app

/*
 * ============================================================================================
 *  UNUSED (milestone 2+). Kept because automatic orientation detection may return later.
 *
 *  Milestone 1 classified NORMAL / BOOK_LEFT / BOOK_RIGHT from the gravity sensor. On-device
 *  testing on the AYN Thor showed it was unreliable, so the app now uses four MANUAL reading
 *  modes (see ReadingMode). Nothing in the app references this file. If it returns, wire
 *  OrientationSensor.start()/stop() into an Activity's onResume/onPause and map its result onto
 *  ReadingMode.BOOK / BOOK_REVERSED / TOP.
 * ============================================================================================
 */

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

enum class SensedOrientation { NORMAL, BOOK_LEFT, BOOK_RIGHT }

/**
 * Reads TYPE_GRAVITY (falls back to TYPE_ACCELEROMETER), low-pass filters it and reports a new
 * orientation only after it has been stable for 400 ms and clearly past the thresholds.
 * Angle = atan2(x, y) of gravity in the screen plane minus [normalAngleDeg]; standard axes give
 * +90 when the device is turned counter-clockwise. If less than ~34% of gravity is in the screen
 * plane the device is lying flat and the current orientation is kept.
 */
class OrientationSensor(
    context: Context,
    private val normalAngleDeg: Float = 0f,
    private val swapLeftRight: Boolean = false,
    private val onChange: (SensedOrientation) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val isAccelerometer = sensor?.type == Sensor.TYPE_ACCELEROMETER

    private val filtered = FloatArray(3)
    private var hasFiltered = false
    private var current = SensedOrientation.NORMAL
    private var candidate: SensedOrientation? = null
    private var candidateSinceNs = 0L

    fun start() {
        sensor ?: return
        hasFiltered = false
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = if (isAccelerometer) 0.15f else 0.5f
        if (!hasFiltered) {
            event.values.copyInto(filtered, 0, 0, 3)
            hasFiltered = true
        } else {
            for (i in 0..2) filtered[i] += alpha * (event.values[i] - filtered[i])
        }
        val (x, y, z) = Triple(filtered[0], filtered[1], filtered[2])
        val seen = classify(x, y, z)
        if (seen == null || seen == current) {
            candidate = null
        } else if (seen != candidate) {
            candidate = seen
            candidateSinceNs = event.timestamp
        } else if (event.timestamp - candidateSinceNs >= 400_000_000L) {
            Log.i(TAG, "OrientationSensor: $current -> $seen (x=%.2f y=%.2f z=%.2f)".format(x, y, z))
            current = seen
            candidate = null
            onChange(seen)
        }
    }

    private fun classify(x: Float, y: Float, z: Float): SensedOrientation? {
        val norm = sqrt(x * x + y * y + z * z)
        val inPlane = sqrt(x * x + y * y)
        if (norm < 1f || inPlane < 0.34f * norm) return null // flat: keep current
        var rel = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat() - normalAngleDeg
        while (rel > 180f) rel -= 360f
        while (rel <= -180f) rel += 360f
        if (swapLeftRight) rel = -rel
        return when {
            abs(rel) <= 30f -> SensedOrientation.NORMAL
            rel in 60f..135f -> SensedOrientation.BOOK_LEFT
            rel in -135f..-60f -> SensedOrientation.BOOK_RIGHT
            else -> null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
