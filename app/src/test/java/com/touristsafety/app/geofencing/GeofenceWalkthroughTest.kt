package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoPoint
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
 * Successive real-location updates pushed through the *actual* geofence evaluation path.
 *
 * This is the test the review asked for: not "does ray casting work", but "given a sequence of fixes
 * like the ones a real device delivers while someone walks — including the sloppy ones — what does the
 * engine, and therefore the screen, say at each step?".
 *
 * Everything below the scripted coordinates is production code: the real [GeoFenceEngine], the real
 * [RayCastingContainmentService] and the real [PolygonGeometry]. Nothing is stubbed, so the statuses
 * asserted here are byte-for-byte the statuses the UI renders in the "Containment right now" list and
 * the ones [GeoFenceEngine.evaluate] reports as the overall banner status.
 *
 * The polygon is a realistic drawn zone: a 300 m square, which at the app's default zoom is roughly
 * what a user taps out on screen. The walk runs up the square's middle meridian, so the nearest edge is
 * always the one being crossed (150 m from the centre to either side edge).
 */
class GeofenceWalkthroughTest {

    /** South-west corner of the zone, 100 m north of the walk's start. */
    private val zoneCorner: GeoPoint = TestGeo.at(TestGeo.origin, 100.0, 0.0)

    private val zoneVertices: List<GeoPoint> = TestGeo.square(zoneCorner, sideMeters = 300.0)

    private val zone = testZone(id = 7, type = ZoneType.RESTRICTED, vertices = zoneVertices)

    private val policy = GeoFencePolicy()

    /** A fix on the walk's middle meridian, [northOfCorner] metres north of the zone's south edge. */
    private fun onWalk(northOfCorner: Double): GeoPoint =
        TestGeo.at(zoneCorner, northOfCorner, 150.0)

    /** One step of the walk: the point, its accuracy, and the label for failure messages. */
    private data class Step(val label: String, val point: GeoPoint, val accuracy: Float)

    /** A plausible walk: approach, cross the edge, settle inside, then leave the way you came. */
    private val walk = listOf(
        Step("100 m outside", onWalk(-100.0), 8f),
        Step("20 m outside", onWalk(-20.0), 8f),
        Step("5 m outside — inside the entry margin", onWalk(-5.0), 8f),
        Step("exactly on the edge", onWalk(0.0), 8f),
        Step("5 m inside — still inside the entry margin", onWalk(5.0), 8f),
        Step("20 m inside — clear of the margin", onWalk(20.0), 8f),
        Step("centre of the zone", onWalk(150.0), 6f),
        Step("back to 5 m inside the south edge", onWalk(5.0), 8f),
        Step("4 m outside — inside the wider exit margin", onWalk(-4.0), 8f),
        Step("40 m outside — clear of the exit margin", onWalk(-40.0), 8f),
    )

    @Test
    fun `a walk across the boundary produces exactly the expected verdict at every step`() {
        val engine = GeoFenceEngine(RayCastingContainmentService)

        // The expected (containment, inside, rendered status) triple for each step above.
        val expected = listOf(
            Triple(ZoneContainment.OUTSIDE, false, SafetyStatus.SAFE),
            Triple(ZoneContainment.OUTSIDE, false, SafetyStatus.SAFE),
            Triple(ZoneContainment.BOUNDARY, false, SafetyStatus.NEAR_BOUNDARY),
            Triple(ZoneContainment.BOUNDARY, false, SafetyStatus.NEAR_BOUNDARY),
            Triple(ZoneContainment.BOUNDARY, false, SafetyStatus.NEAR_BOUNDARY),
            Triple(ZoneContainment.INSIDE, true, SafetyStatus.INSIDE_RESTRICTED),
            Triple(ZoneContainment.INSIDE, true, SafetyStatus.INSIDE_RESTRICTED),
            Triple(ZoneContainment.BOUNDARY, true, SafetyStatus.NEAR_BOUNDARY),
            Triple(ZoneContainment.BOUNDARY, true, SafetyStatus.NEAR_BOUNDARY),
            Triple(ZoneContainment.OUTSIDE, false, SafetyStatus.SAFE),
        )

        walk.forEachIndexed { index, step ->
            val evaluation = engine.evaluate(
                reading = testReading(step.point, accuracyMeters = step.accuracy, timestampMillis = 1_000L * index),
                zones = listOf(zone),
                policy = policy,
            )

            val status = evaluation.statuses.single()
            val (containment, inside, rendered) = expected[index]

            assertEquals("${step.label}: containment", containment, status.containment)
            assertEquals("${step.label}: remembered membership", inside, status.inside)
            assertEquals("${step.label}: status shown to the user", rendered, status.status)
            assertEquals(
                "${step.label}: overall banner must be the worst per-zone verdict",
                rendered,
                evaluation.overallStatus,
            )
        }
    }

    @Test
    fun `the walk raises exactly one ENTRY and exactly one EXIT`() {
        val engine = GeoFenceEngine(RayCastingContainmentService)
        val transitions = walk.flatMapIndexed { index, step ->
            engine.evaluate(
                reading = testReading(step.point, accuracyMeters = step.accuracy, timestampMillis = 1_000L * index),
                zones = listOf(zone),
                policy = policy,
            ).transitions
        }

        assertEquals("only the crossing should alert, not every fix", 2, transitions.size)
        assertEquals(AlertEventType.ENTRY, transitions.first().eventType)
        assertEquals(AlertEventType.EXIT, transitions.last().eventType)
        assertEquals(zone.id, transitions.first().zone.id)
    }

