package com.opticalcenter.spatialnav.navigation

import com.opticalcenter.spatialnav.agent.IncidentReport
import com.opticalcenter.spatialnav.agent.NavigationController
import com.opticalcenter.spatialnav.agent.RouteStatusInfo
import com.opticalcenter.spatialnav.agent.TelemetrySnapshot
import com.opticalcenter.spatialnav.agent.ToolResult
import com.opticalcenter.spatialnav.data.MapDataException
import com.opticalcenter.spatialnav.data.MapDataSource
import com.opticalcenter.spatialnav.spatial.GeoBounds
import com.opticalcenter.spatialnav.spatial.GeoPoint
import com.opticalcenter.spatialnav.spatial.RoutePolyline
import com.opticalcenter.spatialnav.spatial.SpatialEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Everything the UI needs to draw an active trip. */
data class TripState(
    val destination: Place,
    val route: Route,
    val progress: NavigationProgress?,
    val incidentsAhead: List<IncidentAhead>,
    val rerouting: Boolean,
    val arrived: Boolean,
)

/**
 * Owns the active trip: starts it, feeds GPS fixes to the [NavigationSession],
 * reroutes when the driver leaves the route, and periodically refreshes live
 * traffic to announce new incidents and switch to faster routes.
 */
class NavigationManager(
    private val data: MapDataSource,
    private val telemetry: () -> TelemetrySnapshot,
    private val scope: CoroutineScope,
    private val speak: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val trafficRefreshMs: Long = 120_000,
    /** Heading to pass to the router, when it reflects the direction of travel (GPS course). */
    private val travelHeading: () -> Double? = { null },
) : NavigationController {
    private val _trip = MutableStateFlow<TripState?>(null)
    val trip: StateFlow<TripState?> = _trip.asStateFlow()

    private var session: NavigationSession? = null
    private var monitorJob: Job? = null
    private var rerouteJob: Job? = null
    private val announcedIncidents = mutableSetOf<String>()

    override val isNavigating: Boolean get() = session?.isArrived == false

    /** Called by the assistant's tool, which confirms the trip in its own reply. */
    override suspend fun start(destination: Place): ToolResult = start(destination, announce = false)

    suspend fun start(destination: Place, announce: Boolean): ToolResult {
        if (!data.supportsRouting) {
            return ToolResult.Error("driving directions need a TomTom API key. Add tomtom.apiKey to local.properties.")
        }
        val origin = telemetry().pose
        val routes = data.driveRoutes(origin.point, destination.location, travelHeading(), alternatives = 2)
        val best = routes.minBy { it.travelTimeS }

        stop()
        val newSession = NavigationSession(destination, best, announceStart = announce)
        session = newSession
        announcedIncidents.clear()
        _trip.value = TripState(destination, best, null, emptyList(), rerouting = false, arrived = false)
        // Process the current position now (intro / first instruction) rather than on the next GPS fix.
        onLocation(origin.point, telemetry().accuracyM?.toDouble())
        startMonitoring()

        val direction = SpatialEngine.analyze(origin, destination.location).direction
        return ToolResult.NavigationStarted(
            destination = destination,
            distanceM = best.lengthM,
            durationS = best.travelTimeS,
            trafficDelayS = best.trafficDelayS,
            arrivalTime = clockTime(best.travelTimeS),
            relativeDirection = direction,
            alternativesConsidered = routes.size,
            firstInstruction = best.instructions.firstOrNull { it.maneuver != "DEPART" }?.message,
        )
    }

    /** Silent: the assistant's reply (or the End button) is the confirmation. */
    override fun stop(): ToolResult.NavigationStopped {
        val was = session
        monitorJob?.cancel()
        rerouteJob?.cancel()
        monitorJob = null
        rerouteJob = null
        session = null
        _trip.value = null
        return ToolResult.NavigationStopped(was != null && !was.isArrived, was?.destination?.name)
    }

    /** Feed every GPS fix here while navigating. */
    fun onLocation(fix: GeoPoint, accuracyM: Double?) {
        val s = session ?: return
        val update = s.update(fix, accuracyM)
        update.prompts.forEach(speak)
        _trip.value = _trip.value?.copy(progress = update.progress, route = s.route, arrived = s.isArrived)
        when (update.event) {
            NavigationEvent.OffRoute -> reroute(fix)
            NavigationEvent.Arrived -> {
                monitorJob?.cancel()
                monitorJob = null
            }
            null -> Unit
        }
    }

    private fun reroute(from: GeoPoint) {
        if (rerouteJob?.isActive == true) return
        val s = session ?: return
        _trip.value = _trip.value?.copy(rerouting = true)
        speak("Rerouting.")
        rerouteJob = scope.launch {
            try {
                val route = data.driveRoutes(from, s.destination.location, travelHeading()).minBy { it.travelTimeS }
                if (session !== s) return@launch
                s.replaceRoute(route)
                _trip.value = _trip.value?.copy(route = route, rerouting = false)
                val first = route.instructions.firstOrNull { it.maneuver != "DEPART" }
                speak("New route: ${Phrasing.duration(route.travelTimeS)} to ${s.destination.name}." +
                    (first?.let { " ${Phrasing.sentenceStart(it.message)}" } ?: ""))
                onLocation(telemetry().pose.point, telemetry().accuracyM?.toDouble())
            } catch (e: MapDataException) {
                _trip.value = _trip.value?.copy(rerouting = false)
                speak("I couldn't find a new route. ${e.message}")
            }
        }
    }

    override fun status(): RouteStatusInfo? {
        val s = session ?: return null
        val p = _trip.value?.progress ?: return null
        return RouteStatusInfo(
            destination = s.destination,
            remainingM = p.remainingM.roundToInt(),
            remainingS = p.remainingS,
            trafficDelayAheadS = p.trafficDelayAheadS,
            nextInstruction = p.nextInstruction?.message,
            distanceToNextM = p.distanceToNextM?.roundToInt(),
            arrivalTime = clockTime(p.remainingS),
        )
    }

    override suspend fun findFasterRoute(apply: Boolean): ToolResult {
        val s = session ?: return ToolResult.Error("there is no active navigation.")
        val current = _trip.value?.progress?.remainingS ?: s.route.travelTimeS
        val here = telemetry().pose.point
        val candidate = data.driveRoutes(here, s.destination.location, travelHeading(), alternatives = 2)
            .minBy { it.travelTimeS }
        val saving = (current - candidate.travelTimeS).coerceAtLeast(0)
        val switch = apply && NavigationSession.isWorthSwitching(current, candidate.travelTimeS)
        if (switch && session === s) {
            s.replaceRoute(candidate)
            _trip.value = _trip.value?.copy(route = candidate)
            onLocation(here, telemetry().accuracyM?.toDouble())
        }
        return ToolResult.FasterRoute(current, candidate.travelTimeS, saving, switched = switch)
    }

    override suspend fun incidentsOnRoute(): List<IncidentReport> {
        val ahead = refreshIncidents()
        return ahead.take(MAX_REPORTED).map { item ->
            val i = item.incident
            IncidentReport(i.category, i.description, i.magnitude, i.delayS, i.from, i.to, item.distanceAheadM.roundToInt())
        }
    }

    /** Fetch incidents along the remaining route and publish them for the map. */
    private suspend fun refreshIncidents(): List<IncidentAhead> {
        val s = session ?: return emptyList()
        val polyline = RoutePolyline(s.route.points)
        val along = _trip.value?.progress?.alongM ?: 0.0
        val remaining = polyline.remainingPoints(along)
        // Check the next stretch of road; very long trips are rechecked as they progress.
        val box = GeoBounds.clampArea(GeoBounds.of(remaining, paddingM = 200.0), INCIDENT_BOX_KM2)
        val ahead = TrafficAnalysis.incidentsAhead(polyline, along, data.incidents(box))
        if (session === s) _trip.value = _trip.value?.copy(incidentsAhead = ahead)
        return ahead
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    announceNewIncidents(refreshIncidents())
                } catch (_: MapDataException) {
                    // Transient; try again next cycle.
                }
                delay(trafficRefreshMs)
                try {
                    val result = findFasterRoute(apply = true)
                    if (result is ToolResult.FasterRoute && result.switched) {
                        speak("Traffic update: found a faster route, saving ${Phrasing.duration(result.savingS)}.")
                    }
                } catch (_: MapDataException) {
                }
            }
        }
    }

    private fun announceNewIncidents(ahead: List<IncidentAhead>) {
        val worth = ahead.filter {
            it.distanceAheadM <= ANNOUNCE_WITHIN_M && it.incident.id !in announcedIncidents &&
                (it.incident.magnitude >= 2 || it.incident.category.isRoadCondition ||
                    it.incident.category == IncidentCategory.ROAD_CLOSED)
        }
        for (item in worth.take(2)) {
            announcedIncidents += item.incident.id
            speak("Heads up: ${TrafficAnalysis.describe(item)}")
        }
    }

    private fun clockTime(secondsFromNow: Int): String =
        DateTimeFormatter.ofPattern("h:mm a").withZone(zone).format(Instant.ofEpochMilli(clock() + secondsFromNow * 1000L))

    companion object {
        private const val INCIDENT_BOX_KM2 = 2_500.0
        private const val ANNOUNCE_WITHIN_M = 5_000.0
        private const val MAX_REPORTED = 5
    }
}
