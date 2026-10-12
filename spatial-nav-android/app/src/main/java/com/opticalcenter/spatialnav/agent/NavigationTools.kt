package com.opticalcenter.spatialnav.agent

import com.opticalcenter.spatialnav.data.MapDataException
import com.opticalcenter.spatialnav.data.MapDataSource
import com.opticalcenter.spatialnav.navigation.IncidentCategory
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.spatial.Direction
import com.opticalcenter.spatialnav.spatial.DirectionQuery
import com.opticalcenter.spatialnav.spatial.GeoBounds
import com.opticalcenter.spatialnav.spatial.SpatialEngine
import com.opticalcenter.spatialnav.spatial.UserPose
import kotlin.math.roundToInt

/** What the tools can see of the device's telemetry. */
data class TelemetrySnapshot(
    val pose: UserPose,
    val accuracyM: Float? = null,
    val hasFix: Boolean = false,
)

/** Tool schema in the JSON-schema shape LLM function-calling APIs expect. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
)

/** A place annotated relative to the user. */
data class PlaceResult(
    val place: Place,
    val distanceM: Int,
    val bearing: Double,
    val direction: Direction,
)

data class IncidentReport(
    val category: IncidentCategory,
    val description: String?,
    val magnitude: Int,
    val delayS: Int?,
    val from: String?,
    val to: String?,
    /** Distance ahead along the route; null for "nearby" (off-route) reports. */
    val distanceAheadM: Int?,
)

/** Snapshot of an active trip, as reported to the assistant. */
data class RouteStatusInfo(
    val destination: Place,
    val remainingM: Int,
    val remainingS: Int,
    val trafficDelayAheadS: Int,
    val nextInstruction: String?,
    val distanceToNextM: Int?,
    val arrivalTime: String,
)

sealed interface ToolResult {
    data class Location(
        val lat: Double,
        val lon: Double,
        val heading: Double,
        val facing: String,
        val accuracyM: Int?,
        val liveGps: Boolean,
        val address: String?,
    ) : ToolResult

    data class Places(val query: String, val results: List<PlaceResult>) : ToolResult

    data class Surroundings(val query: DirectionQuery, val radiusM: Int, val pois: List<PlaceResult>) : ToolResult

    data class NavigationStarted(
        val destination: Place,
        val distanceM: Int,
        val durationS: Int,
        val trafficDelayS: Int,
        val arrivalTime: String,
        val relativeDirection: Direction,
        val alternativesConsidered: Int,
        val firstInstruction: String?,
    ) : ToolResult

    data class RouteStatus(val status: RouteStatusInfo) : ToolResult

    data class Incidents(val scope: String, val reports: List<IncidentReport>) : ToolResult

    data class FasterRoute(
        val currentRemainingS: Int,
        val bestAlternativeS: Int?,
        val savingS: Int,
        val switched: Boolean,
    ) : ToolResult

    data class NavigationStopped(val wasNavigating: Boolean, val destination: String?) : ToolResult

    data class Error(val message: String, val suggestions: List<String> = emptyList()) : ToolResult
}

/** Trip control, implemented by the app's navigation manager. */
interface NavigationController {
    val isNavigating: Boolean
    suspend fun start(destination: Place): ToolResult
    fun stop(): ToolResult.NavigationStopped
    fun status(): RouteStatusInfo?
    suspend fun findFasterRoute(apply: Boolean): ToolResult
    suspend fun incidentsOnRoute(): List<IncidentReport>
}

/**
 * Tool handlers shared by the Claude assistant and the offline mock. They read
 * live telemetry through [telemetry] and talk to map data / navigation through
 * the injected interfaces, so tests can run them against fakes.
 */
