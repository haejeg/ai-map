package com.opticalcenter.spatialnav.data

import com.opticalcenter.spatialnav.data.tomtom.TomTomDataSource
import com.opticalcenter.spatialnav.data.tomtom.TomTomParsers
import com.opticalcenter.spatialnav.navigation.BoundingBox
import com.opticalcenter.spatialnav.navigation.IncidentCategory
import com.opticalcenter.spatialnav.navigation.TrafficCategory
import com.opticalcenter.spatialnav.spatial.GeoPoint
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Fixtures follow the documented response shapes of the TomTom APIs. */
class TomTomTest {
    private val routeJson = """
        {"formatVersion":"0.0.12","routes":[{
          "summary":{"lengthInMeters":5230,"travelTimeInSeconds":780,"trafficDelayInSeconds":120,
                     "trafficLengthInMeters":800,"departureTime":"2026-10-11T22:40:00+01:00",
                     "arrivalTime":"2026-10-11T22:53:00+01:00","noTrafficTravelTimeInSeconds":640},
          "legs":[{"summary":{"lengthInMeters":5230,"travelTimeInSeconds":780},
                   "points":[{"latitude":51.5007,"longitude":-0.1246},{"latitude":51.5010,"longitude":-0.1200},
                             {"latitude":51.5050,"longitude":-0.1150}]}],
          "sections":[{"startPointIndex":0,"endPointIndex":2,"sectionType":"TRAVEL_MODE","travelMode":"car"},
                      {"startPointIndex":1,"endPointIndex":2,"sectionType":"TRAFFIC","simpleCategory":"JAM",
                       "effectiveSpeedInKmh":12,"delayInSeconds":120,"magnitudeOfDelay":2,"tec":{"effectCode":4}}],
          "guidance":{"instructions":[
              {"routeOffsetInMeters":0,"travelTimeInSeconds":0,"point":{"latitude":51.5007,"longitude":-0.1246},
               "instructionType":"LOCATION_DEPARTURE","maneuver":"DEPART","message":"Leave from Bridge Street"},
              {"routeOffsetInMeters":320,"travelTimeInSeconds":40,"point":{"latitude":51.5010,"longitude":-0.1200},
               "instructionType":"TURN","street":"Victoria Embankment","maneuver":"TURN_LEFT",
               "message":"Turn left onto Victoria Embankment"},
              {"routeOffsetInMeters":5230,"travelTimeInSeconds":780,"point":{"latitude":51.5050,"longitude":-0.1150},
               "instructionType":"LOCATION_ARRIVAL","maneuver":"ARRIVE","message":"You have arrived at your destination"}
          ]}
        },{
          "summary":{"lengthInMeters":6100,"travelTimeInSeconds":900,"trafficDelayInSeconds":0},
          "legs":[{"points":[{"latitude":51.5007,"longitude":-0.1246},{"latitude":51.5050,"longitude":-0.1150}]}]
        }]}
    """.trimIndent()

    private val searchJson = """
        {"summary":{"query":"coffee","numResults":2},"results":[
          {"type":"POI","id":"g6JpZK","score":2.5,"dist":120.5,
           "poi":{"name":"Monmouth Coffee","phone":"+44 20","categories":["café/pub","coffee shop"],
                  "classifications":[{"code":"CAFE_PUB","names":[{"nameLocale":"en-US","name":"coffee shop"}]}]},
           "address":{"streetName":"Monmouth Street","municipality":"London","freeformAddress":"27 Monmouth Street, London WC2H 9EU"},
           "position":{"lat":51.51414,"lon":-0.12650}},
          {"type":"Point Address","id":"addr1","score":1.0,
           "address":{"freeformAddress":"10 Downing Street, London SW1A 2AA"},
           "position":{"lat":51.50336,"lon":-0.12768}}
        ]}
    """.trimIndent()

    private val incidentsJson = """
        {"incidents":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-0.1200,51.5010]},
           "properties":{"id":"inc-1","iconCategory":1,"magnitudeOfDelay":3,
                         "events":[{"description":"Accident","code":201,"iconCategory":1}],
                         "startTime":"2026-10-11T21:00:00Z","endTime":null,"from":"Bridge Street","to":"Whitehall",
                         "length":250.5,"delay":420,"roadNumbers":["A302"]}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-0.13,51.50],[-0.12,51.51]]},
           "properties":{"id":"inc-2","iconCategory":5,"magnitudeOfDelay":0,
                         "events":[{"description":"Icy road","code":1,"iconCategory":5}],"roadNumbers":[]}}
        ]}
    """.trimIndent()

    @Test
    fun parsesRoutesWithGuidanceAndTraffic() {
        val routes = TomTomParsers.parseRoutes(routeJson)
        assertEquals(2, routes.size)
        val r = routes.first()
        assertEquals(5230, r.lengthM)
        assertEquals(780, r.travelTimeS)
        assertEquals(120, r.trafficDelayS)
        assertEquals(640, r.noTrafficTravelTimeS)
        assertEquals(3, r.points.size)
        assertEquals(GeoPoint(51.5007, -0.1246), r.points.first())
        assertEquals(listOf("DEPART", "TURN_LEFT", "ARRIVE"), r.instructions.map { it.maneuver })
        assertEquals("Turn left onto Victoria Embankment", r.instructions[1].message)
        assertEquals(320.0, r.instructions[1].offsetM, 0.0)
        val jam = r.trafficSections.single()
        assertEquals(TrafficCategory.JAM, jam.category)
        assertEquals(2, jam.magnitude)
        assertEquals(120, jam.delayS)
        assertEquals(1 to 2, jam.startIndex to jam.endIndex)
        // Alternatives may lack guidance and sections.
        assertTrue(routes[1].instructions.isEmpty())
    }

