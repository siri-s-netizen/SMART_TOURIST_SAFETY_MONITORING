package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneContainment

/** A containment verdict plus the metric distance that justified it. */
data class ContainmentResult(
    val containment: ZoneContainment,
    val distanceToBoundaryMeters: Double,
)

/**
 * The reusable point-in-polygon service.
 *
 * This is the single place in the app that decides "inside or outside a polygon". The UI, the
 * tracking pipeline and the simulation all go through it, so behaviour can never drift between
 * them.
 */
interface PolygonContainmentService {
    fun locate(
        point: GeoPoint,
        polygon: List<GeoPoint>,
        accuracyMeters: Float,
        boundaryToleranceMeters: Double,
        maxUsableAccuracyMeters: Double,
    ): ContainmentResult
}

/**
 * Ray-casting implementation with an accuracy-aware policy near the boundary.
 *
 * The rule is simple and deliberately conservative:
 *
 *  1. If the fix itself is worse than [maxUsableAccuracyMeters], the answer is UNCERTAIN.
 *  2. If the point sits inside the tolerance band around an edge, the answer is BOUNDARY.
 *  3. If the accuracy circle is wider than the distance to the nearest edge, then the true
 *     position could be on either side, so the answer is UNCERTAIN — never a guess.
 *  4. Otherwise run ray casting and return a decided INSIDE/OUTSIDE.
 *
 * Nothing here ever uses a circular radius as a stand-in for the polygon outline: the radius is
 * only ever used to decide whether the polygon test can be *trusted*.
 */
object RayCastingContainmentService : PolygonContainmentService {

    /**
     * When the platform does not report an accuracy (or reports 0 / NaN), assume a middling value
     * rather than pretending the fix is perfect.
     */
    const val DEFAULT_UNKNOWN_ACCURACY_METERS: Double = 25.0

    override fun locate(
        point: GeoPoint,
        polygon: List<GeoPoint>,
        accuracyMeters: Float,
        boundaryToleranceMeters: Double,
        maxUsableAccuracyMeters: Double,
    ): ContainmentResult {
        val distance = if (polygon.size >= 2) {
            PolygonGeometry.distanceToBoundaryMeters(point, polygon)
        } else {
            Double.POSITIVE_INFINITY
        }

        if (polygon.size < GeoFenceZone.MIN_VERTICES || !point.hasValidRange) {
            return ContainmentResult(ZoneContainment.UNCERTAIN, distance)
        }

        val accuracy = normalizeAccuracy(accuracyMeters)

        // 1. The fix is too coarse to be meaningful anywhere.
        if (accuracy > maxUsableAccuracyMeters) {
            return ContainmentResult(ZoneContainment.UNCERTAIN, distance)
        }

        // 2. Sitting on (or within tolerance of) an edge.
        if (distance <= boundaryToleranceMeters) {
            return ContainmentResult(ZoneContainment.BOUNDARY, distance)
        }

        // 3. The uncertainty circle straddles the edge.
        if (accuracy > distance) {
            return ContainmentResult(ZoneContainment.UNCERTAIN, distance)
        }

        // 4. Good enough to decide.
        val inside = PolygonGeometry.containsRayCasting(point, polygon)
        return ContainmentResult(
            containment = if (inside) ZoneContainment.INSIDE else ZoneContainment.OUTSIDE,
            distanceToBoundaryMeters = distance,
        )
    }

    fun normalizeAccuracy(accuracyMeters: Float): Double =
        if (accuracyMeters.isFinite() && accuracyMeters > 0f) {
            accuracyMeters.toDouble()
        } else {
            DEFAULT_UNKNOWN_ACCURACY_METERS
        }
}
