package com.opticalcenter.spatialnav.data.tomtom

import com.opticalcenter.spatialnav.data.MapDataException
import com.opticalcenter.spatialnav.data.MapDataSource
import com.opticalcenter.spatialnav.navigation.BoundingBox
import com.opticalcenter.spatialnav.navigation.Incident
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.navigation.Route
import com.opticalcenter.spatialnav.spatial.GeoBounds
import com.opticalcenter.spatialnav.spatial.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * TomTom Search, Routing (live traffic) and Traffic Incidents APIs.
 * [baseUrl] is overridable for tests.
 */
class TomTomDataSource(
    private val apiKey: String,
    private val baseUrl: String = "https://api.tomtom.com",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val language: String = "en-GB",
) : MapDataSource {
    override val supportsRouting = true

    private fun url(path: String): HttpUrl.Builder =
        "$baseUrl$path".toHttpUrl().newBuilder().addQueryParameter("key", apiKey)

    private fun coord(d: Double) = String.format(Locale.US, "%.6f", d)

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> body
                    response.code == 403 || response.code == 401 ->
                        throw MapDataException("TomTom rejected the API key (HTTP ${response.code}).")
                    response.code == 429 -> throw MapDataException("TomTom rate limit reached; try again shortly.")
                    else -> throw MapDataException("TomTom request failed (HTTP ${response.code}).")
                }
            }
        } catch (e: IOException) {
            throw MapDataException("Network error talking to TomTom: ${e.message}", e)
        }
    }

    override suspend fun searchPlaces(query: String, near: GeoPoint, radiusM: Int?, limit: Int): List<Place> {
        val url = "$baseUrl/search/2/search/".toHttpUrl().newBuilder()
            .addPathSegment("$query.json") // OkHttp percent-encodes the segment
            .addQueryParameter("key", apiKey)
            .addQueryParameter("lat", coord(near.lat))
            .addQueryParameter("lon", coord(near.lon))
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("language", language)
            .apply { radiusM?.let { addQueryParameter("radius", it.toString()) } }
            .build()
        return TomTomParsers.parsePlaces(get(url))
    }

    override suspend fun nearbyPlaces(center: GeoPoint, radiusM: Int, limit: Int): List<Place> {
        val url = url("/search/2/nearbySearch/.json")
            .addQueryParameter("lat", coord(center.lat))
            .addQueryParameter("lon", coord(center.lon))
            .addQueryParameter("radius", radiusM.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("language", language)
            .build()
        return TomTomParsers.parsePlaces(get(url))
    }

    override suspend fun reverseGeocode(point: GeoPoint): String? {
        val url = url("/search/2/reverseGeocode/${coord(point.lat)},${coord(point.lon)}.json")
            .addQueryParameter("language", language)
            .build()
        return TomTomParsers.parseReverseGeocode(get(url))
    }

    override suspend fun driveRoutes(
        origin: GeoPoint,
        destination: GeoPoint,
        headingDeg: Double?,
        alternatives: Int,
    ): List<Route> {
        val locations = "${coord(origin.lat)},${coord(origin.lon)}:${coord(destination.lat)},${coord(destination.lon)}"
        val url = url("/routing/1/calculateRoute/$locations/json")
            .addQueryParameter("traffic", "true")
            .addQueryParameter("travelMode", "car")
            .addQueryParameter("departAt", "now")
            .addQueryParameter("routeRepresentation", "polyline")
            .addQueryParameter("computeTravelTimeFor", "all")
            .addQueryParameter("sectionType", "traffic")
            .addQueryParameter("instructionsType", "text")
            .addQueryParameter("maxAlternatives", alternatives.coerceIn(0, 5).toString())
            .addQueryParameter("language", language)
            .apply { headingDeg?.let { addQueryParameter("vehicleHeading", (it.roundToInt() % 360).toString()) } }
            .build()
        val routes = try {
            TomTomParsers.parseRoutes(get(url))
        } catch (e: IllegalStateException) {
            throw MapDataException(e.message ?: "No route found.", e)
        }
        if (routes.isEmpty()) throw MapDataException("No drivable route was found.")
        return routes
    }

    override suspend fun incidents(box: BoundingBox): List<Incident> {
        // The API rejects boxes over 10,000 km².
        val b = GeoBounds.clampArea(box, MAX_INCIDENT_BOX_KM2)
        val url = url("/traffic/services/5/incidentDetails")
            .addQueryParameter("bbox", listOf(b.minLon, b.minLat, b.maxLon, b.maxLat).joinToString(",") { coord(it) })
            .addQueryParameter("fields", INCIDENT_FIELDS)
            .addQueryParameter("language", language)
            .addQueryParameter("timeValidityFilter", "present")
            .build()
        return TomTomParsers.parseIncidents(get(url))
    }

    companion object {
        const val MAX_INCIDENT_BOX_KM2 = 10_000.0
        private const val INCIDENT_FIELDS =
            "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
                "events{description,code,iconCategory},startTime,endTime,from,to,length,delay,roadNumbers}}}"

        /** Raster traffic-flow tiles (colour by speed relative to free flow) for the map overlay. */
        fun trafficFlowTileUrl(apiKey: String) =
            "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$apiKey"
    }
}
