package com.opticalcenter.spatialnav.agent

import com.opticalcenter.spatialnav.data.MockDataSource
import com.opticalcenter.spatialnav.navigation.NavigationManager
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.spatial.Direction
import com.opticalcenter.spatialnav.spatial.DirectionQuery
import com.opticalcenter.spatialnav.spatial.GeoPoint
import com.opticalcenter.spatialnav.spatial.MockPois
import com.opticalcenter.spatialnav.spatial.UserPose
import com.opticalcenter.spatialnav.testing.FakeMapData
import com.opticalcenter.spatialnav.testing.Fixtures
import com.opticalcenter.spatialnav.testing.Fixtures.east
import com.opticalcenter.spatialnav.testing.Fixtures.north
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * End-to-end through the offline assistant: utterance → tool call → tools →
 * navigation manager / fake map data → spoken reply.
 *
 * The user faces due east. Mock POIs sit at fixed absolute bearings:
 *   cafe 10°, pharmacy 40°, park 75°, bus 100°, library 150°,
 *   grocery 200°, museum 250°, atm 290°, hospital 330°,
 * so the expected quadrants are ahead: park, bus; right: library, grocery;
 * behind: museum, atm; left: cafe, pharmacy, hospital.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentTest {
    private val anchor = Fixtures.START

    private class World(
        val agent: Agent,
        val tools: NavigationTools,
        val manager: NavigationManager,
        val data: FakeMapData,
        val spoken: MutableList<String>,
        var telemetry: TelemetrySnapshot,
    )

    private fun TestScope.world(heading: Double = 90.0, data: FakeMapData = FakeMapData()): World {
        if (data.places.isEmpty()) {
            data.places = MockPois.around(anchor).map { Place(it.id, it.name, it.category, null, it.location) }
        }
        val spoken = mutableListOf<String>()
        lateinit var w: World
        val manager = NavigationManager(
            data = data,
            telemetry = { w.telemetry },
            scope = backgroundScope,
            speak = { spoken += it },
            clock = { 0L },
            zone = ZoneOffset.UTC,
            trafficRefreshMs = 120_000,
        )
        val tools = NavigationTools({ w.telemetry }, data, manager)
        w = World(
            Agent(tools), tools, manager, data, spoken,
            TelemetrySnapshot(UserPose(anchor.lat, anchor.lon, heading), accuracyM = 5f, hasFix = true),
        )
        return w
    }

    private fun World.moveTo(p: GeoPoint) {
        telemetry = telemetry.copy(pose = telemetry.pose.copy(lat = p.lat, lon = p.lon))
        manager.onLocation(p, 5.0)
    }

    // --- Intent parsing ------------------------------------------------------

    @Test
    fun parseIntent_extractsToolAndArguments() {
        val cases = listOf(
            "What is to my left?" to ToolCall("get_surrounding_pois", mapOf("direction" to "left")),
            "what's on my right" to ToolCall("get_surrounding_pois", mapOf("direction" to "right")),
            "anything in front of me?" to ToolCall("get_surrounding_pois", mapOf("direction" to "ahead")),
            "Where am I?" to ToolCall("get_current_location", emptyMap()),
            "Take me to the Public Library." to ToolCall("reroute_navigation", mapOf("destination" to "public library")),
            "How long until I get there?" to ToolCall("get_route_status", emptyMap()),
            "Any accidents on my route?" to ToolCall("get_traffic_incidents", emptyMap()),
            "Is there a faster route?" to ToolCall("find_faster_route", mapOf("apply" to "true")),
            "Stop navigation" to ToolCall("stop_navigation", emptyMap()),
            "Where's the nearest gas station?" to ToolCall("search_places", mapOf("query" to "gas station", "nearby_only" to "true")),
            "What's around me?" to ToolCall("get_surrounding_pois", mapOf("direction" to "all")),
            "Find coffee nearby" to ToolCall("search_places", mapOf("query" to "coffee", "nearby_only" to "true")),
        )
        for ((utterance, expected) in cases) assertEquals(utterance, expected, MockLlm.parseIntent(utterance))
        assertNull(MockLlm.parseIntent("tell me a joke"))
    }

    // --- Spatial questions ---------------------------------------------------

    private val expected = mapOf(
        Direction.LEFT to listOf("Blue Door Coffee", "Corner Pharmacy", "General Hospital"),
        Direction.AHEAD to listOf("Market Street Bus Stop", "Linden Pocket Park"),
        Direction.RIGHT to listOf("Fresh Grocer", "Public Library"),
        Direction.BEHIND to listOf("ATM Kiosk", "City History Museum"),
    )

    @Test
    fun surroundings_announceExactlyTheRequestedQuadrant() = runTest {
        val w = world()
        for ((direction, names) in expected) {
            val result = w.agent.respond("What is ${if (direction == Direction.AHEAD) "ahead of me" else "to my ${direction.name.lowercase()}"}?")
            val output = result.trace.single().output as ToolResult.Surroundings
            assertEquals(DirectionQuery.Only(direction), output.query)
            assertEquals(names, output.pois.map { it.place.name })
            var cursor = -1
            for (name in names) {
                val at = result.text.indexOf(name)
                assertTrue("\"$name\" missing or out of order in: ${result.text}", at > cursor)
                cursor = at
            }
        }
    }

    @Test
    fun reply_keepsAcronymCategories() = runTest {
        val text = world().agent.respond("What's behind me?").text
        assertTrue(text, "ATM Kiosk, an ATM about 60 meters away" in text)
        assertTrue(text, "City History Museum, a museum" in text)
    }

    @Test
    fun turningAround_swapsLeftAndRight() = runTest {
        val output = world(heading = 270.0).agent.respond("What is to my left?").trace.single().output as ToolResult.Surroundings
        assertEquals(listOf("Fresh Grocer", "Public Library"), output.pois.map { it.place.name })
    }

    @Test
    fun location_includesStreetAddress() = runTest {
        val result = world().agent.respond("Where am I?")
        assertEquals("You're near 221B Baker Street, London, facing east at 90 degrees.", result.text)
    }

    // --- Navigation ----------------------------------------------------------

    @Test
    fun startNavigation_startsTripAndConfirmsWithoutDoubleSpeaking() = runTest {
        val w = world()
        w.data.places = listOf(Fixtures.LIBRARY)
        val result = w.agent.respond("Take me to the public library")
        val started = result.trace.single().output as ToolResult.NavigationStarted
        assertEquals("Public Library", started.destination.name)
        assertEquals(240, started.durationS)
        assertEquals("12:04 AM", started.arrivalTime) // clock 0, UTC
        assertEquals(
            "Starting navigation to Public Library: 2.0 kilometers, about 4 minutes, including 4 minutes of traffic delay. " +
                "Arriving around 12:04 AM.",
            result.text,
        )
        assertNotNull(w.manager.trip.value)
        assertTrue("assistant confirms; the engine stays quiet", w.spoken.isEmpty())
    }

    @Test
    fun routeStatusDuringTrip() = runTest {
        val w = world()
        w.data.places = listOf(Fixtures.LIBRARY)
        w.agent.respond("Take me to the public library")
        w.moveTo(east(500.0))
        val result = w.agent.respond("How long until I get there?")
        val status = (result.trace.single().output as ToolResult.RouteStatus).status
        assertEquals(1500, status.remainingM)
        assertEquals(180, status.remainingS)
        assertEquals("Turn left onto Baker Street", status.nextInstruction)
        assertTrue(result.text, result.text.startsWith("1.5 kilometers to Public Library, about 3 minutes."))
        assertTrue(result.text, "Next, in 500 meters, turn left onto Baker Street." in result.text)
        assertTrue("800 m turn prompt was spoken by the engine", w.spoken.any { it.startsWith("In 500 meters") || it.startsWith("In 800") })
    }

    @Test
    fun trafficOnRoute_reportedOnRequestAndAnnouncedProactively() = runTest {
        val w = world()
        w.data.places = listOf(Fixtures.LIBRARY)
        w.data.incidentList = listOf(Fixtures.incident("crash", north(east(1500.0), 15.0)))
        w.agent.respond("Take me to the public library")
        runCurrent() // monitor's first incident check
        assertTrue(w.spoken.toString(), w.spoken.any { it == "Heads up: Accident in 1.5 kilometers near Baker Street, adding about 5 minutes." })

        val result = w.agent.respond("Any traffic problems on my route?")
        val incidents = result.trace.single().output as ToolResult.Incidents
        assertEquals("route", incidents.scope)
        assertEquals(1500, incidents.reports.single().distanceAheadM)
        assertEquals("There is one incident on your route: Accident in 1.5 kilometers, 5 minutes delay.", result.text)
        assertNotNull("incident request was bounded to the route", w.data.lastIncidentBox)
    }

    @Test
    fun periodicTrafficRefreshSwitchesToFasterRoute() = runTest {
        val w = world()
        w.data.places = listOf(Fixtures.LIBRARY)
        w.agent.respond("Take me to the public library")
        w.moveTo(east(500.0)) // 180 s remaining on the current route
        w.data.routes = listOf(Fixtures.straightRoute(travelTimeS = 50))
        advanceTimeBy(120_001)
        runCurrent()
        assertTrue(w.spoken.toString(), "Traffic update: found a faster route, saving 2 minutes." in w.spoken)
        assertEquals(50, w.manager.trip.value!!.route.travelTimeS)
    }

    @Test
    fun leavingTheRouteTriggersAutomaticReroute() = runTest {
        val w = world()
        w.data.places = listOf(Fixtures.LIBRARY)
        w.agent.respond("Take me to the public library")
        val requestsBefore = w.data.routeRequests
        repeat(3) { w.moveTo(north(east(400.0), 150.0)) }
        runCurrent()
        assertEquals(requestsBefore + 1, w.data.routeRequests)
        assertEquals("Rerouting.", w.spoken.first { it.startsWith("Rerouting") })
        assertTrue(w.spoken.toString(), w.spoken.any { it.startsWith("New route: 4 minutes to Public Library.") })
        assertFalse(w.manager.trip.value!!.rerouting)
    }

    @Test
    fun stopNavigation() = runTest {
        val w = world()
        w.data.places = listOf(Fixtures.LIBRARY)
        w.agent.respond("Take me to the public library")
        val result = w.agent.respond("Stop navigation")
        assertEquals("Navigation to Public Library ended.", result.text)
        assertNull(w.manager.trip.value)
        assertEquals("You're not navigating anywhere right now.", w.agent.respond("Cancel the route").text)
    }

    @Test
    fun withoutTomTomKeyNavigationExplainsWhatIsMissing() = runTest {
        val w = world(data = FakeMapData(supportsRouting = false))
        val offline = NavigationManager(MockDataSource { anchor }, { w.telemetry }, backgroundScope, { })
        val tools = NavigationTools({ w.telemetry }, MockDataSource { anchor }, offline)
        val result = Agent(tools).respond("Take me to the public library")
        assertTrue(result.text, result.text.startsWith("Sorry, driving directions need a TomTom API key."))
        // Spatial questions still work offline with the mock places.
        val left = Agent(tools).respond("What is to my left?").trace.single().output as ToolResult.Surroundings
        assertEquals(expected[Direction.LEFT], left.pois.map { it.place.name })
    }

    @Test
    fun unrelatedSpeech_fallsBackToHelp() = runTest {
        val result = world().agent.respond("sing me a song")
        assertTrue(result.trace.isEmpty())
        assertEquals(MockLlm.HELP_TEXT, result.text)
    }

    @Test
    fun toolResultJson_isCompactAndTyped() = runTest {
        val w = world()
        val json = ToolResultJson.toJson(w.tools.getSurroundingPois("left"))
        assertTrue(json, json.startsWith("""{"direction":"left","radius_m":500,"pois":[{"place_id":"cafe","name":"Blue Door Coffee""""))
        assertTrue(json, """"relative_direction":"left"""" in json)
        assertEquals("""{"error":"no search text was given.","suggestions":[]}""", ToolResultJson.toJson(w.tools.searchPlaces(" ")))
    }

    @Test
    fun toolDefinitions_allHandled() = runTest {
        val w = world()
        assertEquals(8, NavigationTools.DEFINITIONS.size)
        for (def in NavigationTools.DEFINITIONS) {
            val result = w.tools.execute(def.name, emptyMap())
            assertFalse("${def.name} is dispatched", result is ToolResult.Error && result.message.startsWith("unknown tool"))
        }
    }
}
