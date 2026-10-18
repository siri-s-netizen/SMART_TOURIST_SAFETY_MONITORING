package com.touristsafety.app.domain.model

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * An immutable WGS-84 coordinate.
 *
 * The domain layer deliberately never stores `android.location.Location`. Keeping coordinates in
 * a plain Kotlin type is what lets the entire geo-fencing core be unit-tested on a JVM with no
 * emulator, and it keeps UI/GPS/SQLite concerns out of the geometry code.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    /** True when both components are finite and inside the legal WGS-84 range. */
    val hasValidRange: Boolean
        get() = latitude.isFinite() &&
            longitude.isFinite() &&
            latitude >= -90.0 && latitude <= 90.0 &&
            longitude >= -180.0 && longitude <= 180.0

    /**
     * Distance in metres using an equirectangular approximation of the ellipsoid.
     *
     * Error stays well under 0.5% at the scales a walking tourist cares about (tens of metres to
     * tens of kilometres) and unlike haversine it is cheap enough to run against every vertex of
     * every enabled polygon on every GPS fix.
     */
    fun flatDistanceMeters(other: GeoPoint): Double {
        val meanLatitudeRad = Math.toRadians((latitude + other.latitude) / 2.0)
        val deltaLat = Math.toRadians(other.latitude - latitude) * EARTH_RADIUS_METERS
        val deltaLon = Math.toRadians(other.longitude - longitude) * EARTH_RADIUS_METERS * cos(meanLatitudeRad)
        return sqrt(deltaLat * deltaLat + deltaLon * deltaLon)
    }

    fun isWithinMeters(other: GeoPoint, meters: Double): Boolean = flatDistanceMeters(other) <= meters

    /** Locale-independent: used by the persistence codec, never for display. */
    override fun toString(): String = "$latitude, $longitude"

    companion object {
        const val EARTH_RADIUS_METERS: Double = 6371008.8

        /** One degree of latitude is a near-constant ~111.19 km; longitude shrinks by cos(lat). */
        const val METERS_PER_DEGREE_LATITUDE: Double = 111194.93
    }
}
