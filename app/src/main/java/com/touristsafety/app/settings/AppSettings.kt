package com.touristsafety.app.settings

import com.touristsafety.app.geofencing.GeoFencePolicy
import com.touristsafety.app.location.LocationRequestSpec
import com.touristsafety.app.tracking.PositionReportPolicy

/** Accuracy/speed trade-off handed to the Fused Location Provider. */
enum class LocationAccuracyPriority(
    val storageKey: String,
    val displayName: String,
) {
    HIGH_ACCURACY("HIGH_ACCURACY", "High accuracy (GPS)"),
    BALANCED("BALANCED", "Balanced"),
    LOW_POWER("LOW_POWER", "Low power"),
    ;

    companion object {
        fun fromStorage(value: String?): LocationAccuracyPriority =
            entries.firstOrNull { it.storageKey == value } ?: HIGH_ACCURACY
    }
}

/**
 * Everything the user can tune. Immutable so it can be snapshotted per GPS fix without locking.
 *
 * The three geo-fence fields are exposed because no single tolerance suits both a city centre
 * (2–5 m accuracy) and a canyon (30–50 m accuracy), and a tester needs to be able to widen them
 * without a rebuild.
 */
data class AppSettings(
    val updateIntervalMillis: Long = DEFAULT_UPDATE_INTERVAL_MILLIS,
    val minUpdateDistanceMeters: Float = DEFAULT_MIN_UPDATE_DISTANCE_METERS,
    val accuracyPriority: LocationAccuracyPriority = LocationAccuracyPriority.HIGH_ACCURACY,
    val backgroundTrackingEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val simulationModeEnabled: Boolean = false,
    val entryToleranceMeters: Double = GeoFencePolicy.DEFAULT_ENTRY_TOLERANCE_METERS,
    val exitToleranceMeters: Double = GeoFencePolicy.DEFAULT_EXIT_TOLERANCE_METERS,
    val maxUsableAccuracyMeters: Double = GeoFencePolicy.DEFAULT_MAX_USABLE_ACCURACY_METERS,
    /** Retained so a long tracking session cannot grow the database without bound. */
    val retainedLocationRecords: Int = DEFAULT_RETAINED_LOCATION_RECORDS,
    /**
     * Whether routine position reports are queued for the authority at all. Off means the authority
     * still receives zone alerts and SOS, but the tourist's dot on their map only moves on those.
     */
    val positionReportingEnabled: Boolean = true,
    /** How often a position report is queued while the tourist is moving or stationary. */
    val positionReportIntervalMillis: Long = PositionReportPolicy.DEFAULT_INTERVAL_MILLIS,
) {
    val policy: GeoFencePolicy
        get() = GeoFencePolicy(
            entryToleranceMeters = entryToleranceMeters,
            exitToleranceMeters = exitToleranceMeters,
            maxUsableAccuracyMeters = maxUsableAccuracyMeters,
        )

    val positionReportPolicy: PositionReportPolicy
        get() = PositionReportPolicy(
            enabled = positionReportingEnabled,
            intervalMillis = positionReportIntervalMillis,
        )

    val requestSpec: LocationRequestSpec
        get() = LocationRequestSpec(
            intervalMillis = updateIntervalMillis,
            minUpdateDistanceMeters = minUpdateDistanceMeters,
            priority = accuracyPriority,
        )

    companion object {
        /** 5 s is the classic balance: responsive enough to catch a boundary crossing, cheap enough for a walk. */
        const val DEFAULT_UPDATE_INTERVAL_MILLIS: Long = 5_000L

        /** Ignore jitter smaller than this; GPS noise alone is often 3–5 m. */
        const val DEFAULT_MIN_UPDATE_DISTANCE_METERS: Float = 3f

        const val DEFAULT_RETAINED_LOCATION_RECORDS: Int = 5_000

        /** Bounds accepted by the Settings UI. */
        val UPDATE_INTERVAL_CHOICES_MILLIS: List<Long> =
            listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
    }
}
