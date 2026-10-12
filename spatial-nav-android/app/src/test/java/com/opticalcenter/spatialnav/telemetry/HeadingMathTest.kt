package com.opticalcenter.spatialnav.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingMathTest {
    @Test
    fun azimuthRadiansToCompassDegrees() {
        assertEquals(0.0, HeadingMath.azimuthToDegrees(0f), 1e-6)
        assertEquals(90.0, HeadingMath.azimuthToDegrees((Math.PI / 2).toFloat()), 1e-4)
        // getOrientation reports west as −π/2.
        assertEquals(270.0, HeadingMath.azimuthToDegrees((-Math.PI / 2).toFloat()), 1e-4)
    }

    @Test
    fun declinationConvertsMagneticToTrueNorth() {
        assertEquals(103.0, HeadingMath.trueHeading(90.0, 13.0), 1e-9) // easterly declination
        assertEquals(355.0, HeadingMath.trueHeading(5.0, -10.0), 1e-9) // westerly, wraps
    }

    @Test
    fun smoothingTakesShortWayAcrossNorth() {
        assertEquals(0.0, HeadingMath.smooth(350.0, 10.0, factor = 0.5), 1e-9)
        assertEquals(355.0, HeadingMath.smooth(5.0, 345.0, factor = 0.5), 1e-9)
        assertEquals("first reading passes through", 42.0, HeadingMath.smooth(null, 42.0), 0.0)
    }
}
