package com.touristsafety.app.location

import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.settings.LocationAccuracyPriority
import kotlinx.coroutines.flow.Flow

/** Interval/accuracy specification, mirroring what the Fused Location Provider needs. */
data class LocationRequestSpec(
    val intervalMillis: Long,
    val minUpdateDistanceMeters: Float,
    val priority: LocationAccuracyPriority,
)

/**
 * The seam between the geo-fencing pipeline and Google Play Services.
 *
 * Behind this interface sits a `callbackFlow` backed by the Fused Location Provider. Having the
 * interface means the tracking pipeline can be exercised with a scripted fake source in JVM tests,
 * which is the only way to prove entry/exit behaviour deterministically without a physical device.
 */
interface LocationSource {

    /**
     * A cold stream of readings. Each collection starts exactly one subscription and cancelling the
     * collection removes it — this is what prevents duplicate location subscriptions.
     */
    fun locationUpdates(spec: LocationRequestSpec): Flow<GeoReading>

    /** Best cached fix, or null when nothing is cached. Never invents a coordinate. */
    suspend fun lastKnownReading(): GeoReading?

    /** False when the user has switched location services off at the OS level. */
    fun isLocationServiceEnabled(): Boolean

    /** Non-null when the last subscription failed, for display. */
    val lastError: String?
}
