package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.simulation.SimulationPathBuilder
import kotlin.math.cos

/**
 * Builds the "sample areas" that the *Manage Geo-Fences* screen can drop around the user with one
 * tap, so several polygons can be seen on the map without drawing them by hand.
 *
 * The placement maths is deliberately pure (no Android, no repositories) so it can be unit-tested
 * on a JVM: the caller only has to persist whatever [create] returns.
 */
object SampleAreas {

    /** Edge length of each square sample area, in metres. */
    const val SIDE_METERS: Double = 140.0

    /** Centre-to-centre distance between neighbouring sample areas, in metres. */
    const val SPACING_METERS: Double = 340.0

    /** One sample area: a display name, its safety type and a horizontal slot (-1, 0, +1). */
    data class Spec(
        val name: String,
        val type: ZoneType,
        val slot: Int,
    )

    /** Three areas in a row — one of each type — centred on the user. */
    val DEFAULT_SPECS: List<Spec> = listOf(
        Spec("Sample: restricted area", ZoneType.RESTRICTED, -1),
        Spec("Sample: dangerous area", ZoneType.DANGEROUS, 0),
        Spec("Sample: safe zone", ZoneType.SAFE, +1),
    )

    /** Builds the sample zones (unpersisted) around [center]. */
    fun create(center: GeoPoint, specs: List<Spec> = DEFAULT_SPECS): List<GeoFenceZone> =
        specs.map { spec ->
            GeoFenceZone(
                name = spec.name,
                description = "Generated sample area — demonstration only. Delete it any time.",
                type = spec.type,
                vertices = SimulationPathBuilder.createTestSquare(offset(center, spec.slot), SIDE_METERS),
                enabled = true,
            )
        }

    /**
     * Moves [center] sideways by [slot] × [SPACING_METERS] metres (east for positive slots, west for
     * negative). Latitude is left untouched, so the areas sit in a horizontal row. Longitude is
     * scaled by `cos(latitude)` so the row keeps the intended on-the-ground distance away from the
     * equator.
     */
    fun offset(center: GeoPoint, slot: Int): GeoPoint {
        val cosLat = cos(Math.toRadians(center.latitude)).coerceAtLeast(0.2)
        val deltaLon = SPACING_METERS / (GeoPoint.METERS_PER_DEGREE_LATITUDE * cosLat)
        return GeoPoint(
            latitude = center.latitude,
            longitude = center.longitude + slot * deltaLon,
        )
    }
}
