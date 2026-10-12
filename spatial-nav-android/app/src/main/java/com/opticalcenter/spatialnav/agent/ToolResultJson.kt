package com.opticalcenter.spatialnav.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.opticalcenter.spatialnav.spatial.DirectionQuery
import kotlin.math.roundToInt

/** Serialises tool results into compact JSON for the model's tool_result blocks. */
object ToolResultJson {
    private val mapper = ObjectMapper()

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return (v * f).roundToInt() / f
    }

    private fun place(p: PlaceResult) = linkedMapOf(
        "place_id" to p.place.id,
        "name" to p.place.name,
        "category" to p.place.category,
        "address" to p.place.address,
        "distance_m" to p.distanceM,
        "bearing_deg" to p.bearing.roundToInt(),
        "relative_direction" to p.direction.name.lowercase(),
    )

    private fun incident(r: IncidentReport) = linkedMapOf(
        "type" to r.category.name.lowercase(),
        "road_condition" to r.category.isRoadCondition,
        "description" to r.description,
        "severity" to when (r.magnitude) {
            1 -> "minor"; 2 -> "moderate"; 3 -> "major"; 4 -> "closure_or_indefinite"; else -> "unknown"
        },
        "delay_s" to r.delayS,
        "from" to r.from,
        "to" to r.to,
        "distance_ahead_m" to r.distanceAheadM,
    )

    fun toJson(result: ToolResult): String = mapper.writeValueAsString(toMap(result))

    fun isError(result: ToolResult) = result is ToolResult.Error

    private fun toMap(result: ToolResult): Map<String, Any?> = when (result) {
        is ToolResult.Location -> linkedMapOf(
            "lat" to round(result.lat, 6),
            "lon" to round(result.lon, 6),
            "address" to result.address,
            "heading_deg" to result.heading.roundToInt() % 360,
            "facing" to result.facing,
            "gps_accuracy_m" to result.accuracyM,
            "live_gps" to result.liveGps,
        )
        is ToolResult.Places -> linkedMapOf("query" to result.query, "results" to result.results.map(::place))
        is ToolResult.Surroundings -> linkedMapOf(
            "direction" to when (val q = result.query) {
                DirectionQuery.All -> "all"
                is DirectionQuery.Only -> q.direction.name.lowercase()
            },
            "radius_m" to result.radiusM,
            "pois" to result.pois.map(::place),
        )
        is ToolResult.NavigationStarted -> linkedMapOf(
            "navigating_to" to result.destination.name,
            "address" to result.destination.address,
            "distance_m" to result.distanceM,
            "duration_s" to result.durationS,
            "traffic_delay_s" to result.trafficDelayS,
            "arrival_time" to result.arrivalTime,
            "destination_relative_direction" to result.relativeDirection.name.lowercase(),
            "routes_compared" to result.alternativesConsidered,
            "first_instruction" to result.firstInstruction,
        )
        is ToolResult.RouteStatus -> with(result.status) {
            linkedMapOf(
                "destination" to destination.name,
                "remaining_m" to remainingM,
                "remaining_s" to remainingS,
                "arrival_time" to arrivalTime,
                "traffic_delay_ahead_s" to trafficDelayAheadS,
                "next_instruction" to nextInstruction,
                "distance_to_next_m" to distanceToNextM,
            )
        }
        is ToolResult.Incidents -> linkedMapOf("scope" to result.scope, "incidents" to result.reports.map(::incident))
        is ToolResult.FasterRoute -> linkedMapOf(
            "current_remaining_s" to result.currentRemainingS,
            "best_alternative_s" to result.bestAlternativeS,
            "saving_s" to result.savingS,
            "switched" to result.switched,
        )
        is ToolResult.NavigationStopped -> linkedMapOf(
            "was_navigating" to result.wasNavigating,
            "destination" to result.destination,
        )
        is ToolResult.Error -> linkedMapOf("error" to result.message, "suggestions" to result.suggestions)
    }
}
