package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.testing.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement coverage: invalid polygons and duplicate vertices must be rejected with a reason the
 * user can act on.
 */
class PolygonValidatorTest {

    private val valid = TestGeo.squareVertices

    @Test
    fun `a plain square is valid`() {
        val result = PolygonValidator.validate(valid)
        assertTrue(result.isValid)
        assertEquals(null, result.reason)
    }

    @Test
    fun `a triangle is valid`() {
        assertTrue(PolygonValidator.validate(valid.take(3)).isValid)
    }

    @Test
    fun `a concave polygon is valid`() {
        assertTrue(PolygonValidator.validate(TestGeo.concaveL()).isValid)
    }

    @Test
    fun `a polygon with a clockwise winding is valid`() {
        assertTrue(PolygonValidator.validate(valid.reversed()).isValid)
    }

    // ---------------------------------------------------------------- too few vertices

    @Test
    fun `zero one and two vertices are rejected`() {
        assertFalse(PolygonValidator.validate(emptyList()).isValid)
        assertFalse(PolygonValidator.validate(valid.take(1)).isValid)

        val two = PolygonValidator.validate(valid.take(2))
        assertFalse(two.isValid)
        assertNotNull(two.reason)
        assertTrue("reason should be actionable", two.reason!!.contains("at least"))
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    fun `consecutive duplicate vertices are rejected`() {
        val withDuplicate = listOf(valid[0], valid[0], valid[1], valid[2])
        val result = PolygonValidator.validate(withDuplicate)
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("same point"))
    }

    @Test
    fun `repeating the first vertex at the end is rejected`() {
        // A caller that wrongly "closes" the ring produces this; the closing edge is implicit here.
        val closed = valid + valid.first()
        val result = PolygonValidator.validate(closed)
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("same point"))
    }

    @Test
    fun `non-adjacent duplicate vertices are rejected`() {
        val withDuplicate = listOf(valid[0], valid[1], valid[0], valid[2])
        val result = PolygonValidator.validate(withDuplicate)
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("duplicate", ignoreCase = true))
    }

    @Test
    fun `a nearly identical vertex within tolerance is treated as a duplicate`() {
        // 0.2 m away: within the 0.5 m duplicate tolerance.
        val nearly = TestGeo.at(valid[0], 0.2, 0.0)
        val withDuplicate = listOf(valid[0], nearly, valid[1], valid[2])
        assertFalse(PolygonValidator.validate(withDuplicate).isValid)
    }

    @Test
    fun `a vertex 5 metres away is a genuine distinct vertex`() {
        val distinct = TestGeo.at(valid[0], 5.0, -5.0)
        val polygon = listOf(distinct, valid[1], valid[2], valid[3])
        assertTrue(PolygonValidator.validate(polygon).isValid)
    }

    // ---------------------------------------------------------------- degenerate shapes

    @Test
    fun `collinear zero-area polygon is rejected`() {
        val result = PolygonValidator.validate(TestGeo.degenerateLine())
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("area"))
    }

    @Test
    fun `self-intersecting bow tie is rejected`() {
        val result = PolygonValidator.validate(TestGeo.bowtie())
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("crosses itself"))
    }

    @Test
    fun `self intersection check can be skipped explicitly`() {
        // Used by the live editor, which may briefly pass through a crossing state mid-draw.
        val result = PolygonValidator.validate(TestGeo.bowtie(), checkSelfIntersection = false)
        assertTrue(result.isValid)
    }

    // ---------------------------------------------------------------- bad coordinates

    @Test
    fun `latitude out of range is rejected`() {
        val bad = listOf(GeoPoint(91.0, 0.0), valid[1], valid[2])
        val result = PolygonValidator.validate(bad)
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("valid latitude"))
    }

    @Test
    fun `longitude out of range is rejected`() {
        val bad = listOf(GeoPoint(0.0, 181.0), valid[1], valid[2])
        assertFalse(PolygonValidator.validate(bad).isValid)
    }

    @Test
    fun `NaN and infinite coordinates are rejected`() {
        assertFalse(
            PolygonValidator.validate(listOf(GeoPoint(Double.NaN, 0.0), valid[1], valid[2])).isValid,
        )
        assertFalse(
            PolygonValidator.validate(
                listOf(GeoPoint(0.0, Double.POSITIVE_INFINITY), valid[1], valid[2]),
            ).isValid,
        )
    }

    // ---------------------------------------------------------------- draft validation

    @Test
    fun `draft validation reports how many vertices are still needed`() {
        val result = PolygonValidator.validateDraft(valid.take(2))
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("1 more"))
    }

    @Test
    fun `draft validation accepts a complete valid polygon`() {
        assertTrue(PolygonValidator.validateDraft(valid).isValid)
    }
}
