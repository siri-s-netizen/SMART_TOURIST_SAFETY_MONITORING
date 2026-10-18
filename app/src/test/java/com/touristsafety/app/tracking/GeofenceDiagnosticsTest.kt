package com.touristsafety.app.tracking

import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.GeoFencePolicy
import com.touristsafety.app.geofencing.ZoneStatus
import com.touristsafety.app.testing.TestGeo
import com.touristsafety.app.testing.testReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics screen must never invent anything.
 *
 * The review asked for on-screen diagnostics showing the real permission state, provider state, fix
 * age, accuracy, coordinates and the geofence verdict. The riskiest part of that request is that a
 * diagnostic view can quietly become a *second* opinion — recomputing the geometry, or filling in a
 * plausible-looking coordinate when there is no fix. These tests pin the opposite: every value comes
 * straight from the snapshot the engine produced, and the empty states say what is actually missing
 * instead of showing a number.
 */
class GeofenceDiagnosticsTest {

    private val policy = GeoFencePolicy()

    private fun status(
        id: Long = 1,
        name: String = "Training area",
        type: ZoneType = ZoneType.RESTRICTED,
        containment: ZoneContainment,
        inside: Boolean,
        status: SafetyStatus,
        distance: Double = 42.0,
    ) = ZoneStatus(
        zoneId = id,
        zoneName = name,
        zoneType = type,
        containment = containment,
        inside = inside,
        status = status,
        distanceToBoundaryMeters = distance,
    )

    @Test
    fun `before tracking is started it says so instead of reporting an all-clear`() {
        val diagnostics = geofenceDiagnosticsFor(TrackingSnapshot(), policy)

        assertNotNull(diagnostics.note)
        assertTrue(diagnostics.note!!.contains("has not been started"))
        assertEquals(0, diagnostics.monitoredZoneCount)
        assertTrue(diagnostics.zones.isEmpty())
        assertNull(diagnostics.selected)
    }

