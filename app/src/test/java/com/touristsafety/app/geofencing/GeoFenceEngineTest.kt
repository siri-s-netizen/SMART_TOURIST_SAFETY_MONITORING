package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.TestGeo
import com.touristsafety.app.testing.testReading
import com.touristsafety.app.testing.testZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement coverage: entry into a restricted zone, exit from a restricted zone, suppression of
 * repeated alerts while inside, and independent handling of overlapping zones.
 */
class GeoFenceEngineTest {

    private val policy = GeoFencePolicy()

    // ---------------------------------------------------------------- entry / exit

    @Test
    fun `moving from outside to inside a restricted zone raises exactly one ENTRY`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        val first = engine.evaluate(testReading(TestGeo.outsideSouth, accuracyMeters = 5f), listOf(zone))
        assertTrue("no transition expected while outside", first.transitions.isEmpty())

        val second = engine.evaluate(testReading(TestGeo.squareCenter, accuracyMeters = 5f), listOf(zone))
        assertEquals(1, second.transitions.size)
        assertEquals(AlertEventType.ENTRY, second.transitions.first().eventType)
        assertEquals(1L, second.transitions.first().zone.id)
        assertEquals(SafetyStatus.INSIDE_RESTRICTED, second.overallStatus)
    }

    @Test
    fun `leaving a restricted zone raises exactly one EXIT`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))
        engine.evaluate(testReading(TestGeo.squareCenter), listOf(zone))
        val exit = engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))

        assertEquals(1, exit.transitions.size)
        assertEquals(AlertEventType.EXIT, exit.transitions.first().eventType)
        assertEquals(SafetyStatus.SAFE, exit.overallStatus)
    }

    @Test
    fun `starting a session already inside a zone still raises ENTRY`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 7, type = ZoneType.DANGEROUS)

        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter), listOf(zone))

        assertEquals(1, evaluation.transitions.size)
        assertEquals(AlertEventType.ENTRY, evaluation.transitions.first().eventType)
        assertEquals(SafetyStatus.INSIDE_DANGEROUS, evaluation.overallStatus)
    }

    // ---------------------------------------------------------------- no repeat alerts

    @Test
    fun `many readings inside a zone produce only one ENTRY`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))

        var entries = 0
        repeat(25) {
            val evaluation = engine.evaluate(
                testReading(TestGeo.squareCenter, timestampMillis = it * 1_000L),
                listOf(zone),
            )
            entries += evaluation.transitions.count { t -> t.eventType == AlertEventType.ENTRY }
        }

        assertEquals("an hour inside must not produce an alert storm", 1, entries)
        assertTrue(engine.isInside(1L))
    }

    @Test
    fun `a full walk in and out produces one ENTRY and one EXIT`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        val path = listOf(
            TestGeo.outsideSouth, // outside
            TestGeo.outsideSouth,
            TestGeo.squareCenter, // entry
            TestGeo.squareCenter,
            TestGeo.squareCenter,
            TestGeo.outsideSouth, // exit
            TestGeo.outsideSouth,
        )

        val transitions = path.flatMap { engine.evaluate(testReading(it), listOf(zone)).transitions }

        assertEquals(2, transitions.size)
        assertEquals(AlertEventType.ENTRY, transitions[0].eventType)
        assertEquals(AlertEventType.EXIT, transitions[1].eventType)
    }

    // ---------------------------------------------------------------- uncertain readings

    @Test
    fun `a single poor-accuracy reading inside does not fabricate an alert`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))
        // 250 m accuracy, worse than the 100 m ceiling.
        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter, accuracyMeters = 250f), listOf(zone))

        assertTrue("a junk fix must not raise an alert", evaluation.transitions.isEmpty())
        assertEquals(SafetyStatus.UNCERTAIN, evaluation.overallStatus)
        assertFalse(engine.isInside(1L))
    }

    @Test
    fun `poor accuracy near the boundary reports NEAR_BOUNDARY and holds state`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))
        // 20 m inside the south edge: beyond the 8 m entry tolerance, but the 40 m accuracy circle
        // straddles the edge, so the engine must refuse to decide.
        val justInsideBeyondTolerance = TestGeo.at(
            TestGeo.origin,
            20.0,
            TestGeo.CENTER_TO_EDGE_METERS,
        )
        val evaluation = engine.evaluate(
            testReading(justInsideBeyondTolerance, accuracyMeters = 40f),
            listOf(zone),
        )

        assertTrue(evaluation.transitions.isEmpty())
        val status = evaluation.statuses.single()
        assertEquals(ZoneContainment.UNCERTAIN, status.containment)
        assertEquals(SafetyStatus.NEAR_BOUNDARY, status.status)
        assertFalse("membership must remain unchanged", status.inside)
    }

    @Test
    fun `an uncertain reading inside an already-entered zone does not raise a false EXIT`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))
        engine.evaluate(testReading(TestGeo.squareCenter), listOf(zone))

        // A bad fix that would otherwise look like a departure.
        val evaluation = engine.evaluate(testReading(TestGeo.outsideSouth, accuracyMeters = 400f), listOf(zone))

        assertTrue(evaluation.transitions.isEmpty())
        assertTrue("still recorded as inside", engine.isInside(1L))
    }

    @Test
    fun `hysteresis stops flapping when standing on the boundary`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))
        engine.evaluate(testReading(TestGeo.squareCenter), listOf(zone))

        // Oscillate around the edge: 1 m in, 1 m out, repeatedly.
        val oscillation = List(10) { index ->
            if (index % 2 == 0) TestGeo.justInsideSouthEdge else TestGeo.justOutsideSouthEdge
        }
        val transitions = oscillation.flatMap {
            engine.evaluate(testReading(it, accuracyMeters = 2f), listOf(zone)).transitions
        }

        assertTrue("standing on a boundary must not ping-pong", transitions.isEmpty())
    }

    // ---------------------------------------------------------------- overlapping zones

    @Test
    fun `overlapping zones are evaluated independently`() {
        val engine = GeoFenceEngine()
        val restricted = testZone(id = 1, type = ZoneType.RESTRICTED, vertices = TestGeo.squareVertices)
        val overlapping = testZone(
            id = 2,
            type = ZoneType.DANGEROUS,
            vertices = TestGeo.overlappingSquare(north = 50.0, east = 50.0),
        )
        val zones = listOf(restricted, overlapping)

        engine.evaluate(testReading(TestGeo.outsideSouth), zones)

        // The centre of zone 1 is ~50 m from every edge of zone 2, so it is inside both.
        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter), zones)

        assertEquals(2, evaluation.transitions.size)
        assertTrue(evaluation.transitions.all { it.eventType == AlertEventType.ENTRY })
        assertEquals(setOf(1L, 2L), evaluation.transitions.map { it.zone.id }.toSet())
        // Worst status wins: dangerous outranks restricted.
        assertEquals(SafetyStatus.INSIDE_DANGEROUS, evaluation.overallStatus)
    }

    @Test
    fun `leaving one of two overlapping zones only alerts for that zone`() {
        val engine = GeoFenceEngine()
        val inner = testZone(id = 1, type = ZoneType.RESTRICTED, vertices = TestGeo.squareVertices)
        // Shifted 50 m south so the inner square's centre is comfortably inside it (50 m clear of
        // every edge) rather than sitting exactly on a corner.
        val outer = testZone(
            id = 2,
            type = ZoneType.DANGEROUS,
            vertices = TestGeo.overlappingSquare(north = -50.0, east = 0.0),
        )
        val zones = listOf(inner, outer)

        engine.evaluate(testReading(TestGeo.outsideSouth), zones)
        engine.evaluate(testReading(TestGeo.squareCenter), zones)
        assertTrue("the centre of zone 1 is inside zone 2", engine.isInside(2L))

        // A point that is outside zone 1 but still comfortably inside zone 2.
        val betweenZones = TestGeo.at(TestGeo.origin, -25.0, 100.0)
        val evaluation = engine.evaluate(testReading(betweenZones), zones)

        assertEquals(1, evaluation.transitions.size)
        assertEquals(1L, evaluation.transitions.first().zone.id)
        assertEquals(AlertEventType.EXIT, evaluation.transitions.first().eventType)
        assertTrue("zone 2 membership is untouched", engine.isInside(2L))
    }

    // ---------------------------------------------------------------- safe zones

    @Test
    fun `entering a safe zone is recorded but is not a warning`() {
        val engine = GeoFenceEngine()
        val safe = testZone(id = 1, type = ZoneType.SAFE)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(safe))
        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter), listOf(safe))

        assertEquals(1, evaluation.transitions.size)
        assertEquals(SafetyStatus.INSIDE_SAFE_ZONE, evaluation.overallStatus)
        assertFalse(
            "a safe zone must not interrupt the user",
            evaluation.transitions.first().zone.type.raisesWarningOnEntry,
        )
    }

    // ---------------------------------------------------------------- disabled / invalid zones

    @Test
    fun `disabled zones are ignored entirely`() {
        val engine = GeoFenceEngine()
        val disabled = testZone(id = 1, type = ZoneType.RESTRICTED, enabled = false)

        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter), listOf(disabled))

        assertTrue(evaluation.transitions.isEmpty())
        assertTrue(evaluation.statuses.isEmpty())
        assertEquals(SafetyStatus.SAFE, evaluation.overallStatus)
    }

    @Test
    fun `zones with too few vertices are skipped instead of crashing`() {
        val engine = GeoFenceEngine()
        val broken = testZone(id = 1, vertices = TestGeo.squareVertices.take(2))

        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter), listOf(broken))

        assertTrue(evaluation.statuses.isEmpty())
        assertTrue(evaluation.transitions.isEmpty())
    }

    @Test
    fun `no zones means safe`() {
        val engine = GeoFenceEngine()
        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter), emptyList())
        assertEquals(SafetyStatus.SAFE, evaluation.overallStatus)
    }

    // ---------------------------------------------------------------- reset

    @Test
    fun `reset forgets membership so re-entering alerts again`() {
        val engine = GeoFenceEngine()
        val zone = testZone(id = 1, type = ZoneType.RESTRICTED)

        engine.evaluate(testReading(TestGeo.outsideSouth), listOf(zone))
        engine.evaluate(testReading(TestGeo.squareCenter), listOf(zone))
        assertTrue(engine.isInside(1L))

        engine.reset()
        assertFalse(engine.isInside(1L))

        val evaluation = engine.evaluate(testReading(TestGeo.squareCenter), listOf(zone))
        assertEquals(1, evaluation.transitions.size)
        assertEquals(AlertEventType.ENTRY, evaluation.transitions.first().eventType)
    }

    // ---------------------------------------------------------------- policy guards

    @Test
    fun `policy rejects an inverted hysteresis band`() {
        val failure = runCatching { GeoFencePolicy(entryToleranceMeters = 20.0, exitToleranceMeters = 5.0) }
        assertTrue(failure.isFailure)
    }
}