class NavigationTools(
    private val telemetry: () -> TelemetrySnapshot,
    private val data: MapDataSource,
    private val navigation: NavigationController,
) {
    /** Results of the latest search, so the model can refer to them by place_id. */
    private val recentPlaces = LinkedHashMap<String, Place>()

    private fun annotate(place: Place): PlaceResult {
        val a = SpatialEngine.analyze(telemetry().pose, place.location)
        return PlaceResult(place, a.distance.roundToInt(), a.bearing, a.direction)
    }

    private fun remember(places: List<Place>) {
        for (p in places) recentPlaces[p.id] = p
        while (recentPlaces.size > 50) recentPlaces.remove(recentPlaces.keys.first())
    }

    suspend fun getCurrentLocation(): ToolResult.Location {
        val t = telemetry()
        val address = if (t.hasFix) runCatching { data.reverseGeocode(t.pose.point) }.getOrNull() else null
        return ToolResult.Location(
            lat = t.pose.lat,
            lon = t.pose.lon,
            heading = t.pose.heading,
            facing = SpatialEngine.compassName(t.pose.heading),
            accuracyM = t.accuracyM?.roundToInt(),
            liveGps = t.hasFix,
            address = address,
        )
    }

    suspend fun searchPlaces(query: String?, nearbyOnly: Boolean = false): ToolResult {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return ToolResult.Error("no search text was given.")
        return mapErrors {
            val radius = if (nearbyOnly) NEARBY_SEARCH_RADIUS_M else null
            val places = data.searchPlaces(q, telemetry().pose.point, radius, limit = 5)
            remember(places)
            ToolResult.Places(q, places.map(::annotate))
        }
    }

    suspend fun getSurroundingPois(direction: String?, radiusM: Int = DEFAULT_POI_RADIUS_M): ToolResult {
        val query = try {
            DirectionQuery.parse(direction)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error(e.message ?: "unknown direction.")
        }
        val radius = radiusM.coerceIn(50, 2000)
        return mapErrors {
            val nearby = data.nearbyPlaces(telemetry().pose.point, radius, limit = 40)
            remember(nearby)
            val wanted = nearby.map(::annotate)
                .filter { query is DirectionQuery.All || (query as DirectionQuery.Only).direction == it.direction }
                .sortedBy { it.distanceM }
                .take(MAX_POIS_SPOKEN)
            ToolResult.Surroundings(query, radius, wanted)
        }
    }

    /** Start navigation, or change the destination of the current trip. */
    suspend fun rerouteNavigation(destination: String?, placeId: String?): ToolResult = mapErrors {
        val text = destination?.trim()?.takeIf { it.isNotEmpty() }
        val target = placeId?.let { recentPlaces[it] }
            ?: text?.let { t -> recentPlaces.values.firstOrNull { it.name.equals(t, ignoreCase = true) } }
            ?: text?.let { t -> data.searchPlaces(t, telemetry().pose.point, null, limit = 3).also(::remember).firstOrNull() }
        if (target == null) {
            ToolResult.Error(
                if (text == null && placeId != null) "that place_id is not from a recent search." else "no destination matched \"$text\".",
                recentPlaces.values.take(3).map { it.name },
            )
        } else {
            navigation.start(target)
        }
    }

    fun getRouteStatus(): ToolResult =
        navigation.status()?.let { ToolResult.RouteStatus(it) }
            ?: ToolResult.Error("there is no active navigation.")

    suspend fun getTrafficIncidents(scope: String?): ToolResult {
        val onRoute = when (scope?.lowercase()) {
            "route" -> true
            "nearby" -> false
            else -> navigation.isNavigating
        }
        return mapErrors {
            if (onRoute) {
                if (!navigation.isNavigating) return@mapErrors ToolResult.Error("there is no active navigation.")
                ToolResult.Incidents("route", navigation.incidentsOnRoute())
            } else {
                val here = telemetry().pose
                val box = GeoBounds.around(here.point, NEARBY_INCIDENT_RADIUS_M)
                val reports = data.incidents(box)
                    .map { incident ->
                        val nearest = incident.geometry.minOfOrNull {
                            SpatialEngine.haversineDistance(here.lat, here.lon, it.lat, it.lon)
                        }
                        incident to (nearest ?: Double.MAX_VALUE)
                    }
                    .sortedBy { it.second }
                    .take(MAX_INCIDENTS_SPOKEN)
                    .map { (i, _) ->
                        IncidentReport(i.category, i.description, i.magnitude, i.delayS, i.from, i.to, distanceAheadM = null)
                    }
                ToolResult.Incidents("nearby", reports)
            }
        }
    }

    suspend fun findFasterRoute(apply: Boolean): ToolResult {
        if (!navigation.isNavigating) return ToolResult.Error("there is no active navigation.")
        return mapErrors { navigation.findFasterRoute(apply) }
    }

    fun stopNavigation(): ToolResult = navigation.stop()

    /** Dispatch a model-issued tool call by name. Input values come from JSON (strings, numbers, booleans). */
    suspend fun execute(name: String, input: Map<String, Any?>): ToolResult = when (name) {
        GET_CURRENT_LOCATION -> getCurrentLocation()
        SEARCH_PLACES -> searchPlaces(input.string("query"), input.bool("nearby_only") ?: false)
        GET_SURROUNDING_POIS -> getSurroundingPois(
            input.string("direction"),
            input.number("radius_m")?.toInt() ?: DEFAULT_POI_RADIUS_M,
        )
        REROUTE_NAVIGATION -> rerouteNavigation(input.string("destination"), input.string("place_id"))
        GET_ROUTE_STATUS -> getRouteStatus()
        GET_TRAFFIC_INCIDENTS -> getTrafficIncidents(input.string("scope"))
        FIND_FASTER_ROUTE -> findFasterRoute(input.bool("apply") ?: true)
        STOP_NAVIGATION -> stopNavigation()
        else -> ToolResult.Error("unknown tool $name.")
    }

    private suspend fun mapErrors(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (e: MapDataException) {
        ToolResult.Error(e.message ?: "map data is unavailable.")
    }

    private fun Map<String, Any?>.string(key: String) = (this[key] as? String) ?: this[key]?.toString()
    private fun Map<String, Any?>.bool(key: String) = when (val v = this[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }
    private fun Map<String, Any?>.number(key: String) = when (val v = this[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    companion object {
        const val GET_CURRENT_LOCATION = "get_current_location"
        const val SEARCH_PLACES = "search_places"
        const val GET_SURROUNDING_POIS = "get_surrounding_pois"
        const val REROUTE_NAVIGATION = "reroute_navigation"
        const val GET_ROUTE_STATUS = "get_route_status"
        const val GET_TRAFFIC_INCIDENTS = "get_traffic_incidents"
        const val FIND_FASTER_ROUTE = "find_faster_route"
        const val STOP_NAVIGATION = "stop_navigation"

        const val DEFAULT_POI_RADIUS_M = 500
        const val NEARBY_SEARCH_RADIUS_M = 5000
        const val NEARBY_INCIDENT_RADIUS_M = 10_000.0
        const val MAX_POIS_SPOKEN = 5
        const val MAX_INCIDENTS_SPOKEN = 5

        private fun obj(properties: Map<String, Any>, required: List<String> = emptyList()) = mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        )

        val DEFINITIONS = listOf(
            ToolDefinition(
                GET_CURRENT_LOCATION,
                "Get the user's current GPS position, street address (when available), compass heading and GPS accuracy.",
                obj(emptyMap()),
            ),
            ToolDefinition(
                SEARCH_PLACES,
                "Search for places, businesses or addresses by name or type (e.g. \"coffee\", \"Heathrow Terminal 5\", " +
                    "\"10 Downing Street\"). Results include a place_id, distance and which side of the user each is on. " +
                    "Use before reroute_navigation when the destination is ambiguous.",
                obj(
                    mapOf(
                        "query" to mapOf("type" to "string", "description" to "What to search for."),
                        "nearby_only" to mapOf(
                            "type" to "boolean",
                            "description" to "Limit results to within 5 km of the user. Use for generic categories like \"gas station\".",
                        ),
                    ),
                    listOf("query"),
                ),
            ),
            ToolDefinition(
                GET_SURROUNDING_POIS,
                "List real points of interest in a direction relative to where the user is facing (ahead, right, behind, " +
                    "left, or all), nearest first.",
                obj(
                    mapOf(
                        "direction" to mapOf("type" to "string", "enum" to listOf("ahead", "right", "behind", "left", "all")),
                        "radius_m" to mapOf("type" to "integer", "description" to "Search radius in meters (50-2000, default 500)."),
                    ),
                    listOf("direction"),
                ),
            ),
            ToolDefinition(
                REROUTE_NAVIGATION,
                "Start turn-by-turn driving navigation with live traffic to a destination, or change the destination of " +
                    "the current trip. Pass place_id from a previous search_places result when available; otherwise pass " +
                    "the destination name or address.",
                obj(
                    mapOf(
                        "destination" to mapOf("type" to "string", "description" to "Destination name or address."),
                        "place_id" to mapOf("type" to "string", "description" to "place_id from search_places."),
                    ),
                ),
            ),
            ToolDefinition(
                GET_ROUTE_STATUS,
                "Get the active trip's remaining distance, time, arrival time, traffic delay ahead and next turn.",
                obj(emptyMap()),
            ),
            ToolDefinition(
                GET_TRAFFIC_INCIDENTS,
                "Get live traffic incidents and road conditions (accidents, closures, road works, jams, fog, ice, " +
                    "flooding). scope \"route\" covers the rest of the active trip; \"nearby\" covers about 10 km around the user.",
                obj(mapOf("scope" to mapOf("type" to "string", "enum" to listOf("route", "nearby"))), listOf("scope")),
            ),
            ToolDefinition(
                FIND_FASTER_ROUTE,
                "Re-check live traffic for a faster route to the current destination. With apply=true the faster route " +
                    "is used automatically if it saves meaningful time.",
                obj(mapOf("apply" to mapOf("type" to "boolean")), listOf("apply")),
            ),
            ToolDefinition(STOP_NAVIGATION, "End the active navigation.", obj(emptyMap())),
        )
    }
}
