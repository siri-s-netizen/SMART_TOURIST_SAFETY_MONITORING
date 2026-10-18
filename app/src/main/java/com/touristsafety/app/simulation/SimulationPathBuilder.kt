package com.touristsafety.app.simulation

import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.geofencing.PolygonGeometry
import kotlin.math.cos

/**
 * Pure path planning for demo mode.
 *
 * The guarantee this class provides is the whole point of the simulation: **the generated route is
 * mathematically certain to start outside the polygon, pass through its interior, and end outside
 * again.** Without that guarantee a demo could silently never cross a boundary and look broken.
 *
 * The interior point is found by scanning — not by averaging vertices — because the centroid of a
 * concave polygon (an L-shape, say) can easily fall in the notch, which is *outside*.
 */
object SimulationPathBuilder {

    const val DEFAULT_SIDE_METERS: Double = 200.0
    const val DEFAULT_SAMPLES: Int = 12

    /** How far past the polygon's bounding box the route starts and ends. */
    const val DEFAULT_OVERSHOOT_METERS: Double = 150.0

    /** An axis-aligned square centred on [center]: the "create a test polygon" button. */
    fun createTestSquare(
        center: GeoPoint,
        sideMeters: Double = DEFAULT_SIDE_METERS,
    ): List<GeoPoint> {
        val half = sideMeters / 2.0
        val deltaLat = half / GeoPoint.METERS_PER_DEGREE_LATITUDE
        val deltaLon = half / (
            GeoPoint.METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(center.latitude))
            )

        return listOf(
            GeoPoint(center.latitude - deltaLat, center.longitude - deltaLon),
            GeoPoint(center.latitude - deltaLat, center.longitude + deltaLon),
            GeoPoint(center.latitude + deltaLat, center.longitude + deltaLon),
            GeoPoint(center.latitude + deltaLat, center.longitude - deltaLon),
        )
    }

    /**
     * Finds a point that is genuinely inside [vertices], by scanning a grid over the bounding box.
     * Returns null only for a degenerate polygon.
     */
    fun findInsidePoint(vertices: List<GeoPoint>, gridSteps: Int = 12): GeoPoint? {
        val box = PolygonGeometry.boundingBox(vertices) ?: return null
        if (box.north <= box.south || box.east <= box.west) return null

        for (i in 1 until gridSteps) {
            for (j in 1 until gridSteps) {
                val candidate = GeoPoint(
                    latitude = box.south + (box.north - box.south) * i / gridSteps,
                    longitude = box.west + (box.east - box.west) * j / gridSteps,
                )
                if (PolygonGeometry.containsRayCasting(candidate, vertices)) return candidate
            }
        }
        return null
    }

    /**
     * A straight north-to-south corridor through [vertices], sampled [samples] times.
     *
     * Both endpoints sit further from the interior point than any bounding-box corner, so they are
     * provably outside the polygon. The middle samples are inside.
     */
    fun routeThroughZone(
        vertices: List<GeoPoint>,
        samples: Int = DEFAULT_SAMPLES,
        overshootMeters: Double = DEFAULT_OVERSHOOT_METERS,
    ): List<GeoPoint> {
        require(samples >= 2) { "A route needs at least two samples" }

        val inside = findInsidePoint(vertices) ?: return emptyList()
        val box = PolygonGeometry.boundingBox(vertices) ?: return emptyList()

        val corners = listOf(
            GeoPoint(box.north, box.west),
            GeoPoint(box.north, box.east),
            GeoPoint(box.south, box.west),
            GeoPoint(box.south, box.east),
        )
        val reach = corners.maxOf { inside.flatDistanceMeters(it) } + overshootMeters

        val start = offsetNorth(inside, -reach)
        val end = offsetNorth(inside, reach)

        return (0..samples).map { step ->
            val t = step.toDouble() / samples
            GeoPoint(
                latitude = start.latitude + (end.latitude - start.latitude) * t,
                longitude = start.longitude,
            )
        }
    }

    private fun offsetNorth(point: GeoPoint, meters: Double): GeoPoint = GeoPoint(
        latitude = point.latitude + meters / GeoPoint.METERS_PER_DEGREE_LATITUDE,
        longitude = point.longitude,
    )
}
