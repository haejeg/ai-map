package com.opticalcenter.spatialnav.data

import com.opticalcenter.spatialnav.navigation.BoundingBox
import com.opticalcenter.spatialnav.navigation.Incident
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.navigation.Route
import com.opticalcenter.spatialnav.spatial.GeoPoint
import com.opticalcenter.spatialnav.spatial.MockPois
import com.opticalcenter.spatialnav.spatial.SpatialEngine

/**
 * Offline stand-in used when no TomTom key is configured: the fixed mock POIs
 * around [anchor]. Routing and traffic are not available.
 */
class MockDataSource(private val anchor: () -> GeoPoint) : MapDataSource {
    override val supportsRouting = false

    private fun places() = MockPois.around(anchor()).map {
        Place(it.id, it.name, it.category, address = null, location = it.location)
    }

    private fun Place.distanceFrom(p: GeoPoint) =
        SpatialEngine.haversineDistance(p.lat, p.lon, location.lat, location.lon)

    override suspend fun searchPlaces(query: String, near: GeoPoint, radiusM: Int?, limit: Int): List<Place> {
        val q = query.trim().lowercase()
        return places()
            .filter { q in it.name.lowercase() || it.name.lowercase() in q || it.category?.lowercase() == q }
            .filter { radiusM == null || it.distanceFrom(near) <= radiusM }
            .sortedBy { it.distanceFrom(near) }
            .take(limit)
    }

    override suspend fun nearbyPlaces(center: GeoPoint, radiusM: Int, limit: Int): List<Place> =
        places().filter { it.distanceFrom(center) <= radiusM }.sortedBy { it.distanceFrom(center) }.take(limit)

    override suspend fun reverseGeocode(point: GeoPoint): String? = null

    override suspend fun driveRoutes(origin: GeoPoint, destination: GeoPoint, headingDeg: Double?, alternatives: Int): List<Route> =
        throw MapDataException("Driving directions need a TomTom API key. Add tomtom.apiKey to local.properties.")

    override suspend fun incidents(box: BoundingBox): List<Incident> = emptyList()
}
