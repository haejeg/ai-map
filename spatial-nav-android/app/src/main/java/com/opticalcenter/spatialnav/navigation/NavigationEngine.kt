package com.opticalcenter.spatialnav.navigation

import com.opticalcenter.spatialnav.spatial.GeoPoint
import com.opticalcenter.spatialnav.spatial.RoutePolyline
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class GuidanceConfig(
    /** Distances before a manoeuvre at which it is announced, farthest first. */
    val promptDistancesM: List<Double> = listOf(800.0, 250.0, 40.0),
    /** Base distance from the route that counts as off-route. */
    val offRouteThresholdM: Double = 50.0,
    /** Consecutive off-route fixes needed before rerouting (filters GPS jitter). */
    val offRouteFixes: Int = 3,
    /** Within this distance of the end of the route counts as arrived. */
    val arrivalRadiusM: Double = 30.0,
)

data class NavigationProgress(
    val snapped: GeoPoint,
    val alongM: Double,
    val remainingM: Double,
    val remainingS: Int,
    val nextInstruction: Instruction?,
    val distanceToNextM: Double?,
    val offRouteM: Double,
    /** Traffic delay still ahead on the current route, seconds. */
    val trafficDelayAheadS: Int,
)

sealed interface NavigationEvent {
    /** The driver has left the route; the caller should request a new one. */
    data object OffRoute : NavigationEvent
    data object Arrived : NavigationEvent
}

data class NavigationUpdate(
    val progress: NavigationProgress,
    /** Sentences to speak now, in order. */
    val prompts: List<String>,
    val event: NavigationEvent?,
)

/**
 * Turn-by-turn guidance state for one trip. Feed it GPS fixes with [update];
 * swap in a new route (reroute / faster route) with [replaceRoute].
 */
class NavigationSession(
    val destination: Place,
    route: Route,
    private val config: GuidanceConfig = GuidanceConfig(),
    /** Speak a "Starting route…" intro on the first fix. Off when the assistant confirms the trip itself. */
    announceStart: Boolean = true,
) {
    var route: Route = route
        private set
    private var polyline = RoutePolyline(route.points)
    private var lastSegment = 0
    private var offRouteStreak = 0
    private var offRouteReported = false
    private var arrived = false
    private var introduced = !announceStart
    /** Instruction index → prompt stages already spoken. */
    private val spoken = mutableMapOf<Int, MutableSet<Int>>()

    val isArrived: Boolean get() = arrived

    fun replaceRoute(newRoute: Route) {
        route = newRoute
        polyline = RoutePolyline(newRoute.points)
        lastSegment = 0
        offRouteStreak = 0
        offRouteReported = false
        spoken.clear()
        introduced = true // the caller announces reroutes itself
    }

    fun update(fix: GeoPoint, accuracyM: Double? = null): NavigationUpdate {
        // Allow a little backwards slack for GPS jitter, but don't snap to earlier laps of the route.
        val projection = polyline.project(fix, fromSegment = max(0, lastSegment - 2))
        lastSegment = projection.segmentIndex

        val threshold = max(config.offRouteThresholdM, (accuracyM ?: 0.0) * 1.5)
        offRouteStreak = if (projection.offRouteM > threshold) offRouteStreak + 1 else 0

        val remainingM = (polyline.lengthM - projection.alongM).coerceAtLeast(0.0)
        val remainingS = if (polyline.lengthM > 0) {
            (route.travelTimeS * remainingM / polyline.lengthM).roundToInt()
        } else {
            0
        }

        val nextIndex = route.instructions.indexOfFirst { it.offsetM > projection.alongM + 1.0 && !isDepart(it) }
        val next = route.instructions.getOrNull(nextIndex)
        val distanceToNext = next?.let { (it.offsetM - projection.alongM).coerceAtLeast(0.0) }

        val trafficAhead = route.trafficSections
            .filter { it.endIndex > projection.segmentIndex }
            .sumOf { it.delayS }

        val progress = NavigationProgress(
            snapped = projection.point,
            alongM = projection.alongM,
            remainingM = remainingM,
            remainingS = remainingS,
            nextInstruction = next,
            distanceToNextM = distanceToNext,
            offRouteM = projection.offRouteM,
            trafficDelayAheadS = trafficAhead,
        )

        val prompts = mutableListOf<String>()
        var event: NavigationEvent? = null

        if (!introduced) {
            introduced = true
            val first = next?.let { " ${Phrasing.sentenceStart(it.message)}" }.orEmpty()
            prompts += "Starting route to ${destination.name}. ${Phrasing.duration(route.travelTimeS)}, " +
                "${Phrasing.distance(route.lengthM.toDouble())}.$first"
            next?.let { markStagesUpTo(nextIndex, distanceToNext!!) }
        }

        when {
            arrived -> Unit
            remainingM <= config.arrivalRadiusM -> {
                arrived = true
                event = NavigationEvent.Arrived
                prompts += "You have arrived at ${destination.name}."
            }
            offRouteStreak >= config.offRouteFixes -> {
                if (!offRouteReported) {
                    offRouteReported = true
                    event = NavigationEvent.OffRoute
                }
            }
            next != null && distanceToNext != null && !isArrive(next) -> {
                stagePrompt(nextIndex, next, distanceToNext)?.let { prompts += it }
            }
        }
        return NavigationUpdate(progress, prompts, event)
    }

    /** Announce the closest stage the driver has crossed and mark farther ones done. */
    private fun stagePrompt(index: Int, instruction: Instruction, distance: Double): String? {
        val stage = config.promptDistancesM.indexOfLast { distance <= it + STAGE_TOLERANCE_M }
        if (stage == -1) return null
        val done = spoken.getOrPut(index) { mutableSetOf() }
        if (stage in done) return null
        for (s in 0..stage) done += s
        val isFinal = stage == config.promptDistancesM.lastIndex
        return if (isFinal) {
            Phrasing.sentenceStart(instruction.message)
        } else {
            "In ${Phrasing.distance(distance)}, ${Phrasing.sentenceContinue(instruction.message)}"
        }
    }

    private fun markStagesUpTo(index: Int, distance: Double) {
        val stage = config.promptDistancesM.indexOfLast { distance <= it + STAGE_TOLERANCE_M }
        if (stage >= 0) spoken.getOrPut(index) { mutableSetOf() }.addAll(0..stage)
    }

    private fun isDepart(i: Instruction) = i.maneuver == "DEPART"
    private fun isArrive(i: Instruction) = i.maneuver.startsWith("ARRIVE")

    companion object {
        /** Absorbs floating-point error at stage boundaries; far below GPS precision. */
        private const val STAGE_TOLERANCE_M = 0.5

        /** Switch to a candidate route only if it saves at least this much. */
        const val MIN_SAVING_S = 120
        const val MIN_SAVING_FRACTION = 0.08

        fun isWorthSwitching(currentRemainingS: Int, candidateS: Int): Boolean {
            val saving = currentRemainingS - candidateS
            return saving >= MIN_SAVING_S && saving >= currentRemainingS * MIN_SAVING_FRACTION
        }
    }
}

