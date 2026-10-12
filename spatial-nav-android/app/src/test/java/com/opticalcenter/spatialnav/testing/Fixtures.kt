package com.opticalcenter.spatialnav.testing

import com.opticalcenter.spatialnav.data.MapDataException
import com.opticalcenter.spatialnav.data.MapDataSource
import com.opticalcenter.spatialnav.navigation.BoundingBox
import com.opticalcenter.spatialnav.navigation.Incident
import com.opticalcenter.spatialnav.navigation.IncidentCategory
import com.opticalcenter.spatialnav.navigation.Instruction
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.navigation.Route
import com.opticalcenter.spatialnav.navigation.TrafficCategory
import com.opticalcenter.spatialnav.navigation.TrafficSection
import com.opticalcenter.spatialnav.spatial.GeoPoint
import com.opticalcenter.spatialnav.spatial.SpatialEngine

/** Test helpers: a synthetic straight route and an in-memory [MapDataSource]. */
object Fixtures {
    val START = GeoPoint(51.5000, -0.1300)

    /** Point [metres] east of [START] (along the route). */
    fun east(metres: Double, from: GeoPoint = START) = SpatialEngine.destinationPoint(from.lat, from.lon, 90.0, metres)

    /** Point [metres] north of [p] (off the route). */
    fun north(p: GeoPoint, metres: Double) = SpatialEngine.destinationPoint(p.lat, p.lon, 0.0, metres)

    /**
     * A 2 km route heading due east with points every 100 m, a left turn at 1 km
     * and a 4-minute jam on the second half.
     */
    fun straightRoute(travelTimeS: Int = 240, lengthM: Int = 2000): Route {
        val points = (0..lengthM / 100).map { east(it * 100.0) }
        return Route(
            points = points,
            lengthM = lengthM,
            travelTimeS = travelTimeS,
            trafficDelayS = 240,
            noTrafficTravelTimeS = travelTimeS - 240,
            departureTime = null,
            arrivalTime = null,
            instructions = listOf(
                Instruction(0.0, points.first(), "DEPART", "Leave from Oxford Street", "Oxford Street"),
                Instruction(1000.0, points[10], "TURN_LEFT", "Turn left onto Baker Street", "Baker Street"),
                Instruction(lengthM.toDouble(), points.last(), "ARRIVE", "You have arrived at your destination", null),
            ),
            trafficSections = listOf(TrafficSection(12, 18, TrafficCategory.JAM, magnitude = 2, delayS = 240)),
        )
    }

    val LIBRARY = Place("lib", "Public Library", "library", "1 Library Way", east(2000.0))

    fun incident(id: String, at: GeoPoint, category: IncidentCategory = IncidentCategory.ACCIDENT, magnitude: Int = 3, delayS: Int? = 300) =
        Incident(id, category, magnitude, "Accident", "Baker Street", "King Street", delayS, 100.0, listOf("A41"), listOf(at))
}

class FakeMapData(
    var places: List<Place> = emptyList(),
    var routes: List<Route> = listOf(Fixtures.straightRoute()),
    var incidentList: List<Incident> = emptyList(),
    var address: String? = "221B Baker Street, London",
    override val supportsRouting: Boolean = true,
) : MapDataSource {
    var routeRequests = 0
    var lastIncidentBox: BoundingBox? = null

    override suspend fun searchPlaces(query: String, near: GeoPoint, radiusM: Int?, limit: Int) =
        places.filter { query.lowercase() in it.name.lowercase() || it.category == query.lowercase() }.take(limit)

    override suspend fun nearbyPlaces(center: GeoPoint, radiusM: Int, limit: Int) = places.filter {
        SpatialEngine.haversineDistance(center.lat, center.lon, it.location.lat, it.location.lon) <= radiusM
    }.take(limit)

    override suspend fun reverseGeocode(point: GeoPoint) = address

    override suspend fun driveRoutes(origin: GeoPoint, destination: GeoPoint, headingDeg: Double?, alternatives: Int): List<Route> {
        routeRequests++
        if (!supportsRouting) throw MapDataException("no routing")
        return routes
    }

    override suspend fun incidents(box: BoundingBox): List<Incident> {
        lastIncidentBox = box
        return incidentList
    }
}
