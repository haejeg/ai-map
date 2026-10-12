package com.opticalcenter.spatialnav.data.tomtom

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.opticalcenter.spatialnav.navigation.Incident
import com.opticalcenter.spatialnav.navigation.IncidentCategory
import com.opticalcenter.spatialnav.navigation.Instruction
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.navigation.Route
import com.opticalcenter.spatialnav.navigation.TrafficCategory
import com.opticalcenter.spatialnav.navigation.TrafficSection
import com.opticalcenter.spatialnav.spatial.GeoPoint

/**
 * Pure JSON → model parsing for TomTom Search, Routing and Traffic Incidents
 * responses. Optional fields are read leniently so a missing field degrades
 * gracefully instead of failing the whole response.
 */
object TomTomParsers {
    private val mapper = ObjectMapper()

    private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()
    private fun JsonNode.intOrNull(field: String): Int? = get(field)?.takeIf { it.isNumber }?.asInt()
    private fun JsonNode.doubleOrNull(field: String): Double? = get(field)?.takeIf { it.isNumber }?.asDouble()

    /** Search API (fuzzy, nearby): results[]. */
    fun parsePlaces(json: String): List<Place> {
        val results = mapper.readTree(json).path("results")
        return results.mapNotNull { r ->
            val pos = r.path("position")
            val lat = pos.doubleOrNull("lat") ?: return@mapNotNull null
            val lon = pos.doubleOrNull("lon") ?: return@mapNotNull null
            val poi = r.get("poi")
            val address = r.path("address").textOrNull("freeformAddress")
            val category = poi?.path("classifications")?.firstOrNull()
                ?.path("names")?.firstOrNull { it.textOrNull("nameLocale")?.startsWith("en") != false }
                ?.textOrNull("name")
                ?: poi?.path("categories")?.firstOrNull()?.asText()
            Place(
                id = r.textOrNull("id") ?: "$lat,$lon",
                name = poi?.textOrNull("name") ?: address ?: "Unnamed place",
                category = category,
                address = address,
                location = GeoPoint(lat, lon),
            )
        }
    }

    /** Reverse Geocoding API: addresses[0].address.freeformAddress. */
    fun parseReverseGeocode(json: String): String? =
        mapper.readTree(json).path("addresses").firstOrNull()?.path("address")?.textOrNull("freeformAddress")

    /** Routing API calculateRoute: routes[]. */
    fun parseRoutes(json: String): List<Route> {
        val root = mapper.readTree(json)
        root.get("error")?.let { throw IllegalStateException(it.textOrNull("description") ?: "Routing error") }
        return root.path("routes").map { r ->
            val summary = r.path("summary")
            val points = r.path("legs").flatMap { leg ->
                leg.path("points").map { GeoPoint(it.path("latitude").asDouble(), it.path("longitude").asDouble()) }
            }
            val instructions = r.path("guidance").path("instructions").mapNotNull { ins ->
                val p = ins.get("point") ?: return@mapNotNull null
                Instruction(
                    offsetM = ins.path("routeOffsetInMeters").asDouble(),
                    point = GeoPoint(p.path("latitude").asDouble(), p.path("longitude").asDouble()),
                    maneuver = ins.textOrNull("maneuver") ?: "STRAIGHT",
                    message = ins.textOrNull("message") ?: ins.textOrNull("combinedMessage") ?: "",
                    street = ins.textOrNull("street"),
                )
            }
            val traffic = r.path("sections")
                .filter { it.textOrNull("sectionType") == "TRAFFIC" }
                .map { s ->
                    TrafficSection(
                        startIndex = s.path("startPointIndex").asInt(),
                        endIndex = s.path("endPointIndex").asInt(),
                        category = runCatching { TrafficCategory.valueOf(s.textOrNull("simpleCategory") ?: "OTHER") }
                            .getOrDefault(TrafficCategory.OTHER),
                        magnitude = s.intOrNull("magnitudeOfDelay") ?: 0,
                        delayS = s.intOrNull("delayInSeconds") ?: 0,
                    )
                }
            Route(
                points = points,
                lengthM = summary.path("lengthInMeters").asInt(),
                travelTimeS = summary.path("travelTimeInSeconds").asInt(),
                trafficDelayS = summary.intOrNull("trafficDelayInSeconds") ?: 0,
                noTrafficTravelTimeS = summary.intOrNull("noTrafficTravelTimeInSeconds"),
                departureTime = summary.textOrNull("departureTime"),
                arrivalTime = summary.textOrNull("arrivalTime"),
                instructions = instructions,
                trafficSections = traffic,
            )
        }
    }

    /** Traffic Incident Details v5: incidents[] (GeoJSON features, coordinates lon,lat). */
    fun parseIncidents(json: String): List<Incident> =
        mapper.readTree(json).path("incidents").mapNotNull { f ->
            if (f == null || f.isNull) return@mapNotNull null
            val props = f.path("properties")
            val geometry = f.path("geometry")
            val coords = geometry.path("coordinates")
            val points = when (geometry.textOrNull("type")) {
                "Point" -> listOf(GeoPoint(coords[1].asDouble(), coords[0].asDouble()))
                "LineString" -> coords.map { GeoPoint(it[1].asDouble(), it[0].asDouble()) }
                else -> emptyList()
            }
            Incident(
                id = props.textOrNull("id") ?: points.firstOrNull()?.toString().orEmpty(),
                category = IncidentCategory.fromCode(props.intOrNull("iconCategory")),
                magnitude = props.intOrNull("magnitudeOfDelay") ?: 0,
                description = props.path("events").firstOrNull()?.textOrNull("description"),
                from = props.textOrNull("from"),
                to = props.textOrNull("to"),
                delayS = props.intOrNull("delay"),
                lengthM = props.doubleOrNull("length"),
                roadNumbers = props.path("roadNumbers").mapNotNull { it.takeIf { n -> n.isTextual }?.asText() },
                geometry = points,
            )
        }
}