    @Test
    fun `a busy pipeline with no fix yet is reported as waiting, not as safe-inside-nothing`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(isTracking = true, trackingRequested = true, problem = TrackingProblem.NO_FIX_YET),
            policy,
        )

        assertTrue(diagnostics.note!!.contains("No GPS fix yet"))
        assertTrue(diagnostics.zones.isEmpty())
    }

    @Test
    fun `tracking a fix and no enabled zones explains exactly that`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                lastReading = testReading(TestGeo.squareCenter, accuracyMeters = 6f),
                activeZoneCount = 0,
            ),
            policy,
        )

        assertTrue(diagnostics.note!!.contains("No enabled zones"))
    }

    @Test
    fun `zones that were read but could not be tested are distinguished from having no zones`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                lastReading = testReading(TestGeo.squareCenter, accuracyMeters = 6f),
                // The engine skipped them (too few vertices), so none produced a status even though the
                // pipeline did read some zones from the database.
                activeZoneCount = 2,
                zoneStatuses = emptyList(),
            ),
            policy,
        )

        assertTrue(diagnostics.note!!.contains("none was testable"))
    }

    @Test
    fun `an inside verdict reports the exit margin that was in force`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                lastReading = testReading(TestGeo.squareCenter, accuracyMeters = 6f),
                activeZoneCount = 1,
                overallStatus = SafetyStatus.INSIDE_RESTRICTED,
                zoneStatuses = listOf(
                    status(
                        containment = ZoneContainment.INSIDE,
                        inside = true,
                        status = SafetyStatus.INSIDE_RESTRICTED,
                    ),
                ),
            ),
            policy,
        )

        val zone = diagnostics.zones.single()
        assertEquals(GeoFencePolicy.DEFAULT_EXIT_TOLERANCE_METERS, zone.toleranceMeters, 0.0)
        assertEquals("exit margin (already inside)", zone.toleranceRule)
        assertEquals(6f, zone.accuracyMeters!!.toFloat())
        assertEquals(GeoFencePolicy.DEFAULT_MAX_USABLE_ACCURACY_METERS, zone.maxUsableAccuracyMeters, 0.0)
        assertNull("nothing is missing, so there is nothing to explain", diagnostics.note)
        assertEquals(zone, diagnostics.selected)
    }

    @Test
    fun `an outside verdict reports the entry margin that was in force`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                lastReading = testReading(TestGeo.squareCenter, accuracyMeters = 6f),
                activeZoneCount = 1,
                zoneStatuses = listOf(
                    status(
                        containment = ZoneContainment.OUTSIDE,
                        inside = false,
                        status = SafetyStatus.SAFE,
                    ),
                ),
            ),
            policy,
        )

        val zone = diagnostics.zones.single()
        assertEquals(GeoFencePolicy.DEFAULT_ENTRY_TOLERANCE_METERS, zone.toleranceMeters, 0.0)
        assertEquals("entry margin", zone.toleranceRule)
    }

    @Test
    fun `the selected zone is the one that produced the banner`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                lastReading = testReading(TestGeo.squareCenter, accuracyMeters = 4f),
                activeZoneCount = 2,
                overallStatus = SafetyStatus.INSIDE_DANGEROUS,
                zoneStatuses = listOf(
                    status(
                        id = 1,
                        name = "Safe parking",
                        type = ZoneType.SAFE,
                        containment = ZoneContainment.INSIDE,
                        inside = true,
                        status = SafetyStatus.INSIDE_SAFE_ZONE,
                    ),
                    status(
                        id = 2,
                        name = "Cliff edge",
                        type = ZoneType.DANGEROUS,
                        containment = ZoneContainment.INSIDE,
                        inside = true,
                        status = SafetyStatus.INSIDE_DANGEROUS,
                    ),
                ),
            ),
            policy,
        )

        assertEquals("Cliff edge", diagnostics.selected!!.zoneName)
        assertEquals(2, diagnostics.zones.size)
    }

    @Test
    fun `every number is copied from the engine, never recomputed`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                lastReading = testReading(TestGeo.squareCenter, accuracyMeters = 7f),
                activeZoneCount = 1,
                overallStatus = SafetyStatus.NEAR_BOUNDARY,
                zoneStatuses = listOf(
                    status(
                        containment = ZoneContainment.BOUNDARY,
                        inside = true,
                        status = SafetyStatus.NEAR_BOUNDARY,
                        distance = 2.5,
                    ),
                ),
            ),
            policy,
        )

        val zone = diagnostics.zones.single()
        assertEquals(ZoneContainment.BOUNDARY, zone.containment)
        assertTrue(zone.inside)
        assertEquals(SafetyStatus.NEAR_BOUNDARY, zone.status)
        assertEquals(2.5, zone.distanceToBoundaryMeters, 0.0)
        assertEquals(SafetyStatus.NEAR_BOUNDARY, diagnostics.overallStatus)
    }

    @Test
    fun `with no fix the accuracy is reported as unknown rather than a default`() {
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                activeZoneCount = 1,
                zoneStatuses = listOf(
                    status(
                        containment = ZoneContainment.UNCERTAIN,
                        inside = false,
                        status = SafetyStatus.NEAR_BOUNDARY,
                    ),
                ),
            ),
            policy,
        )

        // A status row can only exist after a fix, so this is a defensive case: if it ever happened the
        // card must say "unknown", never a number it made up.
        assertNull(diagnostics.zones.single().accuracyMeters)
    }

    @Test
    fun `the monitored-zone count is the pipeline's own count, not the number of statuses`() {
        // If the engine skipped an untestable zone, the two numbers legitimately differ. The card must
        // show the pipeline's count, because that is what "enabled zones tested" means.
        val diagnostics = geofenceDiagnosticsFor(
            TrackingSnapshot(
                isTracking = true,
                trackingRequested = true,
                lastReading = testReading(TestGeo.squareCenter, accuracyMeters = 5f),
                activeZoneCount = 3,
                zoneStatuses = listOf(
                    status(
                        containment = ZoneContainment.OUTSIDE,
                        inside = false,
                        status = SafetyStatus.SAFE,
                    ),
                ),
            ),
            policy,
        )

        assertEquals(3, diagnostics.monitoredZoneCount)
        assertEquals(1, diagnostics.zones.size)
    }

    @Test
    fun `the snapshot remembers the request even when the start was refused`() {
        // The recovery rule depends on this: a refused start must not erase the user's intent, or there
        // would be nothing to recover. A stopped pipeline, on the other hand, must forget it.
        val refused = TrackingSnapshot(
            isTracking = false,
            trackingRequested = true,
            problem = TrackingProblem.LOCATION_DISABLED,
        )
        val stopped = TrackingSnapshot(isTracking = false, trackingRequested = false)

        assertTrue(refused.trackingRequested)
        assertEquals(TrackingProblem.LOCATION_DISABLED, refused.problem)
        assertEquals(false, stopped.trackingRequested)
    }
}
