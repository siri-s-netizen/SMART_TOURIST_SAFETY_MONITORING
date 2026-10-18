package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Axis-aligned bounding box in degrees. Used to frame the camera on a zone. */
data class GeoBoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val centerLatitude: Double get() = (south + north) / 2.0
    val centerLongitude: Double get() = (west + east) / 2.0
}

/**
 * Pure geometry helpers for polygons expressed in WGS-84 degrees.
 *
 * Two different spaces are used on purpose:
 *
 *  * **Containment** runs the classic even-odd ray-casting test directly in the lon/lat plane. For
 *    the small, local polygons this app deals with the lat/lon plane is an adequate proxy for the
 *    ground plane, and crucially the test is robust for any vertex ordering.
 *  * **Distances and intersection tests** are done in a *local metre frame* (equirectangular
 *    projection anchored on a chosen origin), because comparing metres against a GPS accuracy
 *    radius only makes sense in a metric space.
 */
object PolygonGeometry {

    /** Two vertices closer than this are considered the same point. */
    const val DUPLICATE_VERTEX_TOLERANCE_METERS: Double = 0.5

    /** Anything smaller than this is a degenerate (zero-area) polygon. */
    const val MIN_AREA_SQUARE_METERS: Double = 1.0

    /**
     * Classic ray-casting / even-odd containment test.
     *
     * A ray is cast from [point] in the +longitude direction and the number of boundary crossings
     * is counted; odd means inside. The `(a.latitude > y) != (b.latitude > y)` guard is what makes
     * the half-open edge rule consistent: it also short-circuits before the division, so a
     * perfectly horizontal edge can never divide by zero.
     */
    fun containsRayCasting(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            val straddlesLatitude = (a.latitude > point.latitude) != (b.latitude > point.latitude)
            if (straddlesLatitude) {
                val crossLongitude = (b.longitude - a.longitude) *
                    (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
                if (point.longitude < crossLongitude) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Shortest distance in metres from [point] to the polygon outline (edges, not vertices). */
    fun distanceToBoundaryMeters(point: GeoPoint, polygon: List<GeoPoint>): Double {
        if (polygon.isEmpty()) return Double.POSITIVE_INFINITY
        if (polygon.size == 1) return point.flatDistanceMeters(polygon[0])
        var minimum = Double.POSITIVE_INFINITY
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val d = distanceToSegmentMeters(point, a, b)
            if (d < minimum) minimum = d
        }
        return minimum
    }

    /**
     * Point-to-segment distance in metres, via a local metre frame centred on [point] so the
     * projection is exact at the point of interest.
     */
    fun distanceToSegmentMeters(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val frame = LocalFrame(point)
        val ax = frame.x(a)
        val ay = frame.y(a)
        val bx = frame.x(b)
        val by = frame.y(b)

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy

        // Degenerate segment (a == b): fall back to vertex distance.
        if (lengthSquared == 0.0) return sqrt(ax * ax + ay * ay)

        var t = -(ax * dx + ay * dy) / lengthSquared
        t = t.coerceIn(0.0, 1.0)

        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    /**
     * Absolute polygon area in square metres (shoelace, in a local metre frame).
     *
     * The frame is anchored on the polygon's **mean** position rather than on its first vertex.
     * The longitude scale depends on latitude, so anchoring on `polygon[0]` would make the result
     * depend on which corner happened to be listed first — reversing the ring would change the
     * number. The mean position is order- and winding-invariant, so a square measured clockwise and
     * counter-clockwise gives an identical area.
     */
    fun areaSquareMeters(polygon: List<GeoPoint>): Double {
        if (polygon.size < 3) return 0.0
        val anchor = GeoPoint(
            latitude = polygon.sumOf { it.latitude } / polygon.size,
            longitude = polygon.sumOf { it.longitude } / polygon.size,
        )
        val frame = LocalFrame(anchor)
        var sum = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            sum += frame.x(a) * frame.y(b) - frame.x(b) * frame.y(a)
        }
        return abs(sum) / 2.0
    }

    fun boundingBox(polygon: List<GeoPoint>): GeoBoundingBox? {
        if (polygon.isEmpty()) return null
        var south = polygon[0].latitude
        var north = polygon[0].latitude
        var west = polygon[0].longitude
        var east = polygon[0].longitude
        for (p in polygon) {
            if (p.latitude < south) south = p.latitude
            if (p.latitude > north) north = p.latitude
            if (p.longitude < west) west = p.longitude
            if (p.longitude > east) east = p.longitude
        }
        return GeoBoundingBox(south, west, north, east)
    }

    /**
     * True when two non-adjacent edges of the ring cross each other (a "bow-tie" polygon).
     *
     * Adjacent edges legitimately share a vertex and are skipped. Fewer than four vertices cannot
     * self-intersect, so a triangle always returns false.
     */
    fun isSelfIntersecting(polygon: List<GeoPoint>): Boolean {
        val n = polygon.size
        if (n < 4) return false

        val frame = LocalFrame(polygon[0])
        val xs = DoubleArray(n) { frame.x(polygon[it]) }
        val ys = DoubleArray(n) { frame.y(polygon[it]) }

        for (i in 0 until n) {
            val nextI = (i + 1) % n
            for (j in i + 1 until n) {
                val nextJ = (j + 1) % n
                // Skip edges that share a vertex.
                if (nextI == j || nextJ == i) continue
                val intersects = segmentsIntersect(
                    ax = xs[i], ay = ys[i], bx = xs[nextI], by = ys[nextI],
                    cx = xs[j], cy = ys[j], dx = xs[nextJ], dy = ys[nextJ],
                )
                if (intersects) return true
            }
        }
        return false
    }

    /**
     * Standard orientation-based segment intersection, including collinear overlap and touching
     * endpoints. Coordinates are already projected into a metre frame by the caller.
     */
    internal fun segmentsIntersect(
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double,
        dx: Double, dy: Double,
    ): Boolean {
        val o1 = orientation(ax, ay, bx, by, cx, cy)
        val o2 = orientation(ax, ay, bx, by, dx, dy)
        val o3 = orientation(cx, cy, dx, dy, ax, ay)
        val o4 = orientation(cx, cy, dx, dy, bx, by)

        val sign1Differs = (o1 > 0.0) != (o2 > 0.0)
        val sign2Differs = (o3 > 0.0) != (o4 > 0.0)
        if (sign1Differs && sign2Differs) return true

        // Collinear / touching cases.
        if (o1 == 0.0 && onSegment(ax, ay, bx, by, cx, cy)) return true
        if (o2 == 0.0 && onSegment(ax, ay, bx, by, dx, dy)) return true
        if (o3 == 0.0 && onSegment(cx, cy, dx, dy, ax, ay)) return true
        if (o4 == 0.0 && onSegment(cx, cy, dx, dy, bx, by)) return true

        return false
    }

    private fun orientation(
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double,
    ): Double = (by - ay) * (cx - bx) - (bx - ax) * (cy - by)

    /** Assumes p, q, r are collinear; is r inside the bounding box of segment pq? */
    private fun onSegment(
        px: Double, py: Double,
        qx: Double, qy: Double,
        rx: Double, ry: Double,
    ): Boolean = rx >= minOf(px, qx) && rx <= maxOf(px, qx) &&
        ry >= minOf(py, qy) && ry <= maxOf(py, qy)

    /**
     * Equirectangular projection to a local metre frame anchored on [origin].
     * Valid over the neighbourhood of [origin], which is all this app needs.
     */
    internal class LocalFrame(private val origin: GeoPoint) {
        private val metersPerDegreeLatitude: Double = GeoPoint.METERS_PER_DEGREE_LATITUDE
        private val metersPerDegreeLongitude: Double =
            GeoPoint.METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(origin.latitude))

        fun x(point: GeoPoint): Double = (point.longitude - origin.longitude) * metersPerDegreeLongitude

        fun y(point: GeoPoint): Double = (point.latitude - origin.latitude) * metersPerDegreeLatitude
    }
}