data class IncidentAhead(val incident: Incident, val distanceAheadM: Double)

object TrafficAnalysis {
    /** Incidents within [corridorM] of the route and ahead of [alongM], nearest first. */
    fun incidentsAhead(
        polyline: RoutePolyline,
        alongM: Double,
        incidents: List<Incident>,
        corridorM: Double = 60.0,
    ): List<IncidentAhead> = incidents.mapNotNull { incident ->
        // An incident counts if any of its points lies on the remaining route.
        incident.geometry
            .map { polyline.project(it) }
            .filter { it.offRouteM <= corridorM && it.alongM > alongM }
            .minByOrNull { it.alongM }
            ?.let { IncidentAhead(incident, it.alongM - alongM) }
    }.sortedBy { it.distanceAheadM }

    fun describe(item: IncidentAhead): String {
        val i = item.incident
        val what = (i.description ?: i.category.spoken).trim().trimEnd('.').replaceFirstChar { it.uppercaseChar() }
        val where = i.from?.let { " near $it" }.orEmpty()
        val delay = i.delayS?.takeIf { it >= 60 }?.let { ", adding about ${Phrasing.duration(it)}" }.orEmpty()
        return "$what in ${Phrasing.distance(item.distanceAheadM)}$where$delay."
    }
}

/** Spoken-language formatting shared by guidance and the assistant. */
object Phrasing {
    fun distance(m: Double): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} kilometers"
        m >= 1000 -> String.format(Locale.US, "%.1f kilometers", m / 1000)
        m >= 100 -> "${(m / 50).roundToInt() * 50} meters"
        else -> "${max(10, (m / 10).roundToInt() * 10)} meters"
    }

    fun duration(seconds: Int): String {
        val minutes = (seconds / 60.0).roundToInt()
        return when {
            minutes < 1 -> "under a minute"
            minutes == 1 -> "1 minute"
            minutes < 60 -> "$minutes minutes"
            else -> {
                val h = minutes / 60
                val m = minutes % 60
                val hours = if (h == 1) "1 hour" else "$h hours"
                if (m == 0) hours else "$hours $m minutes"
            }
        }
    }

    /** Capitalise the first letter and ensure trailing punctuation. */
    fun sentenceStart(text: String): String {
        val t = text.trim().trimEnd('.')
        if (t.isEmpty()) return ""
        return t.replaceFirstChar { it.uppercaseChar() } + "."
    }

    /** Lower-case the first letter (unless it starts an acronym) for use mid-sentence. */
    fun sentenceContinue(text: String): String {
        val t = text.trim().trimEnd('.')
        if (t.length >= 2 && t[1].isUpperCase()) return "$t."
        return t.replaceFirstChar { it.lowercaseChar() } + "."
    }
}
