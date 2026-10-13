package dev.navframe.app

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import dev.navframe.core.GeoPoint
import dev.navframe.core.normalizeBearing

/** Absolute orientation of the screen's top edge; coordinates are only used in memory for declination. */
internal class AndroidCompass(context: Context, private val changed: () -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val windows = context.getSystemService(WindowManager::class.java)
    private var sensor: Sensor? = null
    private var degrees = Float.NaN
    private var measuredAt = 0L
    private var declination = 0f
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private val matrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)

    fun start() {
        sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    fun updatePosition(point: GeoPoint) {
        declination = GeomagneticField(point.latitude.toFloat(), point.longitude.toFloat(), 0f, System.currentTimeMillis()).declination
    }
    fun heading(): Float = if (degrees.isFinite() && accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && SystemClock.elapsedRealtime() - measuredAt in 0..5_000) normalizeBearing(degrees + declination) else Float.NaN
    fun close() {
        manager.unregisterListener(this)
        sensor = null; degrees = Float.NaN; measuredAt = 0
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { this.accuracy = accuracy; changed() }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor != sensor) return
        accuracy = event.accuracy
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) { degrees = Float.NaN; changed(); return }
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        @Suppress("DEPRECATION")
        val axes = when (windows.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, remapped)
        SensorManager.getOrientation(remapped, angles)
        val next = normalizeBearing(Math.toDegrees(angles[0].toDouble()).toFloat())
        degrees = smoothHeading(degrees, next)
        measuredAt = SystemClock.elapsedRealtime()
        changed()
    }
}

internal fun smoothHeading(previous: Float, next: Float): Float = if (!previous.isFinite()) normalizeBearing(next) else
    normalizeBearing(previous + (((next - previous + 540f) % 360f) - 180f) * .2f)

internal fun selectHeading(course: Float, speed: Float, compass: Float): Float = when {
    speed >= 2f && course.isFinite() -> normalizeBearing(course)
    compass.isFinite() -> normalizeBearing(compass)
    speed >= .7f && course.isFinite() -> normalizeBearing(course)
    else -> Float.NaN
}
