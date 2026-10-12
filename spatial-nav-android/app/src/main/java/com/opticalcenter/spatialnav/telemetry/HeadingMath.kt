package com.opticalcenter.spatialnav.telemetry

import com.opticalcenter.spatialnav.spatial.SpatialEngine.normalizeAngle

/** Pure heading helpers, kept free of Android types so they are unit-testable. */
object HeadingMath {
    /** Azimuth from SensorManager.getOrientation (radians, −π..π) → degrees [0, 360). */
    fun azimuthToDegrees(azimuthRad: Float): Double = normalizeAngle(Math.toDegrees(azimuthRad.toDouble()))

    /** Magnetic heading + declination (east positive) = true heading. */
    fun trueHeading(magneticHeading: Double, declinationDeg: Double): Double =
        normalizeAngle(magneticHeading + declinationDeg)

    /** Low-pass filter along the shortest arc, so 359° → 1° doesn't swing through 180°. */
    fun smooth(previous: Double?, next: Double, factor: Double = 0.2): Double {
        if (previous == null) return next
        val delta = ((next - previous + 540.0) % 360.0) - 180.0
        return normalizeAngle(previous + delta * factor)
    }
}