    @Test
    fun `the entry is reported at the first step that is clearly inside, not on the edge`() {
        val engine = GeoFenceEngine(RayCastingContainmentService)

        val entryStepIndex = walk.indices.first { index ->
            engine.evaluate(
                reading = testReading(walk[index].point, accuracyMeters = walk[index].accuracy),
                zones = listOf(zone),
                policy = policy,
            ).transitions.isNotEmpty()
        }

        // Steps 0-4 are outside or inside the tolerance band; step 5 is the first decided INSIDE.
        assertEquals(5, entryStepIndex)
        assertEquals("20 m inside — clear of the margin", walk[entryStepIndex].label)
    }

    // ------------------------------------------------------------------ accuracy handling on the walk

    @Test
    fun `a coarse fix near the edge is reported as undecided rather than guessed`() {
        val engine = GeoFenceEngine(RayCastingContainmentService)

        // 10 m inside the south edge, but with a 12 m accuracy the true position could be either side.
        val evaluation = engine.evaluate(
            reading = testReading(onWalk(10.0), accuracyMeters = 12f),
            zones = listOf(zone),
            policy = policy,
        )

        val status = evaluation.statuses.single()
        assertEquals(ZoneContainment.UNCERTAIN, status.containment)
        assertEquals(SafetyStatus.NEAR_BOUNDARY, status.status)
        assertTrue("an undecided fix must not fabricate membership", evaluation.transitions.isEmpty())
    }

    @Test
    fun `a fix worse than the ceiling is undecided even deep inside the zone`() {
        val engine = GeoFenceEngine(RayCastingContainmentService)

        val evaluation = engine.evaluate(
            reading = testReading(onWalk(150.0), accuracyMeters = 250f),
            zones = listOf(zone),
            policy = policy,
        )

        assertEquals(ZoneContainment.UNCERTAIN, evaluation.statuses.single().containment)
        assertEquals(SafetyStatus.UNCERTAIN, evaluation.overallStatus)
    }

    @Test
    fun `a zone narrower than the tolerance band can never be decided as inside`() {
        // An honest limitation, pinned so it cannot silently change. A 10 m square is entirely inside
        // the 8 m entry band, so every point in it returns BOUNDARY and the membership flag never
        // moves: the user sees "near the boundary", never a fabricated INSIDE and never a false alert.
        val tiny = testZone(
            id = 8,
            type = ZoneType.DANGEROUS,
            vertices = TestGeo.square(zoneCorner, sideMeters = 10.0),
        )
        val engine = GeoFenceEngine(RayCastingContainmentService)

        val evaluation = engine.evaluate(
            reading = testReading(TestGeo.at(zoneCorner, 5.0, 5.0), accuracyMeters = 5f),
            zones = listOf(tiny),
            policy = policy,
        )

        val status = evaluation.statuses.single()
        assertEquals(ZoneContainment.BOUNDARY, status.containment)
        assertEquals(SafetyStatus.NEAR_BOUNDARY, status.status)
        assertFalse(status.inside)
        assertTrue(evaluation.transitions.isEmpty())
    }

    @Test
    fun `a fix whose accuracy circle covers the whole zone is undecided, never inside`() {
        // The accuracy-radius rule: standing at the centre of a 100 m square with a ±60 m fix, the true
        // position could be outside, so the engine refuses to decide. This is the case a naive
        // implementation would report as INSIDE dangerous area.
        val square = testZone(
            id = 10,
            type = ZoneType.DANGEROUS,
            vertices = TestGeo.square(zoneCorner, sideMeters = 100.0),
        )
        val engine = GeoFenceEngine(RayCastingContainmentService)

        val evaluation = engine.evaluate(
            reading = testReading(TestGeo.at(zoneCorner, 50.0, 50.0), accuracyMeters = 60f),
            zones = listOf(square),
            policy = policy,
        )

        val status = evaluation.statuses.single()
        assertEquals(ZoneContainment.UNCERTAIN, status.containment)
        assertEquals(SafetyStatus.NEAR_BOUNDARY, status.status)
        assertFalse("an undecided fix must not be recorded as inside", status.inside)
        assertTrue(evaluation.transitions.isEmpty())
    }

    @Test
    fun `distance to the edge is reported so the verdict is explainable`() {
        val engine = GeoFenceEngine(RayCastingContainmentService)

        val outside = engine.evaluate(
            reading = testReading(onWalk(-20.0), accuracyMeters = 8f),
            zones = listOf(zone),
            policy = policy,
        ).statuses.single()

        assertEquals(20.0, outside.distanceToBoundaryMeters, 1.0)

        val inside = engine.evaluate(
            reading = testReading(onWalk(20.0), accuracyMeters = 8f),
            zones = listOf(zone),
            policy = policy,
        ).statuses.single()

        assertEquals(20.0, inside.distanceToBoundaryMeters, 1.0)
    }

    @Test
    fun `a disabled zone is never evaluated`() {
        val engine = GeoFenceEngine(RayCastingContainmentService)
        val disabled = testZone(id = 9, vertices = zoneVertices, enabled = false)

        val evaluation = engine.evaluate(
            reading = testReading(onWalk(150.0), accuracyMeters = 5f),
            zones = listOf(disabled),
            policy = policy,
        )

        assertTrue(evaluation.statuses.isEmpty())
        assertEquals("with nothing testable the banner must stay safe", SafetyStatus.SAFE, evaluation.overallStatus)
    }
}
