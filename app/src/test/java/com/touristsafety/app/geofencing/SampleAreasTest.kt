package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "add sample areas" button is only trustworthy if the areas it drops are real, valid polygons
 * placed where the button promises. All of that maths is pure, so it is checked here on the JVM.
 */
class SampleAreasTest {

    private val center = TestGeo.origin

    private fun centroid(vertices: List<GeoPoint>): GeoPoint = GeoPoint(
        latitude = vertices.map { it.latitude }.average(),
        longitude = vertices.map { it.longitude }.average(),
    )

    @Test
    fun `create makes three areas, one of each type, all valid polygons`() {
        val zones = SampleAreas.create(center)

        assertEquals(3, zones.size)
        assertEquals(
            listOf(ZoneType.RESTRICTED, ZoneType.DANGEROUS, ZoneType.SAFE),
            zones.map { it.type },
        )
        zones.forEach { zone ->
            assertEquals(4, zone.vertexCount)
            assertTrue(zone.hasEnoughVertices)
            assertTrue(PolygonValidator.validate(zone.vertices).isValid)
            assertEquals(
                SampleAreas.SIDE_METERS * SampleAreas.SIDE_METERS,
                PolygonGeometry.areaSquareMeters(zone.vertices),
                200.0,
            )
            assertTrue(zone.enabled)
        }
    }

    @Test
    fun `every area is labelled as a sample so it is never mistaken for real data`() {
        SampleAreas.create(center).forEach { zone ->
            assertTrue(zone.name.startsWith("Sample:"))
            assertTrue(zone.description.contains("sample", ignoreCase = true))
        }
    }

    @Test
    fun `the areas form a row, one spacing apart, and cannot overlap`() {
        val shapes = SampleAreas.create(center).map { centroid(it.vertices) }

        // slots -1, 0, +1 → the middle sits between the other two, each about one spacing away.
        assertEquals(SampleAreas.SPACING_METERS, shapes[0].flatDistanceMeters(shapes[1]), 2.0)
        assertEquals(SampleAreas.SPACING_METERS, shapes[1].flatDistanceMeters(shapes[2]), 2.0)

        // A spacing (340 m) is far wider than a side (140 m), so the squares cannot touch.
        assertTrue(shapes[0].flatDistanceMeters(shapes[2]) > SampleAreas.SIDE_METERS)
    }

    @Test
    fun `the areas stay close to the user`() {
        SampleAreas.create(center).forEach { zone ->
            assertTrue(
                centroid(zone.vertices).flatDistanceMeters(center) <= SampleAreas.SPACING_METERS + 1.0,
            )
        }
    }

    @Test
    fun `the middle area is centred on the requested point`() {
        val middle = centroid(SampleAreas.create(center)[1].vertices)

        assertTrue(middle.flatDistanceMeters(center) < 1.0)
    }

    @Test
    fun `longitude spacing widens towards the poles to hold the distance steady`() {
        val equator = SampleAreas.offset(GeoPoint(0.0, 0.0), 1)
        val north = SampleAreas.offset(GeoPoint(60.0, 0.0), 1)

        assertTrue(
            "a degree of longitude must cover more ground nearer the pole",
            north.longitude > equator.longitude,
        )
        assertTrue(GeoPoint(0.0, 0.0).flatDistanceMeters(equator) in 339.0..341.0)
        assertTrue(GeoPoint(60.0, 0.0).flatDistanceMeters(north) in 339.0..341.0)
    }
}
