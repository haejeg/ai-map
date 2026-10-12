package com.opticalcenter.spatialnav.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class HeadingSource(val label: String) {
    NONE("none"),
    ROTATION_VECTOR("rotation vector"),
    ACCEL_MAG("accel + magnetometer"),
    GPS_COURSE("GPS course"),
    MANUAL("manual"),
}

/** Magnetic heading in degrees clockwise from magnetic north, corrected for screen rotation. */
data class HeadingReading(val magneticHeading: Double, val source: HeadingSource)

/**
 * Compass heading from the fused rotation-vector sensor, falling back to
 * accelerometer + magnetometer on devices without one.
 */
class HeadingSensor(context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    private val rotationVector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val isAvailable: Boolean get() = rotationVector != null || (accelerometer != null && magnetometer != null)

    fun readings(): Flow<HeadingReading> = callbackFlow {
        val manager = sensorManager ?: run { close(); return@callbackFlow }
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var hasGravity = false
        var hasGeomagnetic = false

        fun emitFromRotationMatrix(source: HeadingSource) {
            val (axisX, axisY) = when (displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
            SensorManager.getOrientation(remapped, orientation)
            trySend(HeadingReading(HeadingMath.azimuthToDegrees(orientation[0]), source))
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        emitFromRotationMatrix(HeadingSource.ROTATION_VECTOR)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        event.values.copyInto(gravity, endIndex = 3)
                        hasGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        event.values.copyInto(geomagnetic, endIndex = 3)
                        hasGeomagnetic = true
                        if (hasGravity && SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) {
                            emitFromRotationMatrix(HeadingSource.ACCEL_MAG)
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        if (rotationVector != null) {
            manager.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        }
        awaitClose { manager.unregisterListener(listener) }
    }
}
