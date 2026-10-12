package com.opticalcenter.spatialnav.spatial

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the globe in decimal degrees. */
data class GeoPoint(val lat: Double, val lon: Double)

/** Where the user is and which way they face (degrees clockwise from true north). */
data class UserPose(val lat: Double, val lon: Double, val heading: Double) {
    val point get() = GeoPoint(lat, lon)
}

/** Quadrant relative to where the user is facing. */
enum class Direction(val spoken: String) {
    AHEAD("ahead of you"),
    RIGHT("to your right"),
    BEHIND("behind you"),
    LEFT("to your left"),
}

/** Direction filter for POI queries: one quadrant, or everything around the user. */
sealed interface DirectionQuery {
    data class Only(val direction: Direction) : DirectionQuery
    data object All : DirectionQuery

    companion object {
        private val ALIASES = mapOf(
            "ahead" to Direction.AHEAD, "front" to Direction.AHEAD, "in front" to Direction.AHEAD,
            "forward" to Direction.AHEAD, "straight" to Direction.AHEAD,
            "right" to Direction.RIGHT,
            "behind" to Direction.BEHIND, "back" to Direction.BEHIND, "rear" to Direction.BEHIND,
            "left" to Direction.LEFT,
        )
        private val ALL_WORDS = setOf("all", "around", "any", "everywhere")

        /** Parse a spoken/LLM-supplied direction; throws [IllegalArgumentException] if unknown. */
        fun parse(raw: String?): DirectionQuery {
            val key = raw?.trim()?.lowercase() ?: return All
            if (key in ALL_WORDS) return All
            val direction = ALIASES[key] ?: throw IllegalArgumentException("Unknown direction \"$raw\"")
            return Only(direction)
        }
    }
}

/** Result of analysing one target relative to a user pose. */
data class SpatialAnalysis(
    val bearing: Double,
    val relative: Double,
    val direction: Direction,
    val distance: Double,
)

/**
 * Spatial bearing engine. Pure math with no Android dependencies, so it is
 * fully covered by JVM unit tests.
 */
object SpatialEngine {
    const val EARTH_RADIUS_M = 6_371_008.8

    private fun Double.toRad() = Math.toRadians(this)
    private fun Double.toDeg() = Math.toDegrees(this)

    /** Wrap any angle into [0, 360). */
    fun normalizeAngle(deg: Double): Double {
        val wrapped = deg % 360.0
        // `+ 0.0` turns -0.0 into 0.0 so callers never see a negative zero.
        return (if (wrapped < 0) wrapped + 360.0 else wrapped) + 0.0
    }

    /**
     * Initial great-circle bearing (forward azimuth) from point 1 to point 2,
     * in degrees clockwise from true north, range [0, 360).
     *
     *   θ = atan2( sin Δλ · cos φ2 , cos φ1 · sin φ2 − sin φ1 · cos φ2 · cos Δλ )
     */
    fun forwardAzimuth(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = lat1.toRad()
        val φ2 = lat2.toRad()
        val Δλ = (lon2 - lon1).toRad()
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return normalizeAngle(atan2(y, x).toDeg())
    }

    /** Great-circle distance in metres (haversine). */
    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val Δφ = (lat2 - lat1).toRad()
        val Δλ = (lon2 - lon1).toRad()
        val a = sin(Δφ / 2).pow(2) + cos(lat1.toRad()) * cos(lat2.toRad()) * sin(Δλ / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Point reached by travelling [distanceM] metres from a start along [bearingDeg]. */
    fun destinationPoint(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GeoPoint {
        val δ = distanceM / EARTH_RADIUS_M
        val θ = bearingDeg.toRad()
        val φ1 = lat.toRad()
        val λ1 = lon.toRad()
        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return GeoPoint(φ2.toDeg(), (λ2.toDeg() + 540.0) % 360.0 - 180.0)
    }

    /** Angle of a target relative to where the user faces: 0 = dead ahead, 90 = right. */
    fun relativeBearing(targetBearing: Double, heading: Double) = normalizeAngle(targetBearing - heading)

    /**
     * Bucket a relative bearing into a quadrant. Ranges are half-open so every
     * angle maps to exactly one bucket:
     *   AHEAD  [315, 360) ∪ [0, 45)
     *   RIGHT  [45, 135)
     *   BEHIND [135, 225)
     *   LEFT   [225, 315)
     */
    fun categorize(relative: Double): Direction {
        val r = normalizeAngle(relative)
        return when {
            r >= 315.0 || r < 45.0 -> Direction.AHEAD
            r < 135.0 -> Direction.RIGHT
            r < 225.0 -> Direction.BEHIND
            else -> Direction.LEFT
        }
    }

    fun analyze(user: UserPose, target: GeoPoint): SpatialAnalysis {
        val bearing = forwardAzimuth(user.lat, user.lon, target.lat, target.lon)
        val relative = relativeBearing(bearing, user.heading)
        return SpatialAnalysis(
            bearing = bearing,
            relative = relative,
            direction = categorize(relative),
            distance = haversineDistance(user.lat, user.lon, target.lat, target.lon),
        )
    }

    /** Annotate POIs, keep those in the requested quadrant and radius, nearest first. */
    fun filterPois(
        user: UserPose,
        pois: List<Poi>,
        query: DirectionQuery = DirectionQuery.All,
        maxDistanceM: Double = Double.POSITIVE_INFINITY,
    ): List<LocatedPoi> = pois
        .map { LocatedPoi(it, analyze(user, it.location)) }
        .filter { (query is DirectionQuery.All || (query as DirectionQuery.Only).direction == it.analysis.direction) }
        .filter { it.analysis.distance <= maxDistanceM }
        .sortedBy { it.analysis.distance }

    private val COMPASS_POINTS =
        listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")

    fun compassName(bearing: Double): String =
        COMPASS_POINTS[(normalizeAngle(bearing) / 45.0).roundToInt() % 8]
}
