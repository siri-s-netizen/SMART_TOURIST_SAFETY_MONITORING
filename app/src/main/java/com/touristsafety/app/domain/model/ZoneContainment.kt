package com.touristsafety.app.domain.model

/**
 * Result of testing one coordinate against one polygon.
 *
 * BOUNDARY and UNCERTAIN are deliberately distinct from INSIDE/OUTSIDE: they mean "the data is not
 * good enough to claim a transition", which is what stops the engine from firing an emergency
 * alert based on a GPS fix that is 40 m wide sitting 3 m inside a zone.
 */
enum class ZoneContainment {
    INSIDE,
    OUTSIDE,

    /** Within the configured tolerance band of an edge. */
    BOUNDARY,

    /** The reported accuracy circle straddles the boundary, or the fix is unusable. */
    UNCERTAIN,
    ;

    /** True only for the two outcomes that are safe to act on. */
    val isDecided: Boolean
        get() = this == INSIDE || this == OUTSIDE
}
