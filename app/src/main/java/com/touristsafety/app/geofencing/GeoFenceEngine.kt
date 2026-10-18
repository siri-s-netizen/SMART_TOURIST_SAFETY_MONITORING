package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.domain.model.ZoneType

/** Per-zone verdict for the most recent reading. */
data class ZoneStatus(
    val zoneId: Long,
    val zoneName: String,
    val zoneType: ZoneType,
    /** What the containment service said about *this* reading. */
    val containment: ZoneContainment,
    /** Last *decided* membership. Sticky: BOUNDARY/UNCERTAIN never flip it. */
    val inside: Boolean,
    val status: SafetyStatus,
    val distanceToBoundaryMeters: Double,
)

/** A genuine outside->inside (ENTRY) or inside->outside (EXIT) crossing. */
data class ZoneTransition(
    val zone: GeoFenceZone,
    val eventType: AlertEventType,
    val reading: GeoReading,
)

/** Everything the caller needs after feeding one reading to the engine. */
data class ZoneEvaluation(
    val reading: GeoReading,
    val statuses: List<ZoneStatus>,
    val transitions: List<ZoneTransition>,
    val overallStatus: SafetyStatus,
)

/**
 * The stateful geo-fence engine.
 *
 * Feed it readings in chronological order and it returns crossings. Key behaviours:
 *
 *  * **Transitions only.** An alert is emitted when membership *changes*, never on every fix, so a
 *    tourist who spends an hour inside a restricted area gets exactly one ENTRY.
 *  * **Sticky state.** BOUNDARY and UNCERTAIN verdicts are recorded for display but do not move the
 *    membership flag. A momentary bad fix cannot fabricate an alert.
 *  * **Independent zones.** State is tracked per zone id, so overlapping and nested zones are
 *    evaluated completely separately.
 *  * **Unknown means outside.** A zone with no prior state is treated as "outside", so launching
 *    the app while already standing inside a restricted area correctly raises an ENTRY.
 *
 * The class is deliberately free of Android and SQLite dependencies: it is a plain state machine,
 * which is why it is covered directly by JVM unit tests.
 */
class GeoFenceEngine(
    private val containmentService: PolygonContainmentService = RayCastingContainmentService,
) {
    private val insideState = mutableMapOf<Long, Boolean>()

    /** Forgets all remembered membership. Used when tracking stops or a simulation resets. */
    fun reset() {
        insideState.clear()
    }

    fun isInside(zoneId: Long): Boolean = insideState[zoneId] == true

    fun knownZoneIds(): Set<Long> = insideState.keys.toSet()

    fun evaluate(
        reading: GeoReading,
        zones: List<GeoFenceZone>,
        policy: GeoFencePolicy = GeoFencePolicy(),
    ): ZoneEvaluation {
        val accuracyTooPoor = RayCastingContainmentService.normalizeAccuracy(reading.accuracyMeters) >
            policy.maxUsableAccuracyMeters

        val statuses = ArrayList<ZoneStatus>(zones.size)
        val transitions = ArrayList<ZoneTransition>()

        for (zone in zones) {
            // Zones with too few vertices cannot be tested; skip rather than emit noise.
            if (!zone.enabled || zone.vertices.size < GeoFenceZone.MIN_VERTICES) continue

            val wasInside = insideState[zone.id] ?: false

            // Hysteresis: leaving needs a wider margin than entering.
            val toleranceMeters = if (wasInside) {
                policy.exitToleranceMeters
            } else {
                policy.entryToleranceMeters
            }

            val result = containmentService.locate(
                point = reading.point,
                polygon = zone.vertices,
                accuracyMeters = reading.accuracyMeters,
                boundaryToleranceMeters = toleranceMeters,
                maxUsableAccuracyMeters = policy.maxUsableAccuracyMeters,
            )

            val nowInside = when (result.containment) {
                ZoneContainment.INSIDE -> true
                ZoneContainment.OUTSIDE -> false
                // Sticky: never change membership on an undecided verdict.
                ZoneContainment.BOUNDARY, ZoneContainment.UNCERTAIN -> wasInside
            }

            insideState[zone.id] = nowInside

            statuses += ZoneStatus(
                zoneId = zone.id,
                zoneName = zone.name,
                zoneType = zone.type,
                containment = result.containment,
                inside = nowInside,
                status = statusFor(
                    zoneType = zone.type,
                    containment = result.containment,
                    inside = nowInside,
                    accuracyTooPoor = accuracyTooPoor,
                ),
                distanceToBoundaryMeters = result.distanceToBoundaryMeters,
            )

            if (nowInside != wasInside) {
                transitions += ZoneTransition(
                    zone = zone,
                    eventType = if (nowInside) AlertEventType.ENTRY else AlertEventType.EXIT,
                    reading = reading,
                )
            }
        }

        return ZoneEvaluation(
            reading = reading,
            statuses = statuses,
            transitions = transitions,
            overallStatus = SafetyStatus.worst(statuses.map { it.status }),
        )
    }

    private fun statusFor(
        zoneType: ZoneType,
        containment: ZoneContainment,
        inside: Boolean,
        accuracyTooPoor: Boolean,
    ): SafetyStatus = when {
        accuracyTooPoor -> SafetyStatus.UNCERTAIN
        containment == ZoneContainment.UNCERTAIN || containment == ZoneContainment.BOUNDARY ->
            SafetyStatus.NEAR_BOUNDARY

        inside -> when (zoneType) {
            ZoneType.RESTRICTED -> SafetyStatus.INSIDE_RESTRICTED
            ZoneType.DANGEROUS -> SafetyStatus.INSIDE_DANGEROUS
            ZoneType.SAFE -> SafetyStatus.INSIDE_SAFE_ZONE
        }

        else -> SafetyStatus.SAFE
    }
}
