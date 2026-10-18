package com.touristsafety.app.domain.model

/**
 * Where a reading came from.
 *
 * Simulation and real GPS are kept strictly apart all the way down to the database row, so a
 * simulated coordinate can never be mistaken for — or silently overwrite — a real one.
 */
enum class LocationSource(val storageKey: String, val displayName: String) {
    GPS("GPS", "Actual GPS"),
    SIMULATION("SIMULATION", "Simulation"),
    ;

    val isSimulated: Boolean
        get() = this == SIMULATION

    companion object {
        fun fromStorage(value: String?): LocationSource =
            entries.firstOrNull { it.storageKey == value } ?: GPS
    }
}

/**
 * One location observation.
 *
 * [accuracyMeters] is the horizontal accuracy radius reported by the platform (68% confidence).
 * A non-positive or non-finite value means "unknown" and is handled by the containment policy.
 */
data class GeoReading(
    val point: GeoPoint,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val source: LocationSource,
) {
    val isSimulated: Boolean
        get() = source.isSimulated
}