    @Test
    fun routingErrorBodyThrows() {
        assertThrows(IllegalStateException::class.java) {
            TomTomParsers.parseRoutes("""{"error":{"description":"Engine error while executing route request: NO_ROUTE_FOUND"}}""")
        }
    }

    @Test
    fun parsesSearchResults() {
        val places = TomTomParsers.parsePlaces(searchJson)
        assertEquals(listOf("Monmouth Coffee", "10 Downing Street, London SW1A 2AA"), places.map { it.name })
        assertEquals("coffee shop", places[0].category)
        assertEquals("27 Monmouth Street, London WC2H 9EU", places[0].address)
        assertEquals(GeoPoint(51.51414, -0.12650), places[0].location)
        assertNull(places[1].category)
    }

    @Test
    fun parsesIncidentsIncludingRoadConditions() {
        val incidents = TomTomParsers.parseIncidents(incidentsJson)
        val accident = incidents[0]
        assertEquals(IncidentCategory.ACCIDENT, accident.category)
        assertEquals(3, accident.magnitude)
        assertEquals(420, accident.delayS)
        assertEquals(listOf(GeoPoint(51.5010, -0.1200)), accident.geometry) // lon,lat → lat,lon
        assertEquals(listOf("A302"), accident.roadNumbers)
        val ice = incidents[1]
        assertEquals(IncidentCategory.ICE, ice.category)
        assertTrue(ice.category.isRoadCondition)
        assertEquals(2, ice.geometry.size)
        assertNull(ice.delayS)
    }

    @Test
    fun parsesReverseGeocode() {
        assertEquals(
            "Bridge Street, London SW1A 2JR",
            TomTomParsers.parseReverseGeocode(
                """{"addresses":[{"address":{"freeformAddress":"Bridge Street, London SW1A 2JR"},"position":"51.5,-0.12"}]}""",
            ),
        )
    }

    // --- HTTP client -------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var source: TomTomDataSource

    @Before
    fun startServer() {
        server = MockWebServer().apply { start() }
        source = TomTomDataSource("test-key", baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun routeRequestAsksForLiveTrafficAndGuidance() = runBlocking {
        server.enqueue(MockResponse().setBody(routeJson))
        val routes = source.driveRoutes(GeoPoint(51.5007, -0.1246), GeoPoint(51.505, -0.115), headingDeg = 92.4, alternatives = 2)
        assertEquals(2, routes.size)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/routing/1/calculateRoute/51.500700,-0.124600:51.505000,-0.115000/json", url.encodedPath)
        assertEquals("test-key", url.queryParameter("key"))
        assertEquals("true", url.queryParameter("traffic"))
        assertEquals("car", url.queryParameter("travelMode"))
        assertEquals("traffic", url.queryParameter("sectionType"))
        assertEquals("text", url.queryParameter("instructionsType"))
        assertEquals("all", url.queryParameter("computeTravelTimeFor"))
        assertEquals("2", url.queryParameter("maxAlternatives"))
        assertEquals("92", url.queryParameter("vehicleHeading"))
    }

    @Test
    fun searchEncodesQueryAndBiasesToLocation() = runBlocking {
        server.enqueue(MockResponse().setBody(searchJson))
        source.searchPlaces("coffee & cake", GeoPoint(51.5, -0.12), radiusM = 5000, limit = 5)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/search/2/search/coffee%20&%20cake.json", url.encodedPath)
        assertEquals("51.500000", url.queryParameter("lat"))
        assertEquals("5000", url.queryParameter("radius"))
    }

    @Test
    fun incidentRequestUsesLonLatBboxAndClampsArea() = runBlocking {
        server.enqueue(MockResponse().setBody(incidentsJson))
        val incidents = source.incidents(BoundingBox(minLat = 51.4, minLon = -0.3, maxLat = 51.6, maxLon = 0.1))
        assertEquals(2, incidents.size)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/traffic/services/5/incidentDetails", url.encodedPath)
        assertEquals("-0.300000,51.400000,0.100000,51.600000", url.queryParameter("bbox"))
        assertTrue(url.queryParameter("fields")!!.startsWith("{incidents{type,geometry{type,coordinates},properties{"))
        assertEquals("present", url.queryParameter("timeValidityFilter"))

        server.enqueue(MockResponse().setBody("""{"incidents":[]}"""))
        source.incidents(BoundingBox(40.0, -10.0, 60.0, 20.0)) // far over 10,000 km²
        val (minLon, minLat, maxLon, maxLat) = server.takeRequest().requestUrl!!.queryParameter("bbox")!!.split(",").map { it.toDouble() }
        val box = BoundingBox(minLat, minLon, maxLat, maxLon)
        assertTrue(com.opticalcenter.spatialnav.spatial.GeoBounds.areaKm2(box) <= 10_000.0)
    }

    @Test
    fun httpErrorsBecomeReadableExceptions() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detailedError":{"code":"Forbidden"}}"""))
        val e = assertThrows(MapDataException::class.java) {
            runBlocking { source.nearbyPlaces(GeoPoint(51.5, -0.12), 500) }
        }
        assertTrue(e.message!!, "API key" in e.message!!)
    }
}
