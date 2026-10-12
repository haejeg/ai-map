package com.opticalcenter.spatialnav.spatial

import com.opticalcenter.spatialnav.navigation.BoundingBox
import com.opticalcenter.spatialnav.testing.Fixtures
import com.opticalcenter.spatialnav.testing.Fixtures.east
import com.opticalcenter.spatialnav.testing.Fixtures.north
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    private val polyline = RoutePolyline(Fixtures.straightRoute().points)

    @Test
    fun lengthMatchesConstruction() {
        assertEquals(2000.0, polyline.lengthM, 0.5)
    }

    @Test
    fun projectsPointOnRoute() {
        val p = polyline.project(east(1234.0))
        assertEquals(1234.0, p.alongM, 0.5)
        assertEquals(0.0, p.offRouteM, 0.5)
        assertEquals(12, p.segmentIndex)
    }

    @Test
    fun measuresPerpendicularDistanceOffRoute() {
        val p = polyline.project(north(east(500.0), 80.0))
        assertEquals(500.0, p.alongM, 1.0)
        assertEquals(80.0, p.offRouteM, 1.0)
    }

    @Test
    fun clampsBeyondTheEnds() {
        val before = polyline.project(east(-300.0))
        assertEquals(0.0, before.alongM, 0.01)
        assertEquals(300.0, before.offRouteM, 1.0)
        val after = polyline.project(east(2300.0))
        assertEquals(2000.0, after.alongM, 0.5)
    }

    @Test
    fun fromSegmentPreventsSnappingBackwards() {
        // A route that goes east 1 km then returns west along the same road.
        val out = (0..10).map { east(it * 100.0) }
        val back = (9 downTo 0).map { east(it * 100.0) }
        val loop = RoutePolyline(out + back)
        val p = east(300.0)
        assertEquals("unconstrained snaps to the outbound leg", 300.0, loop.project(p).alongM, 1.0)
        assertEquals("constrained snaps to the return leg", 1700.0, loop.project(p, fromSegment = 12).alongM, 1.0)
    }

    @Test
    fun pointAtAndRemainingPoints() {
        val mid = polyline.pointAt(1050.0)
        assertEquals(1050.0, polyline.project(mid).alongM, 0.5)
        val rest = polyline.remainingPoints(1050.0)
        assertEquals(11, rest.size) // interpolated point + points at 1100..2000
        assertEquals(1050.0, polyline.project(rest.first()).alongM, 0.5)
    }

    @Test
    fun boundsPaddingAndAreaClamp() {
        val box = GeoBounds.of(polyline.points, paddingM = 100.0)
        assertTrue(box.minLat < Fixtures.START.lat && box.maxLat > Fixtures.START.lat)
        val huge = BoundingBox(40.0, -10.0, 60.0, 20.0)
        assertTrue(GeoBounds.areaKm2(huge) > 10_000)
        val clamped = GeoBounds.clampArea(huge, 10_000.0)
        assertTrue(GeoBounds.areaKm2(clamped) <= 10_000.0)
        assertEquals((huge.minLat + huge.maxLat) / 2, (clamped.minLat + clamped.maxLat) / 2, 1e-9)
    }
}
