package com.touristsafety.app.data.database

import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.testing.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vertex codec is what makes an ordered polygon survive a database round trip. */
class PolygonCodecTest {

    @Test
    fun `encode then decode returns the identical ordered list`() {
        val vertices = TestGeo.squareVertices
        val decoded = PolygonCodec.decode(PolygonCodec.encode(vertices))

        assertEquals(vertices.size, decoded.size)
        vertices.forEachIndexed { index, expected ->
            assertEquals(expected.latitude, decoded[index].latitude, 0.0)
            assertEquals(expected.longitude, decoded[index].longitude, 0.0)
        }
    }

    @Test
    fun `order is preserved exactly`() {
        val vertices = TestGeo.concaveL()
        val decoded = PolygonCodec.decode(PolygonCodec.encode(vertices))
        assertEquals(vertices, decoded)
    }

    @Test
    fun `full double precision survives the round trip`() {
        val precise = GeoPoint(48.85837012345678, 2.29448109876543)
        val decoded = PolygonCodec.decode(PolygonCodec.encode(listOf(precise)))
        assertEquals(precise.latitude, decoded.single().latitude, 0.0)
        assertEquals(precise.longitude, decoded.single().longitude, 0.0)
    }

    @Test
    fun `negative coordinates survive the round trip`() {
        val vertices = listOf(
            GeoPoint(-33.8688, 151.2093),
            GeoPoint(-33.8700, 151.2100),
            GeoPoint(-33.8700, 151.2090),
        )
        assertEquals(vertices, PolygonCodec.decode(PolygonCodec.encode(vertices)))
    }

    @Test
    fun `encoding is locale independent`() {
        // A comma-decimal locale would corrupt the separator if String.format were used.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val vertices = TestGeo.squareVertices
            val encoded = PolygonCodec.encode(vertices)
            assertTrue("must use '.' as decimal point", encoded.contains("48.8"))
            assertEquals(vertices, PolygonCodec.decode(encoded))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `empty and blank input decodes to an empty list`() {
        assertTrue(PolygonCodec.decode(null).isEmpty())
        assertTrue(PolygonCodec.decode("").isEmpty())
        assertTrue(PolygonCodec.decode("   ").isEmpty())
        assertTrue(PolygonCodec.encode(emptyList()).isEmpty())
    }

    @Test
    fun `malformed tokens are skipped rather than throwing`() {
        val mixed = "48.8584,2.2945;broken;48.86,2.30;,;48.87"
        val decoded = PolygonCodec.decode(mixed)

        assertEquals(2, decoded.size)
        assertEquals(48.8584, decoded[0].latitude, 1e-9)
        assertEquals(48.86, decoded[1].latitude, 1e-9)
    }

    @Test
    fun `out of range coordinates are dropped`() {
        val decoded = PolygonCodec.decode("91.0,0.0;48.8584,2.2945;0.0,181.0")
        assertEquals(1, decoded.size)
        assertEquals(48.8584, decoded.single().latitude, 1e-9)
    }

    @Test
    fun `trailing separator does not create a phantom vertex`() {
        val decoded = PolygonCodec.decode("48.8584,2.2945;48.86,2.30;")
        assertEquals(2, decoded.size)
    }
}
