package com.touristsafety.app.geofencing

/**
 * Tuning knobs for the containment decision, surfaced in Settings so a tester can trade alert
 * sensitivity against false positives on real hardware.
 *
 * The two tolerances implement hysteresis: it takes a *larger* margin to declare an exit than an
 * entry, which stops a tourist standing exactly on a boundary from generating an endless
 * ENTRY/EXIT ping-pong.
 */
data class GeoFencePolicy(
    /** A point must be at least this far inside before entry is believed. */
    val entryToleranceMeters: Double = DEFAULT_ENTRY_TOLERANCE_METERS,

    /** A point must be at least this far outside before exit is believed. */
    val exitToleranceMeters: Double = DEFAULT_EXIT_TOLERANCE_METERS,

    /**
     * Readings worse than this are rejected outright: the engine reports UNCERTAIN and holds the
     * previous state instead of guessing which side of a boundary the tourist is on.
     */
    val maxUsableAccuracyMeters: Double = DEFAULT_MAX_USABLE_ACCURACY_METERS,
) {
    init {
        require(entryToleranceMeters >= 0.0) { "entryToleranceMeters must not be negative" }
        require(exitToleranceMeters >= entryToleranceMeters) {
            "exitToleranceMeters must be >= entryToleranceMeters to keep the hysteresis band non-inverted"
        }
        require(maxUsableAccuracyMeters > 0.0) { "maxUsableAccuracyMeters must be positive" }
    }

    companion object {
        const val DEFAULT_ENTRY_TOLERANCE_METERS: Double = 8.0
        const val DEFAULT_EXIT_TOLERANCE_METERS: Double = 15.0
        const val DEFAULT_MAX_USABLE_ACCURACY_METERS: Double = 100.0
    }
}
