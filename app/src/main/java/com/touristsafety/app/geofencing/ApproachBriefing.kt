package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.motion.MovementState

/**
 * The "what do I know about this boundary before I reach it" briefing shown while a tourist
 * approaches a restricted or dangerous zone.
 *
 * @property distanceMeters distance to the zone outline, or `0` when the tourist is inside it.
 */
data class ApproachBriefing(
    val zone: GeoFenceZone,
    val distanceMeters: Double,
    val isInsideNow: Boolean,
    val history: ZoneHistory,
    val risk: ApproachRisk,
)

/**
 * Chooses which boundary to brief the tourist on, and assembles the briefing.
 *
 * Pure and Android-free: real zones, real recorded crossings, the live movement state, and the local
 * hour go in; a briefing or `null` comes out. That makes the whole "which zone, and why that score"
 * decision testable without a device.
 */
object ApproachBriefingCalculator {

    /**
     * Only zones the tourist is supposed to keep **out** of are briefed. Warning someone as they
     * approach a *safe* zone would be noise, and would train them to ignore the card.
     */
    fun brief(
        zones: List<GeoFenceZone>,
        here: GeoPoint?,
        alerts: List<GeoFenceAlert>,
        movement: MovementState,
        localHour: Int,
        nowMillis: Long,
    ): ApproachBriefing? {
        if (here == null) return null

        var best: ApproachBriefing? = null

        for (zone in zones) {
            if (!zone.enabled) continue
            if (!zone.type.raisesWarningOnEntry) continue
            if (!zone.hasEnoughVertices) continue

            val inside = PolygonGeometry.containsRayCasting(here, zone.vertices)
            val distance = if (inside) {
                0.0
            } else {
                PolygonGeometry.distanceToBoundaryMeters(here, zone.vertices)
            }

            if (!inside && distance > ApproachRiskModel.APPROACH_RADIUS_METERS) continue

            val history = ZoneHistory.from(alerts, zone.id, nowMillis)
            val risk = ApproachRiskModel.assess(
                zoneType = zone.type,
                history = history,
                input = ApproachRiskModel.Input(
                    distanceMeters = distance,
                    isInsideNow = inside,
                    localHour = localHour,
                    activity = movement.activity,
                    fallProbability = if (movement.modelAvailable) movement.fallProbability else null,
                ),
            )

            val candidate = ApproachBriefing(
                zone = zone,
                distanceMeters = distance,
                isInsideNow = inside,
                history = history,
                risk = risk,
            )

            best = when {
                best == null -> candidate
                // Highest risk first; ties go to the closer boundary, then to the lower id so the
                // card cannot flicker between two identical zones on consecutive GPS fixes.
                candidate.risk.score > best.risk.score -> candidate
                candidate.risk.score < best.risk.score -> best
                candidate.distanceMeters < best.distanceMeters -> candidate
                candidate.distanceMeters > best.distanceMeters -> best
                candidate.zone.id < best.zone.id -> candidate
                else -> best
            }
        }

        return best
    }
}
