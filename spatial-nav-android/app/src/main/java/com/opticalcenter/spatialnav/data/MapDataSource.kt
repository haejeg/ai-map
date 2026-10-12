package com.opticalcenter.spatialnav.data

import com.opticalcenter.spatialnav.navigation.BoundingBox
import com.opticalcenter.spatialnav.navigation.Incident
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.navigation.Route
import com.opticalcenter.spatialnav.spatial.GeoPoint

/** Map data the app needs: places, routes with traffic, and incidents. */
interface MapDataSource {
    /** True when routing and live traffic are available (needs a provider key). */
    val supportsRouting: Boolean

    /** Free-text place or address search, biased to (or limited around) [near]. */
    suspend fun searchPlaces(query: String, near: GeoPoint, radiusM: Int? = null, limit: Int = 5): List<Place>

    /** Points of interest around [center]. */
    suspend fun nearbyPlaces(center: GeoPoint, radiusM: Int, limit: Int = 20): List<Place>

    /** Street address of a coordinate, or null if unknown. */
    suspend fun reverseGeocode(point: GeoPoint): String?

    /** Traffic-aware driving routes, best first. Includes up to [alternatives] extra routes. */
    suspend fun driveRoutes(origin: GeoPoint, destination: GeoPoint, headingDeg: Double? = null, alternatives: Int = 0): List<Route>

    /** Current incidents and road conditions inside [box]. */
    suspend fun incidents(box: BoundingBox): List<Incident>
}

class MapDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
