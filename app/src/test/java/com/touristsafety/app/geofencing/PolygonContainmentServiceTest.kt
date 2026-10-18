package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.testing.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Requirement coverage: inside, outside, on the boundary, and the accuracy-aware policy that must
 * refuse to guess when the GPS reading is too coarse near an edge.
 */
class PolygonContainmentServiceTest {

    private val square = TestGeo.squareVertices
    private val service = RayCastingContainmentService

    private fun locate(
        point: com.touristsafety.app.domain.model.GeoPoint,
        accuracyMeters: Float,
        boundaryToleranceMeters: Double = GeoFencePolicy.DEFAULT_ENTRY_TOLERANCE_METERS,
        maxUsableAccuracyMeters: Double = GeoFencePolicy.DEFAULT_MAX_USABLE_ACCURACY_METERS,
    ) = service.locate(
        point = point,
        polygon = square,
        accuracyMeters = accuracyMeters,
        boundaryToleranceMeters = boundaryToleranceMeters,
        maxUsableAccuracyMeters = maxUsableAccuracyMeters,
    )

    // ---------------------------------------------------------------- decided cases

    @Test
    fun `good fix well inside reports INSIDE`() {
        val result = locate(TestGeo.squareCenter, accuracyMeters = 5f)
        assertEquals(ZoneContainment.INSIDE, result.containment)
        assertEquals(100.0, result.distanceToBoundaryMeters, 1.0)
    }

    @Test
    fun `good fix well outside reports OUTSIDE`() {
        val result = locate(TestGeo.outsideSouth, accuracyMeters = 5f)
        assertEquals(ZoneContainment.OUTSIDE, result.containment)
    }

    // ---------------------------------------------------------------- boundary

    @Test
    fun `point exactly on the edge reports BOUNDARY`() {
        val result = locate(TestGeo.onSouthEdge, accuracyMeters = 5f)
        assertEquals(ZoneContainment.BOUNDARY, result.containment)
    }

    @Test
    fun `point just inside the tolerance band reports BOUNDARY`() {
        // 3 m inside, default tolerance is 8 m.
        val result = locate(TestGeo.justInsideSouthEdge, accuracyMeters = 2f)
        assertEquals(ZoneContainment.BOUNDARY, result.containment)
    }

    @Test
    fun `boundary verdict is symmetric across the edge`() {
        val insideVerdict = locate(TestGeo.justInsideSouthEdge, accuracyMeters = 2f).containment
        val outsideVerdict = locate(TestGeo.justOutsideSouthEdge, accuracyMeters = 2f).containment
        assertEquals(ZoneContainment.BOUNDARY, insideVerdict)
        assertEquals(ZoneContainment.BOUNDARY, outsideVerdict)
    }

    // ---------------------------------------------------------------- accuracy policy

    @Test
    fun `poor accuracy straddling the boundary reports UNCERTAIN not a guess`() {
        // 3 m inside, but the fix is 40 m wide: could genuinely be either side.
        val result = locate(
            point = TestGeo.justInsideSouthEdge,
            accuracyMeters = 40f,
            boundaryToleranceMeters = 1.0,
        )
        assertEquals(ZoneContainment.UNCERTAIN, result.containment)
    }

    @Test
    fun `poor accuracy far from every boundary still decides`() {
        // 100 m from the edge with a 25 m accuracy: comfortably decided.
        val result = locate(TestGeo.squareCenter, accuracyMeters = 25f, boundaryToleranceMeters = 1.0)
        assertEquals(ZoneContainment.INSIDE, result.containment)
    }

    @Test
    fun `accuracy worse than the usable maximum reports UNCERTAIN everywhere`() {
        val centreVerdict = locate(TestGeo.squareCenter, accuracyMeters = 250f).containment
        val outsideVerdict = locate(TestGeo.outsideSouth, accuracyMeters = 250f).containment
        assertEquals(ZoneContainment.UNCERTAIN, centreVerdict)
        assertEquals(ZoneContainment.UNCERTAIN, outsideVerdict)
    }

    @Test
    fun `exactly at the accuracy ceiling is still usable`() {
        // A 500 m square: its centre sits 250 m from every edge, comfortably more than the 100 m
        // ceiling, so the ONLY rule that can reject this fix is the ceiling comparison itself. The
        // implementation uses a strict `>`, so accuracy == ceiling must still decide. (Using the
        // 200 m fixture square would not isolate this: its centre is ~99.999 m from an edge, so the
        // "circle straddles the edge" rule would fire instead for unrelated projection reasons.)
        val largeSquare = TestGeo.square(TestGeo.origin, sideMeters = 500.0)
        val centre = TestGeo.at(TestGeo.origin, 250.0, 250.0)
        val result = service.locate(
            point = centre,
            polygon = largeSquare,
            accuracyMeters = 100f,
            boundaryToleranceMeters = GeoFencePolicy.DEFAULT_ENTRY_TOLERANCE_METERS,
            maxUsableAccuracyMeters = 100.0,
        )
        assertEquals(ZoneContainment.INSIDE, result.containment)
    }

    @Test
    fun `unknown accuracy falls back to a conservative default`() {
        assertEquals(25.0, RayCastingContainmentService.normalizeAccuracy(0f), 1e-9)
        assertEquals(25.0, RayCastingContainmentService.normalizeAccuracy(-1f), 1e-9)
        assertEquals(25.0, RayCastingContainmentService.normalizeAccuracy(Float.NaN), 1e-9)
        assertEquals(7.5, RayCastingContainmentService.normalizeAccuracy(7.5f), 1e-9)
    }

    // ---------------------------------------------------------------- invalid input

    @Test
    fun `polygon with too few vertices is never inside`() {
        val result = service.locate(
            point = TestGeo.squareCenter,
            polygon = square.take(2),
            accuracyMeters = 5f,
            boundaryToleranceMeters = 8.0,
            maxUsableAccuracyMeters = 100.0,
        )
        assertEquals(ZoneContainment.UNCERTAIN, result.containment)
    }

    @Test
    fun `invalid coordinate is never inside`() {
        val bogus = com.touristsafety.app.domain.model.GeoPoint(Double.NaN, 2.2945)
        val result = service.locate(
            point = bogus,
            polygon = square,
            accuracyMeters = 5f,
            boundaryToleranceMeters = 8.0,
            maxUsableAccuracyMeters = 100.0,
        )
        assertEquals(ZoneContainment.UNCERTAIN, result.containment)
    }

    @Test
    fun `concave polygon notch is outside`() {
        val result = service.locate(
            point = TestGeo.concaveNotchPoint,
            polygon = TestGeo.concaveL(),
            accuracyMeters = 3f,
            boundaryToleranceMeters = 1.0,
            maxUsableAccuracyMeters = 100.0,
        )
        assertEquals(ZoneContainment.OUTSIDE, result.containment)
    }
}
