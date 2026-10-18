package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.motion.ActivityKind
import com.touristsafety.app.motion.MovementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which boundary the tourist is briefed about, and when nothing is worth interrupting them for.
 *
 * This is the decision that makes the feature usable: a card that appears for the *wrong* zone, or
 * for a safe zone, teaches the tourist to ignore it.
 */
class ApproachBriefingTest {

    private val home = GeoPoint(12.9716, 77.5946)
    private val now = 1_700_000_000_000L

    /** A ~110 m square centred on [centre]; half-size 0.0005 deg is about 55 m. */
    private fun square(centre: GeoPoint, halfDegrees: Double = 0.0005): List<GeoPoint> = listOf(
        GeoPoint(centre.latitude - halfDegrees, centre.longitude - halfDegrees),
        GeoPoint(centre.latitude - halfDegrees, centre.longitude + halfDegrees),
        GeoPoint(centre.latitude + halfDegrees, centre.longitude + halfDegrees),
        GeoPoint(centre.latitude + halfDegrees, centre.longitude - halfDegrees),
    )

    private fun zone(
        id: Long,
        type: ZoneType = ZoneType.DANGEROUS,
        centre: GeoPoint = home,
        enabled: Boolean = true,
    ) = GeoFenceZone(
        id = id,
        name = "Zone $id",
        type = type,
        vertices = square(centre),
        enabled = enabled,
    )

    /** ~1 km north-east of home. */
    private fun farAway(): GeoPoint = GeoPoint(home.latitude + 0.009, home.longitude + 0.009)

    private fun brief(
        zones: List<GeoFenceZone>,
        here: GeoPoint? = home,
        movement: MovementState = MovementState(),
        hour: Int = 12,
    ) = ApproachBriefingCalculator.brief(
        zones = zones,
        here = here,
        alerts = emptyList(),
        movement = movement,
        localHour = hour,
        nowMillis = now,
    )

    @Test
    fun `no position means no briefing`() {
        assertNull(brief(zones = listOf(zone(1)), here = null))
    }

    @Test
    fun `a zone far beyond the radius is not briefed`() {
        assertNull(brief(zones = listOf(zone(1, centre = farAway()))))
    }

    @Test
    fun `a safe zone is never briefed, however close it is`() {
        assertNull(brief(zones = listOf(zone(1, type = ZoneType.SAFE))))
    }

    @Test
    fun `a disabled zone is ignored`() {
        assertNull(brief(zones = listOf(zone(1, enabled = false))))
    }

    @Test
    fun `being inside the zone is reported as zero distance`() {
        val briefing = brief(zones = listOf(zone(7)))
        assertEquals(7L, briefing?.zone?.id)
        assertEquals(true, briefing?.isInsideNow)
        assertEquals(0.0, briefing?.distanceMeters ?: -1.0, 1e-9)
    }

    @Test
    fun `standing just outside gives a real distance and full proximity credit`() {
        // ~110 m north of the centre: outside the 55 m half-size, so around 55 m from the boundary.
        val justOutside = GeoPoint(home.latitude + 0.0015, home.longitude)
        val briefing = brief(zones = listOf(zone(1)), here = justOutside)

        val distance = briefing?.distanceMeters ?: -1.0
        assertTrue("expected a positive distance, got $distance", distance > 20.0 && distance < 120.0)
        assertEquals(false, briefing?.isInsideNow)
        assertTrue(briefing!!.risk.factors.first { it.label == "Distance" }.points > 0)
    }

    @Test
    fun `when two boundaries are near, the higher risk one is briefed`() {
        val safeish = zone(1, type = ZoneType.RESTRICTED)
        val worse = zone(2, type = ZoneType.DANGEROUS)

        val briefing = brief(zones = listOf(safeish, worse))
        assertEquals("the dangerous zone must win", 2L, briefing?.zone?.id)
    }

    @Test
    fun `equal-risk zones resolve deterministically to the closer boundary`() {
        val near = zone(5, type = ZoneType.DANGEROUS)
        val further = zone(6, type = ZoneType.DANGEROUS, centre = GeoPoint(home.latitude + 0.0012, home.longitude))

        // Both are dangerous, so the only difference is distance; run it repeatedly to be sure the
        // card cannot flicker between them on consecutive fixes.
        repeat(5) {
            assertEquals(5L, brief(zones = listOf(further, near))?.zone?.id)
        }
    }

    @Test
    fun `the live movement state feeds the score`() {
        val walkingFallRisk = MovementState(
            modelAvailable = true,
            activity = ActivityKind.WALKING,
            fallProbability = 0.9f,
        )

        val calm = brief(zones = listOf(zone(1)), movement = MovementState(modelAvailable = true))
        val alarming = brief(zones = listOf(zone(1)), movement = walkingFallRisk)

        assertTrue(
            "a walking tourist with an impact signature must score higher",
            (alarming?.risk?.score ?: 0) > (calm?.risk?.score ?: 0),
        )
    }

    @Test
    fun `darkness raises the score for the same position`() {
        val noon = brief(zones = listOf(zone(1)), hour = 12)
        val night = brief(zones = listOf(zone(1)), hour = 23)
        assertTrue((night?.risk?.score ?: 0) > (noon?.risk?.score ?: 0))
    }
}
