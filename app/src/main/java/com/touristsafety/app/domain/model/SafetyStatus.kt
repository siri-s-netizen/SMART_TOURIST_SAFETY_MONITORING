package com.touristsafety.app.domain.model

/**
 * The single status indicator shown to the user.
 *
 * Severity drives both the home-screen colour and the "worst status wins" reduction across
 * overlapping zones. Green = safe, yellow = near a boundary or uncertain, red = inside a
 * restricted/dangerous zone.
 */
enum class SafetyStatus(val severity: Int) {
    SAFE(0),

    /** Inside a zone the tourist is *supposed* to be in. Informational, still green. */
    INSIDE_SAFE_ZONE(0),

    /** Close to an edge, or the fix is too poor to decide. Yellow. */
    NEAR_BOUNDARY(1),

    /** Fix unusable (accuracy worse than the configured maximum). Yellow. */
    UNCERTAIN(1),

    /** Red. */
    INSIDE_RESTRICTED(2),

    /** Red, highest priority: a dangerous area outranks a restricted one. */
    INSIDE_DANGEROUS(3),
    ;

    val isElevated: Boolean
        get() = severity >= 2

    companion object {
        fun worst(statuses: Collection<SafetyStatus>): SafetyStatus =
            statuses.maxByOrNull { it.severity } ?: SAFE
    }
}
