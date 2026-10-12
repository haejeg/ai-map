package com.opticalcenter.spatialnav.navigation

import com.opticalcenter.spatialnav.spatial.GeoPoint

/** A searchable place: a business, landmark or address. */
data class Place(
    val id: String,
    val name: String,
    val category: String?,
    val address: String?,
    val location: GeoPoint,
)

/** One turn-by-turn step. [offsetM] is the distance from the route start to the manoeuvre. */
data class Instruction(
    val offsetM: Double,
    val point: GeoPoint,
    val maneuver: String,
    val message: String,
    val street: String?,
)

enum class TrafficCategory { JAM, ROAD_WORK, ROAD_CLOSURE, OTHER }

/** A stretch of the route affected by traffic, as polyline point indices. */
data class TrafficSection(
    val startIndex: Int,
    val endIndex: Int,
    val category: TrafficCategory,
    /** 0 unknown, 1 minor, 2 moderate, 3 major, 4 undefined (closures). */
    val magnitude: Int,
    val delayS: Int,
)

data class Route(
    val points: List<GeoPoint>,
    val lengthM: Int,
    val travelTimeS: Int,
    val trafficDelayS: Int,
    val noTrafficTravelTimeS: Int?,
    val departureTime: String?,
    val arrivalTime: String?,
    val instructions: List<Instruction>,
    val trafficSections: List<TrafficSection>,
)

/** Incident categories from the traffic feed, including weather-related road conditions. */
enum class IncidentCategory(val code: Int, val spoken: String) {
    UNKNOWN(0, "an incident"),
    ACCIDENT(1, "an accident"),
    FOG(2, "fog"),
    DANGEROUS_CONDITIONS(3, "dangerous conditions"),
    RAIN(4, "rain"),
    ICE(5, "ice"),
    JAM(6, "a traffic jam"),
    LANE_CLOSED(7, "a lane closure"),
    ROAD_CLOSED(8, "a road closure"),
    ROAD_WORKS(9, "road works"),
    WIND(10, "strong wind"),
    FLOODING(11, "flooding"),
    BROKEN_DOWN_VEHICLE(14, "a broken-down vehicle"),
    ;

    val isRoadCondition: Boolean get() = this in setOf(FOG, DANGEROUS_CONDITIONS, RAIN, ICE, WIND, FLOODING)

    companion object {
        fun fromCode(code: Int?) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class Incident(
    val id: String,
    val category: IncidentCategory,
    /** 0 unknown, 1 minor, 2 moderate, 3 major, 4 undefined (closures). */
    val magnitude: Int,
    val description: String?,
    val from: String?,
    val to: String?,
    val delayS: Int?,
    val lengthM: Double?,
    val roadNumbers: List<String>,
    val geometry: List<GeoPoint>,
)

/** Axis-aligned lat/lon box. */
data class BoundingBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)
