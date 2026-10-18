package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zone wire contract, decided on the plain JVM.
 *
 * Two promises are pinned here. First, coordinates are carried as *named* `latitude` and `longitude`
 * fields and the order survives a round trip — a mirrored polygon is the classic GeoJSON bug and it
 * would put a tourist's warning in the wrong place. Second, a malformed zone is dropped rather than
 * guessed at, with one deliberate exception: an unknown `zoneType` becomes RESTRICTED, because the
 * dangerous failure is telling a tourist a closed-off area is safe.
 */
class AuthorityZoneApiTest {

    private val square = listOf(
        GeoPoint(12.9716, 77.5946),
        GeoPoint(12.9734, 77.5946),
        GeoPoint(12.9734, 77.5974),
        GeoPoint(12.9716, 77.5974),
    )

    @Test
    fun `a published zone keeps its name, type, status and revision`() {
        val text = """
            {"zones":[{"zoneId":7,"name":"Old quarry","description":"Steep sides.",
            "zoneType":"RESTRICTED","status":"ACTIVE","revision":3,"vertexCount":4,
            "updatedAtMillis":1700000000000,
            "vertices":[{"latitude":12.9716,"longitude":77.5946},
                        {"latitude":12.9734,"longitude":77.5946},
                        {"latitude":12.9734,"longitude":77.5974}]}],"serverTimeMillis":1}
        """.trimIndent()

        val zones = AuthorityApi.parseZones(text)

        assertEquals(1, zones.size)
        val zone = zones.single()
        assertEquals(7, zone.zoneId)
        assertEquals("Old quarry", zone.name)
        assertEquals(ZoneType.RESTRICTED, zone.zoneType)
        assertEquals(ZoneStatus.ACTIVE, zone.status)
        assertEquals(3, zone.revision)
        assertEquals(1_700_000_000_000L, zone.updatedAtMillis)
        assertTrue(zone.isActive)
        assertTrue(zone.warnsWhenInside)
    }

    @Test
    fun `latitude and longitude are not swapped`() {
        val text = """
            {"zones":[{"zoneId":1,"name":"Z","zoneType":"DANGEROUS","status":"ACTIVE","revision":1,
            "vertices":[{"latitude":12.9716,"longitude":77.5946},
                        {"latitude":12.9734,"longitude":77.5946},
                        {"latitude":12.9734,"longitude":77.5974}]}]}
        """.trimIndent()

        val first = AuthorityApi.parseZones(text).single().vertices.first()
        assertEquals(12.9716, first.latitude, 1e-9)
        assertEquals(77.5946, first.longitude, 1e-9)
    }

    @Test
    fun `a zone with fewer than three vertices is dropped, not rendered`() {
        val text = """
            {"zones":[{"zoneId":1,"name":"Degenerate","zoneType":"RESTRICTED","status":"ACTIVE",
            "vertices":[{"latitude":12.97,"longitude":77.59},{"latitude":12.98,"longitude":77.60}]}]}
        """.trimIndent()
        assertTrue(AuthorityApi.parseZones(text).isEmpty())
    }

    @Test
    fun `a row without a zone id is dropped`() {
        val text = """
            {"zones":[{"name":"No id","zoneType":"RESTRICTED","status":"ACTIVE",
            "vertices":[{"latitude":12.97,"longitude":77.59},{"latitude":12.98,"longitude":77.60},
                        {"latitude":12.99,"longitude":77.61}]}]}
        """.trimIndent()
        assertTrue(AuthorityApi.parseZones(text).isEmpty())
    }

    @Test
    fun `an unknown zone type degrades to the safe-to-warn direction, not to safe`() {
        val text = """
            {"zones":[{"zoneId":1,"name":"Mystery","zoneType":"FORBIDDEN_SECTOR","status":"ACTIVE",
            "vertices":[{"latitude":12.97,"longitude":77.59},{"latitude":12.98,"longitude":77.60},
                        {"latitude":12.99,"longitude":77.61}]}]}
        """.trimIndent()
        val zone = AuthorityApi.parseZones(text).single()
        assertEquals(ZoneType.RESTRICTED, zone.zoneType)
        assertTrue(zone.warnsWhenInside)
    }

    @Test
    fun `a safe zone does not warn when inside`() {
        val text = """
            {"zones":[{"zoneId":1,"name":"Rest stop","zoneType":"SAFE","status":"ACTIVE",
            "vertices":[{"latitude":12.97,"longitude":77.59},{"latitude":12.98,"longitude":77.60},
                        {"latitude":12.99,"longitude":77.61}]}]}
        """.trimIndent()
        val zone = AuthorityApi.parseZones(text).single()
        assertEquals(ZoneType.SAFE, zone.zoneType)
        assertTrue(!zone.warnsWhenInside)
    }

    @Test
    fun `a single-zone answer is read from its zone object`() {
        val text = """
            {"zone":{"zoneId":9,"name":"Cliff","zoneType":"DANGEROUS","status":"INACTIVE","revision":2,
            "vertices":[{"latitude":12.97,"longitude":77.59},{"latitude":12.98,"longitude":77.60},
                        {"latitude":12.99,"longitude":77.61}]}}
        """.trimIndent()
        val zone = AuthorityApi.parseZone(text)
        assertNotNull(zone)
        assertEquals(9, zone!!.zoneId)
        assertEquals(ZoneStatus.INACTIVE, zone.status)
        assertTrue(!zone.isActive)
    }

    @Test
    fun `a create body survives a parse round trip with its ring order intact`() {
        val body = AuthorityApi.zoneBody(
            name = "Old quarry",
            description = "Steep sides.",
            zoneType = ZoneType.RESTRICTED,
            vertices = square,
        )

        // The wire shape the backend validates: named latitude/longitude, ring in the order given.
        assertTrue(body.contains("\"latitude\""))
        assertTrue(body.contains("\"longitude\""))

        val zones = AuthorityApi.parseZones("{\"zones\":[${body.replaceFirst("{", "{\"zoneId\":1,\"status\":\"ACTIVE\",\"revision\":1,")}]}")
        assertEquals(4, zones.single().vertices.size)
        assertEquals(square, zones.single().vertices)
    }

    @Test
    fun `a create body can carry an explicit draft status`() {
        val body = AuthorityApi.zoneBody("Draft", "", ZoneType.RESTRICTED, square, status = ZoneStatus.INACTIVE)
        assertTrue(body.contains("\"status\":\"INACTIVE\""))
    }

    @Test
    fun `a status body names the target status`() {
        assertEquals("{\"status\":\"ARCHIVED\"}", AuthorityApi.zoneStatusBody(ZoneStatus.ARCHIVED))
    }

    @Test
    fun `the endpoints point at the authority zone routes, and mutating paths are not the tourist route`() {
        assertEquals("/api/v1/zones", AuthorityEndpoints.ZONES)
        assertEquals("/api/v1/authority/zones", AuthorityEndpoints.AUTHORITY_ZONES)
        assertEquals("/api/v1/authority/zones/4/status", AuthorityEndpoints.authorityZoneStatusPath(4))
        assertEquals("/api/v1/authority/zones/4", AuthorityEndpoints.authorityZonePath(4))
        // The read route a tourist may call is a different path from every write route.
        assertTrue(AuthorityEndpoints.ZONES != AuthorityEndpoints.AUTHORITY_ZONES)
        assertNull(
            listOf(AuthorityEndpoints.authorityZoneStatusPath(4), AuthorityEndpoints.authorityZonePath(4))
                .firstOrNull { it == AuthorityEndpoints.ZONES },
        )
    }
}
