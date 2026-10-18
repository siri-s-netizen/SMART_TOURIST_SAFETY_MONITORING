package com.touristsafety.app.tracking

import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.geofencing.ZoneStatus

/** Something the user needs to know about *why* tracking is not behaving as expected. */
enum class TrackingProblem(val label: String) {
    PERMISSION_DENIED("Location permission not granted."),
    BACKGROUND_PERMISSION_DENIED("Background location permission not granted."),
    LOCATION_DISABLED("Location services are switched off in system settings."),
    NO_FIX_YET("Waiting for a GPS fix…"),
    POOR_ACCURACY("GPS accuracy is too poor to judge zone boundaries."),
    PROVIDER_ERROR("The location provider reported an error."),
    STORAGE_ERROR("A reading could not be saved to the local database."),
}

/**
 * The complete, UI-ready state of *real* GPS tracking.
 *
 * Note this type is only ever produced by the GPS pipeline. Simulated positions use a completely
 * separate state holder so that a demo can never be presented as real tracking.
 */
data class TrackingSnapshot(
    val isTracking: Boolean = false,

    /**
     * The user has asked for tracking, whether or not the pipeline could actually start it.
     *
     * This is deliberately separate from [isTracking]. A start that is refused because location
     * services are off, or because permission is missing, sets this to true and [isTracking] to
     * false — and *that* combination is what lets the app notice, when it next reaches the
     * foreground, that the obstacle has gone away and the user's request can now be honoured.
     * Without it the request was forgotten the moment it could not be served, so the only way to
     * recover was to work out that Start had to be pressed a second time.
     */
    val trackingRequested: Boolean = false,

    /** True when the background foreground-service is keeping GPS alive. */
    val backgroundTracking: Boolean = false,

    /** The most recent live reading. Null means "no fix yet" — never a placeholder coordinate. */
    val lastReading: GeoReading? = null,

    val zoneStatuses: List<ZoneStatus> = emptyList(),
    val overallStatus: SafetyStatus = SafetyStatus.SAFE,

    /** Number of enabled zones that were actually tested against the last reading. */
    val activeZoneCount: Int = 0,

    val readingCount: Int = 0,
    val startedAtMillis: Long? = null,

    val problem: TrackingProblem? = null,
    val lastErrorMessage: String? = null,

    /**
     * The location provider's own complaint, verbatim, as reported by
     * [com.touristsafety.app.location.LocationSource.lastError].
     *
     * Kept apart from [lastErrorMessage] because it is not necessarily fatal: the fused provider can
     * report "no usable location right now" while the subscription is perfectly healthy. It was
     * previously recorded by [com.touristsafety.app.location.FusedLocationSource] and then read by
     * nothing, which made "the provider says it has nothing" indistinguishable from "we are simply
     * still waiting" on screen.
     */
    val providerError: String? = null,

    /** Set when the last reading's accuracy exceeded the configured ceiling. */
    val accuracyIsPoor: Boolean = false,
) {
    val hasFix: Boolean get() = lastReading != null

    val accuracyMeters: Float? get() = lastReading?.accuracyMeters

    /** True when there is no live fix to show. Drives the "GPS unavailable" banner. */
    val isWaitingForFix: Boolean get() = isTracking && lastReading == null
}
