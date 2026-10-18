package com.touristsafety.app.simulation

import com.touristsafety.app.data.database.PolygonCodec
import com.touristsafety.app.geofencing.PolygonGeometry
import com.touristsafety.app.geofencing.PolygonValidator
import com.touristsafety.app.testing.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulator is only useful if its route is *provably* outside → inside → outside. These tests
 * assert exactly that, using the same ray-casting routine the production engine uses, so a demo can
 * never silently fail to cross a boundary.
 */
class SimulationPathBuilderTest {

    @Test
    fun `a generated test square has four vertices and the requested area`() {
        val vertices = SimulationPathBuilder.createTestSquare(TestGeo.origin, sideMeters = 200.0)

        assertEquals(4, vertices.size)
        assertEquals(200.0 * 200.0, PolygonGeometry.areaSquareMeters(vertices), 400.0)
        assertTrue(PolygonValidator.validate(vertices).isValid)
    }

    @Test
    fun `the inside point really is inside the polygon`() {
        val inside = SimulationPathBuilder.findInsidePoint(TestGeo.squareVertices)

        assertNotNull(inside)
        assertTrue(PolygonGeometry.containsRayCasting(inside!!, TestGeo.squareVertices))
    }

    @Test
    fun `the inside point of a concave polygon avoids the notch`() {
        val vertices = TestGeo.concaveL()

        // The notch is outside the L: a centroid-based pick would land here and break the demo.
        assertTrue(
            "fixture sanity: the notch must be outside the polygon",
            !PolygonGeometry.containsRayCasting(TestGeo.concaveNotchPoint, vertices),
        )

        val inside = SimulationPathBuilder.findInsidePoint(vertices)

        assertNotNull(inside)
        assertTrue(PolygonGeometry.containsRayCasting(inside!!, vertices))
        assertTrue(
            "the chosen point must not be the notch",
            inside.flatDistanceMeters(TestGeo.concaveNotchPoint) > 1.0,
        )
    }

    @Test
    fun `a degenerate polygon has no inside point`() {
        assertNull(SimulationPathBuilder.findInsidePoint(TestGeo.degenerateLine()))
        assertNull(SimulationPathBuilder.findInsidePoint(emptyList()))
    }

    @Test
    fun `the route starts outside, passes inside and ends outside`() {
        val vertices = TestGeo.squareVertices
        val route = SimulationPathBuilder.routeThroughZone(vertices)

        assertTrue("a route must be produced", route.size >= 3)

        val containment = route.map { PolygonGeometry.containsRayCasting(it, vertices) }

        assertTrue("the route must not start inside the zone", !containment.first())
        assertTrue("the route must not end inside the zone", !containment.last())
        assertTrue("the walk must pass through the zone", containment.any { it })
    }

    @Test
    fun `the route through a concave polygon still crosses it`() {
        val vertices = TestGeo.concaveL()
        val route = SimulationPathBuilder.routeThroughZone(vertices)

        val containment = route.map { PolygonGeometry.containsRayCasting(it, vertices) }

        assertTrue(route.isNotEmpty())
        assertTrue(!containment.first())
        assertTrue(!containment.last())
        assertTrue(containment.any { it })
    }

    @Test
    fun `a degenerate polygon produces no route at all rather than a fake one`() {
        assertTrue(SimulationPathBuilder.routeThroughZone(TestGeo.degenerateLine()).isEmpty())
        assertTrue(SimulationPathBuilder.routeThroughZone(emptyList()).isEmpty())
    }

    @Test
    fun `the route is a single straight line north to south`() {
        val route = SimulationPathBuilder.routeThroughZone(TestGeo.squareVertices)

        val longitudes = route.map { it.longitude }.distinct()
        assertEquals("every sample shares one longitude", 1, longitudes.size)
        assertTrue(route.first().latitude < route.last().latitude)
    }

    @Test
    fun `route endpoints clear the bounding box by the requested overshoot`() {
        val vertices = TestGeo.squareVertices
        val box = PolygonGeometry.boundingBox(vertices)!!

        val route = SimulationPathBuilder.routeThroughZone(vertices, overshootMeters = 150.0)

        val start = route.first()
        val end = route.last()
        assertTrue("start must be south of the box", start.latitude < box.south)
        assertTrue("end must be north of the box", end.latitude > box.north)

        val overshoot = start.flatDistanceMeters(
            com.touristsafety.app.domain.model.GeoPoint(box.south, start.longitude),
        )
        assertTrue("the overshoot should be generous", overshoot > 100.0)
    }

    @Test
    fun `a square is closed correctly regardless of vertex order`() {
        val clockwise = TestGeo.squareVertices
        val counterClockwise = clockwise.reversed()

        assertTrue(PolygonGeometry.containsRayCasting(TestGeo.squareCenter, clockwise))
        assertTrue(PolygonGeometry.containsRayCasting(TestGeo.squareCenter, counterClockwise))

        assertTrue(PolygonValidator.validate(counterClockwise).isValid)
    }

    @Test
    fun `a zone built for the simulator survives a codec round trip`() {
        val vertices = SimulationPathBuilder.createTestSquare(TestGeo.origin)

        val decoded = PolygonCodec.decode(PolygonCodec.encode(vertices))

        assertEquals(vertices.size, decoded.size)
        vertices.zip(decoded).forEach { (original, restored) ->
            assertEquals(original.latitude, restored.latitude, 1e-12)
            assertEquals(original.longitude, restored.longitude, 1e-12)
        }
    }
}
