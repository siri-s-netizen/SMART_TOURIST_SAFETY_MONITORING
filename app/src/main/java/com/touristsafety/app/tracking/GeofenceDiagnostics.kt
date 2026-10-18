package com.touristsafety.app.tracking

import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.GeoFencePolicy
import com.touristsafety.app.geofencing.ZoneStatus

/**
 * One zone's part of the geofence evaluation, with the numbers that produced it.
 *
 * [toleranceMeters] is the margin the engine actually applied to *this* zone on *this* fix. It is not
 * a constant: the engine widens the margin once the tourist is already inside, so a boundary cannot
 * ping-pong ENTRY/EXIT. Showing which of the two was in force is what makes a "near the boundary"
 * verdict explainable instead of mysterious.
 */
data class GeofenceDiagnostic(
    val zoneId: Long,
    val zoneName: String,
    val zoneType: ZoneType,
    val containment: ZoneContainment,
    val inside: Boolean,
    val status: SafetyStatus,
    val distanceToBoundaryMeters: Double,
    val toleranceMeters: Double,
    val accuracyMeters: Double?,
    val maxUsableAccuracyMeters: Double,
) {
    /** The margin in force, named so the screen can say which rule it was. */
    val toleranceRule: String
        get() = if (inside) "exit margin (already inside)" else "entry margin"
}

/**
 * The complete, honest geofence picture for the most recent fix.
 *
 * This exists because the review found the decision itself was invisible: the app could show
 * "near the boundary" without ever exposing which zone, what the distance to the edge was, or which
 * tolerance and accuracy ceiling were in play. Every field here is copied from the real
 * [ZoneStatus] the engine returned — nothing is recomputed, guessed or invented, so this view can
 * never disagree with the alert the engine raised.
 */
data class GeofenceDiagnostics(
    /** Enabled zones the engine tested on the last fix. */
    val monitoredZoneCount: Int,
    val zones: List<GeofenceDiagnostic>,
    /** The zone that decided [overallStatus], i.e. the one the banner is about. */
    val selected: GeofenceDiagnostic?,
    val overallStatus: SafetyStatus,
    /** A plain statement of what is (or is not) being monitored, or null when zones are being tested. */
    val note: String?,
)

/**
 * Builds the diagnostic view from the last snapshot.
 *
 * `zoneStatuses` is empty in three different situations — tracking never ran, tracking ran but no zone
 * is enabled, or every enabled zone was filtered out — and they need different explanations, which is
 * what [GeofenceDiagnostics.note] is for.
 */
fun geofenceDiagnosticsFor(
    snapshot: TrackingSnapshot,
    policy: GeoFencePolicy,
): GeofenceDiagnostics {
    val accuracy = snapshot.lastReading?.accuracyMeters?.let {
        if (it.isFinite() && it > 0f) it.toDouble() else null
    }

    val zones = snapshot.zoneStatuses.map { status ->
        GeofenceDiagnostic(
            zoneId = status.zoneId,
            zoneName = status.zoneName,
            zoneType = status.zoneType,
            containment = status.containment,
            inside = status.inside,
            status = status.status,
            distanceToBoundaryMeters = status.distanceToBoundaryMeters,
            toleranceMeters = if (status.inside) {
                policy.exitToleranceMeters
            } else {
                policy.entryToleranceMeters
            },
            accuracyMeters = accuracy,
            maxUsableAccuracyMeters = policy.maxUsableAccuracyMeters,
        )
    }

    // The banner is driven by the worst verdict, so the "selected" zone must be the one that produced
    // it. Ties resolve to the first, which is the same order the engine evaluated them in.
    val selected = zones.firstOrNull { it.status == snapshot.overallStatus } ?: zones.firstOrNull()

    val note = when {
        !snapshot.trackingRequested && !snapshot.isTracking ->
            "Tracking has not been started, so no polygon is being evaluated."

        snapshot.lastReading == null ->
            "No GPS fix yet, so no polygon has been tested."

        zones.isEmpty() && snapshot.activeZoneCount == 0 ->
            "No enabled zones. Draw one on the Manage Geo-Fences screen and it will be evaluated on " +
                "the very next fix."

        zones.isEmpty() ->
            "Zones were read from the database but none was testable — each one needs at least three " +
                "valid vertices."

        else -> null
    }

    return GeofenceDiagnostics(
        monitoredZoneCount = snapshot.activeZoneCount,
        zones = zones,
        selected = selected,
        overallStatus = snapshot.overallStatus,
        note = note,
    )
}
