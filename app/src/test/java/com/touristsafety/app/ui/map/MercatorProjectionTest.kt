package com.touristsafety.app.ui.map

import androidx.compose.ui.geometry.Offset
import com.touristsafety.app.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the key-less map projection.
 *
 * These are the checks that let the OpenStreetMap fallback be trusted without a device: if the
 * projection round-trips and lands on the correct tile for known coordinates, the fallback map is
 * placing geometry correctly.
 */
class MercatorProjectionTest {

    private val origin = GeoPoint(0.0, 0.0)
    private val eiffel = GeoPoint(48.8584, 2.2945)

    @Test
    fun `world size doubles per zoom level`() {
        assertEquals(256.0, MercatorProjection.worldSizePx(0.0), 1e-9)
        assertEquals(512.0, MercatorProjection.worldSizePx(1.0), 1e-9)
        assertEquals(256.0 * 1024.0, MercatorProjection.worldSizePx(10.0), 1e-6)
    }

    @Test
    fun `null island is the centre of the world`() {
        assertEquals(128.0, MercatorProjection.lonToWorldX(0.0, 0.0), 1e-9)
        assertEquals(128.0, MercatorProjection.latToWorldY(0.0, 0.0), 1e-9)
    }

    @Test
    fun `longitude round trips through world x`() {
        for (lon in listOf(-179.9, -90.0, 0.0, 2.2945, 90.0, 179.9)) {
            assertEquals(lon, MercatorProjection.worldXToLon(MercatorProjection.lonToWorldX(lon, 12.0), 12.0), 1e-9)
        }
    }

    @Test
    fun `latitude round trips through world y`() {
        for (lat in listOf(-80.0, -45.0, 0.0, 48.8584, 80.0)) {
            assertEquals(lat, MercatorProjection.worldYToLat(MercatorProjection.latToWorldY(lat, 12.0), 12.0), 1e-9)
        }
    }

    @Test
    fun `known slippy-map tile indices are correct`() {
        // Greenwich at zoom 1 is the SE corner of the top-left quadrant => (1, 1).
        assertEquals(1 to 1, MercatorProjection.tileIndexFor(origin, 1))
        // Zoom 0 has exactly one tile.
        assertEquals(0 to 0, MercatorProjection.tileIndexFor(origin, 0))
        assertEquals(0 to 0, MercatorProjection.tileIndexFor(GeoPoint(48.8584, 2.2945), 0))
    }

    @Test
    fun `screen mapping round trips through the inverse`() {
        val viewportW = 720f
        val viewportH = 1280f
        val zoom = 15.0
        val centre = eiffel
        val screens = listOf(
            Offset(360f, 640f),
            Offset(0f, 0f),
            Offset(719f, 1279f),
            Offset(120f, 900f),
        )
        for (screen in screens) {
            val geo = MercatorProjection.toGeoPoint(screen, centre, zoom, viewportW, viewportH)
            val back = MercatorProjection.toScreen(geo, centre, zoom, viewportW, viewportH)
            assertEquals(screen.x.toDouble(), back.x.toDouble(), 0.05)
            assertEquals(screen.y.toDouble(), back.y.toDouble(), 0.05)
        }
    }

    @Test
    fun `the viewport centre maps back to the camera centre`() {
        val back = MercatorProjection.toGeoPoint(
            offset = Offset(360f, 640f),
            center = eiffel,
            zoom = 16.0,
            viewportWidthPx = 720f,
            viewportHeightPx = 1280f,
        )
        assertEquals(eiffel.latitude, back.latitude, 1e-6)
        assertEquals(eiffel.longitude, back.longitude, 1e-6)
    }

    @Test
    fun `ground resolution matches the textbook value at the equator`() {
        // 2 * pi * R / 256 = 156543.03 m/px at zoom 0.
        assertEquals(156_543.03, MercatorProjection.metersPerPixel(0.0, 0.0), 0.1)
    }

    @Test
    fun `panning right moves the centre west`() {
        val moved = MercatorProjection.panCenterByPixels(eiffel, panXPx = 100f, panYPx = 0f, zoom = 15.0)
        assertTrue("dragging content right must reveal more of the west", moved.longitude < eiffel.longitude)
        assertEquals(eiffel.latitude, moved.latitude, 1e-9)
    }

    @Test
    fun `panning down moves the centre north`() {
        val moved = MercatorProjection.panCenterByPixels(eiffel, panXPx = 0f, panYPx = 100f, zoom = 15.0)
        assertTrue("dragging content down must reveal more of the north", moved.latitude > eiffel.latitude)
        assertEquals(eiffel.longitude, moved.longitude, 1e-9)
    }

    @Test
    fun `latitude is clamped before it can diverge`() {
        val y = MercatorProjection.latToWorldY(89.9, 4.0)
        val yMax = MercatorProjection.latToWorldY(MercatorProjection.MAX_LATITUDE, 4.0)
        assertEquals(yMax, y, 1e-6)
    }
}
