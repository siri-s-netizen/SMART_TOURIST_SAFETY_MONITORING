package com.touristsafety.app.ui.components

import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.ZoneStatus
import com.touristsafety.app.motion.FutureRiskBand
import com.touristsafety.app.motion.FutureRiskDataQuality
import com.touristsafety.app.motion.FutureRiskState
import com.touristsafety.app.tracking.TrackingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD 1's wording and states, as plain text assertions.
 *
 * The point of these tests is the property the whole feature exists for: the geographical verdict is
 * decided from the position and the zones alone, and it stays a red "inside" even when the fall-risk
 * model says Low. No model object is passed to [GeoSafetyCardModel], which is what makes that
 * structural rather than a promise — but the assertion below pins the *behaviour* so a future edit
 * cannot quietly reintroduce the coupling.
 */
class GeoSafetyCardModelTest {

    private val now = 1_700_000_000_000L
    private val here = GeoPoint(12.9716, 77.5946)

    private fun snapshot(
        zones: List<ZoneStatus>,
        isTracking: Boolean = true,
        reading: GeoReading? = GeoReading(here, 5f, now, LocationSource.GPS),
        overall: SafetyStatus = SafetyStatus.SAFE,
    ) = TrackingSnapshot(
        isTracking = isTracking,
        trackingRequested = isTracking,
        lastReading = reading,
        zoneStatuses = zones,
        overallStatus = overall,
        activeZoneCount = zones.size,
    )

    private fun zone(
        id: Long = 1,
        name: String = "Old quarry",
        type: ZoneType = ZoneType.RESTRICTED,
        containment: ZoneContainment = ZoneContainment.INSIDE,
        distance: Double = 42.0,
    ) = ZoneStatus(
        zoneId = id,
        zoneName = name,
        zoneType = type,
        containment = containment,
        inside = containment == ZoneContainment.INSIDE,
        status = when {
            containment == ZoneContainment.INSIDE && type == ZoneType.DANGEROUS -> SafetyStatus.INSIDE_DANGEROUS
            containment == ZoneContainment.INSIDE && type == ZoneType.RESTRICTED -> SafetyStatus.INSIDE_RESTRICTED
            containment == ZoneContainment.BOUNDARY -> SafetyStatus.NEAR_BOUNDARY
            containment == ZoneContainment.UNCERTAIN -> SafetyStatus.UNCERTAIN
            else -> SafetyStatus.SAFE
        },
        distanceToBoundaryMeters = distance,
    )

    private fun build(snapshot: TrackingSnapshot, maxUsable: Double = 100.0) =
        GeoSafetyCardModel.build(snapshot, maxUsable, now)

    @Test
    fun `a decided fix inside a restricted polygon is a red inside-zone verdict`() {
        val content = build(snapshot(listOf(zone()), overall = SafetyStatus.INSIDE_RESTRICTED))

        assertEquals(GeoSafetyState.INSIDE_RESTRICTED_ZONE, content.state)
        assertTrue(content.isInsideRestricted)
        assertEquals(GeoSafetyTone.DANGER, content.headlineTone)
        assertEquals(GeoSafetyCardModel.INSIDE_WARNING, content.warning)
        assertTrue(content.headline.contains("Old quarry"))
    }

    @Test
    fun `the inside-zone card names the zone, the distance to its boundary, accuracy and time`() {
        val content = build(
            snapshot(listOf(zone(distance = 37.5)), overall = SafetyStatus.INSIDE_RESTRICTED),
        )
        val byLabel = content.rows.associate { it.label to it.value }
        assertEquals("Old quarry", byLabel["Zone"])
        assertEquals("Restricted Area", byLabel["Zone type"])
        assertEquals("37.5 m", byLabel["Distance to boundary"])
        assertEquals("±5 m", byLabel["GPS accuracy"])
        assertNotNull(byLabel["Position time"])
    }

