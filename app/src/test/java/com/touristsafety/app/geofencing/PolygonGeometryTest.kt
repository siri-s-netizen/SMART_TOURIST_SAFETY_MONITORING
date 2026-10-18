package com.touristsafety.app.geofencing

import com.touristsafety.app.testing.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement coverage: "a coordinate inside a polygon", "a coordinate outside a polygon",
 * and the geometry primitives the containment service depends on.
 */
class PolygonGeometryTest {

    private val square = TestGeo.squareVertices

    // ---------------------------------------------------------------- containment

    @Test
    fun `centre of polygon is inside`() {
        assertTrue(PolygonGeometry.containsRayCasting(TestGeo.squareCenter, square))
    }

    @Test
    fun `point far outside polygon is outside`() {
        assertFalse(PolygonGeometry.containsRayCasting(TestGeo.outsideSouth, square))
    }

    @Test
    fun `containment does not depend on winding order`() {
        val reversed = square.reversed()
        assertTrue(PolygonGeometry.containsRayCasting(TestGeo.squareCenter, reversed))
        assertFalse(PolygonGeometry.containsRayCasting(TestGeo.outsideSouth, reversed))
    }

    @Test
    fun `rotating the ring does not change containment`() {
        // Same ring, different starting vertex.
        val rotated = square.drop(1) + square.first()
        assertTrue(PolygonGeometry.containsRayCasting(TestGeo.squareCenter, rotated))
        assertFalse(PolygonGeometry.containsRayCasting(TestGeo.outsideSouth, rotated))
    }

    @Test
    fun `concave polygon excludes its notch`() {
        val l = TestGeo.concaveL()
        // Inside the lower arm...
        assertTrue(PolygonGeometry.containsRayCasting(TestGeo.at(TestGeo.origin, 50.0, 200.0), l))
        // ...but the notch cut out of the shape is outside.
        assertFalse(PolygonGeometry.containsRayCasting(TestGeo.concaveNotchPoint, l))
    }

    @Test
    fun `a point horizontally aligned with an edge does not divide by zero`() {
        // Latitude exactly equal to two vertices of the south edge: the classic crash case.
        val onEdgeLatitude = TestGeo.at(TestGeo.origin, 0.0, 500.0)
        val result = PolygonGeometry.containsRayCasting(onEdgeLatitude, square)
        assertFalse("A point far outside must not be reported inside", result)
    }

    @Test
    fun `polygon with fewer than three vertices contains nothing`() {
        assertFalse(PolygonGeometry.containsRayCasting(TestGeo.squareCenter, emptyList()))
        assertFalse(PolygonGeometry.containsRayCasting(TestGeo.squareCenter, square.take(2)))
    }

    // ---------------------------------------------------------------- distances

    @Test
    fun `centre is half the side length from every edge`() {
        val distance = PolygonGeometry.distanceToBoundaryMeters(TestGeo.squareCenter, square)
        assertEquals(TestGeo.CENTER_TO_EDGE_METERS, distance, 1.0)
    }

    @Test
    fun `distance to boundary is measured in metres not degrees`() {
        val outside = TestGeo.outsideSouth
        val distance = PolygonGeometry.distanceToBoundaryMeters(outside, square)
        assertEquals(300.0, distance, 1.0)
    }

    @Test
    fun `point on the boundary has zero distance`() {
        val distance = PolygonGeometry.distanceToBoundaryMeters(TestGeo.onSouthEdge, square)
        assertEquals(0.0, distance, 0.2)
    }

    @Test
    fun `point to segment distance clamps beyond the segment ends`() {
        val a = TestGeo.at(TestGeo.origin, 0.0, 0.0)
        val b = TestGeo.at(TestGeo.origin, 0.0, 100.0)
        // 100 m to the west of vertex a: nearest point on the segment is a itself.
        val p = TestGeo.at(TestGeo.origin, 0.0, -100.0)
        assertEquals(100.0, PolygonGeometry.distanceToSegmentMeters(p, a, b), 1.0)
    }

    @Test
    fun `degenerate segment falls back to vertex distance`() {
        val a = TestGeo.at(TestGeo.origin, 0.0, 0.0)
        val p = TestGeo.at(TestGeo.origin, 50.0, 0.0)
        assertEquals(50.0, PolygonGeometry.distanceToSegmentMeters(p, a, a), 1.0)
    }

    @Test
    fun `distance is symmetric`() {
        val outward = PolygonGeometry.distanceToBoundaryMeters(TestGeo.outsideSouth, square)
        // Mirror of `outsideSouth`: the same 300 m, but north of the north edge instead of south of
        // the south edge. The square is 200 m tall, so that is 500 m north of the origin.
        val matchingInside = TestGeo.at(
            TestGeo.origin,
            TestGeo.SIDE_METERS + 300.0,
            TestGeo.CENTER_TO_EDGE_METERS,
        )
        val inward = PolygonGeometry.distanceToBoundaryMeters(matchingInside, square)
        assertEquals(outward, inward, 1.0)
    }

    // ---------------------------------------------------------------- area

    @Test
    fun `square area is side squared`() {
        val area = PolygonGeometry.areaSquareMeters(square)
        assertEquals(TestGeo.SIDE_METERS * TestGeo.SIDE_METERS, area, 100.0)
    }

    @Test
    fun `area ignores winding direction`() {
        val clockwise = PolygonGeometry.areaSquareMeters(square)
        val counterClockwise = PolygonGeometry.areaSquareMeters(square.reversed())
        assertEquals(clockwise, counterClockwise, 0.001)
    }

    @Test
    fun `degenerate collinear polygon has no area`() {
        assertEquals(0.0, PolygonGeometry.areaSquareMeters(TestGeo.degenerateLine()), 0.001)
    }

    // ---------------------------------------------------------------- self intersection

    @Test
    fun `a square does not self intersect`() {
        assertFalse(PolygonGeometry.isSelfIntersecting(square))
    }

    @Test
    fun `a triangle cannot self intersect`() {
        assertFalse(PolygonGeometry.isSelfIntersecting(square.take(3)))
    }

    @Test
    fun `a concave polygon does not self intersect`() {
        assertFalse(PolygonGeometry.isSelfIntersecting(TestGeo.concaveL()))
    }

    @Test
    fun `a bow tie self intersects`() {
        assertTrue(PolygonGeometry.isSelfIntersecting(TestGeo.bowtie()))
    }

    // ---------------------------------------------------------------- bounding box

    @Test
    fun `bounding box wraps every vertex`() {
        val box = PolygonGeometry.boundingBox(square)
        assertNotNull(box)
        requireNotNull(box)
        assertEquals(TestGeo.origin.latitude, box.south, 1e-9)
        assertEquals(TestGeo.origin.longitude, box.west, 1e-9)
        assertTrue(box.north > box.south)
        assertTrue(box.east > box.west)
    }

    @Test
    fun `bounding box of an empty polygon is null`() {
        assertEquals(null, PolygonGeometry.boundingBox(emptyList()))
    }
}
