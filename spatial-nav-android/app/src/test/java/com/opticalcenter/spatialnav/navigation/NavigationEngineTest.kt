package com.opticalcenter.spatialnav.navigation

import com.opticalcenter.spatialnav.spatial.RoutePolyline
import com.opticalcenter.spatialnav.testing.Fixtures
import com.opticalcenter.spatialnav.testing.Fixtures.east
import com.opticalcenter.spatialnav.testing.Fixtures.north
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {
    private fun session(announce: Boolean = true) =
        NavigationSession(Fixtures.LIBRARY, Fixtures.straightRoute(), announceStart = announce)

    /** Drive the route in [step]-metre increments, collecting (position, prompt) pairs. */
    private fun drive(s: NavigationSession, toM: Double, step: Double = 10.0, fromM: Double = 0.0): List<Pair<Double, String>> {
        val spoken = mutableListOf<Pair<Double, String>>()
        var m = fromM
        while (m <= toM) {
            s.update(east(m)).prompts.forEach { spoken += m to it }
            m += step
        }
        return spoken
    }

    @Test
    fun introAnnouncesDestinationDurationAndFirstTurn() {
        val first = session().update(east(0.0))
        assertEquals(
            "Starting route to Public Library. 4 minutes, 2.0 kilometers. Turn left onto Baker Street.",
            first.prompts.single(),
        )
    }

    @Test
    fun noIntroWhenAssistantConfirmsTrip() {
        assertTrue(session(announce = false).update(east(0.0)).prompts.isEmpty())
    }

    @Test
    fun turnIsAnnouncedOncePerStageAtTheRightDistances() {
        val spoken = drive(session(announce = false), toM = 1000.0)
        assertEquals(
            listOf(
                200.0 to "In 800 meters, turn left onto Baker Street.",
                750.0 to "In 250 meters, turn left onto Baker Street.",
                960.0 to "Turn left onto Baker Street.",
            ),
            spoken,
        )
    }

    @Test
    fun startingCloseToATurnSkipsFartherStages() {
        val spoken = drive(session(announce = false), fromM = 800.0, toM = 1000.0)
        assertEquals(listOf(800.0 to "In 200 meters, turn left onto Baker Street.", 960.0 to "Turn left onto Baker Street."), spoken)
    }

    @Test
    fun progressTracksRemainingDistanceTimeAndNextTurn() {
        val s = session(announce = false)
        val p = s.update(east(500.0)).progress
        assertEquals(1500.0, p.remainingM, 1.0)
        assertEquals(180, p.remainingS) // 240 s × 1500/2000
        assertEquals("TURN_LEFT", p.nextInstruction?.maneuver)
        assertEquals(500.0, p.distanceToNextM!!, 1.0)
        assertEquals(240, p.trafficDelayAheadS)
        // Past the jam (sections end at point 18 = 1800 m), no delay remains.
        assertEquals(0, s.update(east(1850.0)).progress.trafficDelayAheadS)
    }

    @Test
    fun offRouteNeedsConsecutiveFixesAndIsReportedOnce() {
        val s = session(announce = false)
        s.update(east(300.0))
        val off = north(east(400.0), 120.0)
        assertNull(s.update(off).event)
        assertNull(s.update(off).event)
        assertEquals(NavigationEvent.OffRoute, s.update(off).event)
        assertNull("reported once until a new route arrives", s.update(off).event)
    }

    @Test
    fun gpsJitterWithinThresholdIsNotOffRoute() {
        val s = session(announce = false)
        repeat(10) { i -> assertNull(s.update(north(east(100.0 + i * 20), if (i % 2 == 0) 30.0 else -30.0)).event) }
    }

    @Test
    fun poorAccuracyWidensTheOffRouteThreshold() {
        val s = session(announce = false)
        val off = north(east(400.0), 70.0)
        repeat(5) { assertNull(s.update(off, accuracyM = 60.0).event) } // threshold 90 m
    }

    @Test
    fun arrivalIsDetectedAndAnnounced() {
        val s = session(announce = false)
        drive(s, toM = 1900.0, step = 100.0)
        val update = s.update(east(1985.0))
        assertEquals(NavigationEvent.Arrived, update.event)
        assertEquals(listOf("You have arrived at Public Library."), update.prompts)
        assertTrue(s.isArrived)
        assertNull(s.update(east(1990.0)).event)
    }

    @Test
    fun replaceRouteResetsGuidance() {
        val s = session(announce = false)
        drive(s, toM = 400.0) // 800 m stage already spoken
        s.replaceRoute(Fixtures.straightRoute(travelTimeS = 200))
        // The new route's turn is announced again from scratch.
        assertEquals(listOf("In 600 meters, turn left onto Baker Street."), s.update(east(400.0)).prompts)
    }

    @Test
    fun fasterRouteNeedsMeaningfulSaving() {
        assertTrue(NavigationSession.isWorthSwitching(currentRemainingS = 1800, candidateS = 1500))
        assertFalse("saves only 90 s", NavigationSession.isWorthSwitching(1800, 1710))
        assertFalse("2 min of a 3 h trip is under 8%", NavigationSession.isWorthSwitching(10_800, 10_660))
        assertFalse(NavigationSession.isWorthSwitching(600, 700))
    }

    @Test
    fun incidentsAheadAreFilteredToTheRouteCorridorAndAhead() {
        val polyline = RoutePolyline(Fixtures.straightRoute().points)
        val ahead = Fixtures.incident("ahead", north(east(1500.0), 20.0))
        val behind = Fixtures.incident("behind", east(200.0))
        val offRoute = Fixtures.incident("off", north(east(1200.0), 400.0))
        val result = TrafficAnalysis.incidentsAhead(polyline, alongM = 600.0, incidents = listOf(offRoute, behind, ahead))
        assertEquals(listOf("ahead"), result.map { it.incident.id })
        assertEquals(900.0, result.single().distanceAheadM, 1.0)
        assertEquals(
            "Accident in 900 meters near Baker Street, adding about 5 minutes.",
            TrafficAnalysis.describe(result.single()),
        )
    }

    @Test
    fun phrasing() {
        assertEquals("850 meters", Phrasing.distance(840.0))
        assertEquals("40 meters", Phrasing.distance(37.0))
        assertEquals("1.5 kilometers", Phrasing.distance(1480.0))
        assertEquals("23 kilometers", Phrasing.distance(23_400.0))
        assertEquals("under a minute", Phrasing.duration(20))
        assertEquals("1 hour 5 minutes", Phrasing.duration(3900))
        assertEquals("2 hours", Phrasing.duration(7200))
        assertEquals("turn left onto A1.", Phrasing.sentenceContinue("Turn left onto A1"))
        assertEquals("ATM ahead.", Phrasing.sentenceContinue("ATM ahead"))
    }
}