    @Test
    fun `a low fall-risk prediction does not suppress or soften the inside-zone warning`() {
        // The exact failure the task calls out: a Low model score next to a restricted-zone warning.
        val zoneInside = snapshot(listOf(zone()), overall = SafetyStatus.INSIDE_RESTRICTED)

        val geo = build(zoneInside)
        val lowRisk = FutureRiskState(
            isMonitoring = true,
            modelAvailable = true,
            modelVersion = "future-risk-unimib-v1",
            band = FutureRiskBand.LOW,
            probability = 0.052f,
            moderateThreshold = 0.069201f,
            highThreshold = 0.314046f,
            evaluatedAtMillis = now,
            dataQuality = FutureRiskDataQuality(50, 50, true, null),
        )
        val riskContent = FutureRiskCardModel.build(lowRisk, now)

        // The model genuinely says "no sign of a fall"...
        assertTrue(riskContent.headline.contains("No sign of an imminent hard fall"))
        assertEquals(FutureRiskBand.LOW, lowRisk.band)
        // ...and the geographical card is still an unqualified red inside-zone verdict.
        assertEquals(GeoSafetyState.INSIDE_RESTRICTED_ZONE, geo.state)
        assertEquals(GeoSafetyCardModel.INSIDE_WARNING, geo.warning)
    }

    @Test
    fun `a dangerous zone inside is reported as dangerous`() {
        val content = build(
            snapshot(
                listOf(zone(type = ZoneType.DANGEROUS, name = "Blasting zone")),
                overall = SafetyStatus.INSIDE_DANGEROUS,
            ),
        )
        assertEquals(GeoSafetyState.INSIDE_RESTRICTED_ZONE, content.state)
        assertTrue(content.headline.contains("DANGEROUS"))
        assertTrue(content.headline.contains("Blasting zone"))
    }

    @Test
    fun `a decided fix outside every zone is outside, with no warning`() {
        val content = build(
            snapshot(listOf(zone(containment = ZoneContainment.OUTSIDE, distance = 210.0))),
        )
        assertEquals(GeoSafetyState.OUTSIDE_ZONES, content.state)
        assertNull(content.warning)
        assertFalse(content.isInsideRestricted)
        assertEquals(GeoSafetyTone.SAFE, content.headlineTone)
    }

    @Test
    fun `a fix on the boundary is an explicit uncertainty, never inside or outside`() {
        val content = build(
            snapshot(listOf(zone(containment = ZoneContainment.BOUNDARY, distance = 4.0))),
        )
        assertEquals(GeoSafetyState.NEAR_BOUNDARY_UNCERTAIN, content.state)
        assertNull(content.warning)
        assertTrue(content.notices.single().contains("Old quarry"))
    }

    @Test
    fun `a coarse fix is uncertainty, not a guess about which side of the boundary`() {
        val reading = GeoReading(here, 140f, now, LocationSource.GPS)
        val content = build(snapshot(listOf(zone()), reading = reading), maxUsable = 100.0)
        assertEquals(GeoSafetyState.NEAR_BOUNDARY_UNCERTAIN, content.state)
        assertTrue(content.notices.single().contains("140"))
    }

    @Test
    fun `no fix while tracking is checking, not safe and not inside`() {
        val content = build(snapshot(emptyList(), reading = null, overall = SafetyStatus.SAFE))
        assertEquals(GeoSafetyState.CHECKING_LOCATION, content.state)
        assertNull(content.warning)
    }

    @Test
    fun `no fix with tracking stopped is location unavailable, never a green safe`() {
        val content = build(
            snapshot(emptyList(), isTracking = false, reading = null, overall = SafetyStatus.SAFE),
        )
        assertEquals(GeoSafetyState.LOCATION_UNAVAILABLE, content.state)
        assertEquals(GeoSafetyTone.MUTED, content.headlineTone)
    }

    @Test
    fun `an old fix is stale and cannot be presented as where the tourist is now`() {
        val oldReading = GeoReading(here, 5f, now - 5 * 60_000L, LocationSource.GPS)
        val content = build(
            snapshot(listOf(zone()), reading = oldReading, overall = SafetyStatus.INSIDE_RESTRICTED),
        )
        assertEquals(GeoSafetyState.LOCATION_UNAVAILABLE, content.state)
        assertNull(content.warning)
    }

    @Test
    fun `the card carries the sentence that keeps it apart from the model cards`() {
        val content = build(snapshot(listOf(zone()), overall = SafetyStatus.INSIDE_RESTRICTED))
        assertTrue(content.distinction.contains("not a fall-risk score"))
    }

    @Test
    fun `multiple zones - the dangerous one wins the headline`() {
        val content = build(
            snapshot(
                listOf(
                    zone(id = 1, name = "Quarry", type = ZoneType.RESTRICTED),
                    zone(id = 2, name = "Cliff", type = ZoneType.DANGEROUS),
                ),
                overall = SafetyStatus.INSIDE_DANGEROUS,
            ),
        )
        assertTrue(content.headline.contains("Cliff"))
    }
}
