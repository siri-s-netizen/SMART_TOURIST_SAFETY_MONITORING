package com.touristsafety.app.ui.components

import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.ZoneStatus

/**
 * One-line, human-readable explanation of what the engine decided about a single zone.
 *
 * Kept next to the other formatting helpers so the same wording is used on the dashboard, the
 * tracking screen and the simulation log.
 */
fun ZoneStatus.description(): String {
    val state = when (containment) {
        ZoneContainment.INSIDE -> when (zoneType) {
            ZoneType.SAFE -> "inside (safe zone)"
            ZoneType.RESTRICTED -> "INSIDE restricted area"
            ZoneType.DANGEROUS -> "INSIDE dangerous area"
        }

        ZoneContainment.OUTSIDE -> "outside"
        ZoneContainment.BOUNDARY -> "near the boundary"
        ZoneContainment.UNCERTAIN -> "uncertain — accuracy too poor"
    }

    return if (distanceToBoundaryMeters.isFinite()) {
        "$state · ${distanceToBoundaryMeters.formatDistanceFromBoundary()}"
    } else {
        state
    }
}
