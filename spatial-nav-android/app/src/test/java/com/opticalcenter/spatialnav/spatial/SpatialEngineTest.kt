package com.opticalcenter.spatialnav.spatial

import com.opticalcenter.spatialnav.spatial.SpatialEngine.analyze
import com.opticalcenter.spatialnav.spatial.SpatialEngine.categorize
import com.opticalcenter.spatialnav.spatial.SpatialEngine.destinationPoint
import com.opticalcenter.spatialnav.spatial.SpatialEngine.forwardAzimuth
import com.opticalcenter.spatialnav.spatial.SpatialEngine.haversineDistance
import com.opticalcenter.spatialnav.spatial.SpatialEngine.normalizeAngle
import com.opticalcenter.spatialnav.spatial.SpatialEngine.relativeBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialEngineTest {
    // Simulated user: Times Square, facing due east.
    private val user = UserPose(40.7580, -73.9855, heading = 90.0)

    /** Angular difference along the shortest arc, for comparing bearings near 0/360. */
    private fun angDiff(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun expectedFor(rel: Double) = when {
        rel >= 315 || rel < 45 -> Direction.AHEAD
        rel < 135 -> Direction.RIGHT
        rel < 225 -> Direction.BEHIND
        else -> Direction.LEFT
    }

    @Test
    fun normalizeAngle_wrapsIntoRange() {
        val cases = listOf(0.0 to 0.0, 360.0 to 0.0, 720.0 to 0.0, -90.0 to 270.0, 450.0 to 90.0, -360.0 to 0.0, 359.5 to 359.5)
        for ((input, expected) in cases) assertEquals("normalize($input)", expected, normalizeAngle(input), 0.0)
        assertEquals("no negative zero", 0.0.toRawBits(), normalizeAngle(-360.0).toRawBits())
    }

    @Test
    fun forwardAzimuth_cardinalDirections() {
        assertEquals(0.0, forwardAzimuth(0.0, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(90.0, forwardAzimuth(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(180.0, forwardAzimuth(0.0, 0.0, -1.0, 0.0), 1e-9)
        assertEquals(270.0, forwardAzimuth(0.0, 0.0, 0.0, -1.0), 1e-9)
    }

    @Test
    fun forwardAzimuth_matchesPublishedBaghdadToOsaka() {
        assertEquals(60.16, forwardAzimuth(35.0, 45.0, 35.0, 135.0), 0.05)
    }

    @Test
    fun forwardAzimuth_alwaysInRange() {
        for (lon in -179..179 step 17) {
            val b = forwardAzimuth(10.0, 0.0, -20.0, lon.toDouble())
            assertTrue("bearing $b out of range", b >= 0 && b < 360)
        }
    }

    @Test
    fun categorize_boundaries() {
        val cases = listOf(
            0.0 to Direction.AHEAD, 44.999 to Direction.AHEAD, 45.0 to Direction.RIGHT, 134.999 to Direction.RIGHT,
            135.0 to Direction.BEHIND, 224.999 to Direction.BEHIND, 225.0 to Direction.LEFT, 314.999 to Direction.LEFT,
            315.0 to Direction.AHEAD, 359.999 to Direction.AHEAD, 360.0 to Direction.AHEAD,
            -45.0 to Direction.AHEAD, -46.0 to Direction.LEFT, 810.0 to Direction.RIGHT,
        )
        for ((rel, expected) in cases) assertEquals("relative $rel", expected, categorize(rel))
    }

    @Test
    fun relativeBearing_wrapsAcrossNorth() {
        assertEquals(20.0, relativeBearing(10.0, 350.0), 1e-9)
        assertEquals(Direction.AHEAD, categorize(relativeBearing(10.0, 350.0)))
        assertEquals(340.0, relativeBearing(350.0, 10.0), 1e-9)
    }

    @Test
    fun facingEast_independentRawOffsetPois() {
        val d = 0.001 // ~111 m in latitude
        assertEquals("due east", Direction.AHEAD, analyze(user, GeoPoint(user.lat, user.lon + d)).direction)
        assertEquals("due south", Direction.RIGHT, analyze(user, GeoPoint(user.lat - d, user.lon)).direction)
        assertEquals("due west", Direction.BEHIND, analyze(user, GeoPoint(user.lat, user.lon - d)).direction)
        assertEquals("due north", Direction.LEFT, analyze(user, GeoPoint(user.lat + d, user.lon)).direction)
    }

    @Test
    fun facingEast_poisAtSpecificBearings() {
        val cases = listOf(
            Triple(60.0, 330.0, Direction.AHEAD), Triple(120.0, 30.0, Direction.AHEAD),
            Triple(150.0, 60.0, Direction.RIGHT), Triple(200.0, 110.0, Direction.RIGHT),
            Triple(250.0, 160.0, Direction.BEHIND), Triple(300.0, 210.0, Direction.BEHIND),
            Triple(340.0, 250.0, Direction.LEFT), Triple(20.0, 290.0, Direction.LEFT),
        )
        for ((absolute, rel, expected) in cases) {
            val a = analyze(user, destinationPoint(user.lat, user.lon, absolute, 150.0))
            assertTrue("bearing ${a.bearing} vs $absolute", angDiff(a.bearing, absolute) < 0.01)
            assertTrue("relative ${a.relative} vs $rel", angDiff(a.relative, rel) < 0.01)
            assertEquals("bearing $absolute", expected, a.direction)
        }
    }

    @Test
    fun exhaustiveSweep_everyHeadingTimesRelativeAngle() {
        val boundaries = listOf(0.0, 45.0, 135.0, 225.0, 315.0, 360.0)
        var checked = 0
        for (lat in listOf(-60.0, 0.0, 40.758, 70.0)) {
            for (heading in 0 until 360 step 30) {
                var rel = 0.5
                while (rel < 360) {
                    if (boundaries.none { abs(rel - it) < 0.05 }) {
                        val pose = UserPose(lat, 10.0, heading.toDouble())
                        val p = destinationPoint(lat, 10.0, heading + rel, 250.0)
                        assertEquals("lat $lat heading $heading rel $rel", expectedFor(rel), analyze(pose, p).direction)
                        checked++
                    }
                    rel += 4.0
                }
            }
        }
        assertEquals(4 * 12 * 90, checked)
    }

    @Test
    fun distanceHelpers_roundTrip() {
        for (bearing in listOf(0.0, 37.0, 90.0, 181.0, 299.0)) {
            val p = destinationPoint(user.lat, user.lon, bearing, 400.0)
            assertEquals("bearing $bearing", 400.0, haversineDistance(user.lat, user.lon, p.lat, p.lon), 0.01)
        }
        assertEquals("1° latitude", 111_195.0, haversineDistance(0.0, 0.0, 1.0, 0.0), 5.0)
    }

    private fun poi(id: String, name: String, bearing: Double, distance: Double) =
        Poi(id, name, "test", destinationPoint(user.lat, user.lon, bearing, distance))

    private val pois = listOf(
        poi("n", "North Deli", 0.0, 100.0),
        poi("nn", "Far North Bank", 355.0, 900.0),
        poi("nw", "Northwest Pizza", 330.0, 50.0),
        poi("e", "East Bakery", 90.0, 200.0),
        poi("s", "South Gym", 180.0, 150.0),
        poi("w", "West Cafe", 270.0, 300.0),
    )

    private fun ids(query: DirectionQuery, radius: Double = Double.POSITIVE_INFINITY) =
        SpatialEngine.filterPois(user, pois, query, radius).map { it.poi.id }

    @Test
    fun filterPois_leftOfEastFacingUserIsNorth_nearestFirst() {
        assertEquals(listOf("nw", "n", "nn"), ids(DirectionQuery.Only(Direction.LEFT)))
    }

    @Test
    fun filterPois_respectsRadius() {
        assertEquals(listOf("nw", "n"), ids(DirectionQuery.Only(Direction.LEFT), 500.0))
    }

    @Test
    fun filterPois_otherQuadrantsAndAll() {
        assertEquals(listOf("e"), ids(DirectionQuery.Only(Direction.AHEAD)))
        assertEquals(listOf("s"), ids(DirectionQuery.Only(Direction.RIGHT)))
        assertEquals(listOf("w"), ids(DirectionQuery.Only(Direction.BEHIND)))
        assertEquals(pois.size, ids(DirectionQuery.All).size)
    }

    @Test
    fun directionQuery_parsesSynonymsAndRejectsJunk() {
        assertEquals(DirectionQuery.Only(Direction.AHEAD), DirectionQuery.parse("Front"))
        assertEquals(DirectionQuery.Only(Direction.BEHIND), DirectionQuery.parse("back"))
        assertEquals(DirectionQuery.All, DirectionQuery.parse("around"))
        assertEquals(DirectionQuery.All, DirectionQuery.parse(null))
        assertThrows(IllegalArgumentException::class.java) { DirectionQuery.parse("upwards") }
    }
}
