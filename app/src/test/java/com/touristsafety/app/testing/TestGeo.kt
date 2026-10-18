package com.touristsafety.app.testing

import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType
import kotlin.math.cos

/**
 * Deterministic geometry fixtures shared by the tests.
 *
 * Everything is derived from a single origin so that "how far is this point from that edge?" is an
 * exact, hand-checkable number rather than a magic constant. A 200 m square gives a centre that is
 * exactly 100 m from every edge, which makes the accuracy-aware boundary tests unambiguous.
 */
object TestGeo {

    /** Eiffel Tower. Any origin works; a real one keeps the numbers realistic. */
    val origin: GeoPoint = GeoPoint(48.8584, 2.2945)

    const val SIDE_METERS: Double = 200.0

    /** Half the side: the distance from the centre of the square to any edge. */
    const val CENTER_TO_EDGE_METERS: Double = SIDE_METERS / 2.0

    fun metersToLatitudeDegrees(meters: Double): Double =
        meters / GeoPoint.METERS_PER_DEGREE_LATITUDE

    fun metersToLongitudeDegrees(meters: Double, atLatitude: Double): Double =
        meters / (GeoPoint.METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(atLatitude)))

    /** A point [northMeters] up and [eastMeters] right of [from], in metres. */
    fun at(from: GeoPoint, northMeters: Double, eastMeters: Double): GeoPoint = GeoPoint(
        latitude = from.latitude + metersToLatitudeDegrees(northMeters),
        longitude = from.longitude + metersToLongitudeDegrees(eastMeters, from.latitude),
    )

    /**
     * Axis-aligned square with its south-west corner at [from].
     * Winding order: SW -> SE -> NE -> NW (the ring is stored open; the closing edge is implicit).
     */
    fun square(from: GeoPoint = origin, sideMeters: Double = SIDE_METERS): List<GeoPoint> {
        val dLat = metersToLatitudeDegrees(sideMeters)
        val dLon = metersToLongitudeDegrees(sideMeters, from.latitude)
        return listOf(
            GeoPoint(from.latitude, from.longitude),
            GeoPoint(from.latitude, from.longitude + dLon),
            GeoPoint(from.latitude + dLat, from.longitude + dLon),
            GeoPoint(from.latitude + dLat, from.longitude),
        )
    }

    val squareVertices: List<GeoPoint> get() = square()

    /** Exactly [CENTER_TO_EDGE_METERS] from every edge. */
    val squareCenter: GeoPoint
        get() = at(origin, CENTER_TO_EDGE_METERS, CENTER_TO_EDGE_METERS)

    /** 300 m south of the south edge, and inside the edge's east/west span. */
    val outsideSouth: GeoPoint
        get() = at(origin, -300.0, CENTER_TO_EDGE_METERS)

    /** Exactly on the south edge. */
    val onSouthEdge: GeoPoint
        get() = at(origin, 0.0, CENTER_TO_EDGE_METERS)

    /** 3 m north of the south edge, i.e. just inside. */
    val justInsideSouthEdge: GeoPoint
        get() = at(origin, 3.0, CENTER_TO_EDGE_METERS)

    /** 3 m south of the south edge, i.e. just outside. */
    val justOutsideSouthEdge: GeoPoint
        get() = at(origin, -3.0, CENTER_TO_EDGE_METERS)

    /** Square translated [north] m and [east] m: overlaps the base square but is not identical. */
    fun overlappingSquare(north: Double, east: Double): List<GeoPoint> =
        square(at(origin, north, east))

    /** An L-shaped concave polygon: the notch must read as OUTSIDE. */
    fun concaveL(): List<GeoPoint> = listOf(
        at(origin, 0.0, 0.0),
        at(origin, 0.0, 300.0),
        at(origin, 100.0, 300.0),
        at(origin, 100.0, 100.0),
        at(origin, 300.0, 100.0),
        at(origin, 300.0, 0.0),
    )

    /** Point inside the L's notch, which a convex-only test would wrongly report as inside. */
    val concaveNotchPoint: GeoPoint get() = at(origin, 200.0, 200.0)

    /**
     * A self-crossing "bow-tie".
     *
     * Deliberately asymmetric: a *symmetric* bow-tie has a shoelace area of exactly zero and would
     * be rejected as a degenerate sliver before the self-intersection check ever runs. These vertices
     * cross over each other while enclosing a non-zero (net) area, so they isolate the crossing test.
     * The edges (0,0)-(400,200) and (0,200)-(200,0) intersect.
     */
    fun bowtie(): List<GeoPoint> = listOf(
        at(origin, 0.0, 0.0),
        at(origin, 200.0, 400.0),
        at(origin, 200.0, 0.0),
        at(origin, 0.0, 200.0),
    )

    /** Collinear vertices: geometrically valid coordinates, but no usable area. */
    fun degenerateLine(): List<GeoPoint> = listOf(
        at(origin, 0.0, 0.0),
        at(origin, 0.0, 100.0),
        at(origin, 0.0, 200.0),
    )
}

fun testZone(
    id: Long,
    type: ZoneType = ZoneType.RESTRICTED,
    vertices: List<GeoPoint> = TestGeo.squareVertices,
    enabled: Boolean = true,
    name: String = "Zone $id",
): GeoFenceZone = GeoFenceZone(
    id = id,
    name = name,
    description = "",
    type = type,
    vertices = vertices,
    enabled = enabled,
    createdAtMillis = 1_000L,
    updatedAtMillis = 1_000L,
)

fun testReading(
    point: GeoPoint,
    accuracyMeters: Float = 5f,
    timestampMillis: Long = 0L,
    source: LocationSource = LocationSource.GPS,
): GeoReading = GeoReading(
    point = point,
    accuracyMeters = accuracyMeters,
    timestampMillis = timestampMillis,
    source = source,
)
