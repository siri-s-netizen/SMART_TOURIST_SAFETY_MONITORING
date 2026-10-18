package com.touristsafety.app.location

/**
 * The six states a tourist actually needs to be able to tell apart, plus "off" and "stale".
 *
 * The reviewer's point was that GPS reception, internet connectivity and map-tile availability are
 * three *separate* things, and that the app was conflating them: a blank map said nothing about
 * whether the phone had a fix, and a fix said nothing about whether tiles could load. This enum is the
 * GPS half of that separation, and it is deliberately finer-grained than "tracking / not tracking".
 */
enum class GpsState {
    /** Tracking has not been started. No attempt is being made to get a fix. */
    OFF,

    /** The user has not granted location permission, so nothing can be received at all. */
    PERMISSION_DENIED,

    /** Permission is fine, but location is switched off in Android settings. */
    LOCATION_DISABLED,

    /** Tracking is on and we are still inside the normal first-fix window. */
    WAITING,

    /** Tracking is on, no fix has arrived yet, and it is taking longer than it should. */
    ACQUIRING,

    /** A recent, usable fix. */
    LIVE,

    /** A fix exists but its accuracy is worse than the ceiling used to judge boundaries. */
    POOR_ACCURACY,

    /** The newest fix is old enough that it can no longer be trusted as a live position. */
    STALE,
}

/** Everything the status decision depends on. Kept flat so it is trivial to build in a test. */
data class GpsStatusInput(
    val hasForegroundPermission: Boolean,
    val locationServiceEnabled: Boolean,
    val isTracking: Boolean,
    val lastFixAtMillis: Long?,
    val lastFixAccuracyMeters: Float?,
    val nowMillis: Long,
    /** When the current tracking session started, used to tell "waiting" from "acquiring". */
    val startedTrackingAtMillis: Long? = null,
    /** A message from the location provider, if it reported one. */
    val providerError: String? = null,
    /** Accuracy worse than this cannot be used to judge whether the user is inside a boundary. */
    val maxUsableAccuracyMeters: Double = 100.0,
)

/** The answer: one state, one short label, and the honest detail behind it. */
data class GpsStatus(
    val state: GpsState,
    val label: String,
    val detail: String?,
) {
    /** Only [GpsState.LIVE] may be presented as a live position. */
    val isLive: Boolean get() = state == GpsState.LIVE

    /** True while the app is still trying to get a first fix. */
    val isAcquiring: Boolean
        get() = state == GpsState.WAITING || state == GpsState.ACQUIRING
}

/**
 * Turns the raw tracking facts into one headline state.
 *
 * This is a pure function on purpose. The UI previously decided this inline in several places and the
 * same conditions were spelled out differently on different screens; now there is one rule, and it is
 * unit-tested against every branch.
 *
 * Order matters: permission and the system switch are checked first, because without them no amount of
 * waiting will produce a fix and telling the user "waiting for GPS" would be a lie.
 */
fun gpsStatusFor(input: GpsStatusInput): GpsStatus {
    if (!input.hasForegroundPermission) {
        return GpsStatus(
            state = GpsState.PERMISSION_DENIED,
            label = "Permission denied",
            detail = "Location access is not granted, so no position can be received. " +
                "Grant it on the Tracking screen.",
        )
    }

    if (!input.locationServiceEnabled) {
        return GpsStatus(
            state = GpsState.LOCATION_DISABLED,
            label = "Location unavailable",
            detail = "Location is switched off in Android settings, so no fix can be received.",
        )
    }

    if (!input.isTracking) {
        return GpsStatus(
            state = GpsState.OFF,
            label = "Not tracking",
            detail = input.lastFixAtMillis?.let { at ->
                "Tracking is stopped. Last known fix ${ageLabel(input.nowMillis - at)} ago."
            } ?: "Tracking is stopped. No fix has been received yet.",
        )
    }

    val fixAt = input.lastFixAtMillis
    if (fixAt == null) {
        val waitingFor = input.startedTrackingAtMillis?.let { input.nowMillis - it } ?: 0L
        return if (waitingFor < ACQUIRING_AFTER_MILLIS) {
            GpsStatus(
                state = GpsState.WAITING,
                label = "Waiting for GPS",
                detail = "Tracking started ${(waitingFor / 1000L).coerceAtLeast(0L)}s ago. " +
                    "A first fix usually takes a few seconds outdoors.",
            )
        } else {
            GpsStatus(
                state = GpsState.ACQUIRING,
                label = "Acquiring location…",
                detail = input.providerError
                    ?: "No fix after ${waitingFor / 1000L}s. Move outdoors or near a window — " +
                        "indoors the signal often never arrives.",
            )
        }
    }

    val age = (input.nowMillis - fixAt).coerceAtLeast(0L)
    val accuracy = input.lastFixAccuracyMeters

    if (age > STALE_AFTER_MILLIS) {
        return GpsStatus(
            state = GpsState.STALE,
            label = "GPS stale",
            detail = "The newest fix is ${ageLabel(age)} old. This is the last known position, " +
                "not necessarily where you are now.",
        )
    }

    if (accuracy != null && accuracy > input.maxUsableAccuracyMeters) {
        return GpsStatus(
            state = GpsState.POOR_ACCURACY,
            label = "Poor accuracy",
            detail = "±${accuracy.toInt()} m is worse than the ±${input.maxUsableAccuracyMeters.toInt()} m " +
                "limit used to judge zone boundaries, so nearby zones may read as uncertain.",
        )
    }

    return GpsStatus(
        state = GpsState.LIVE,
        label = "Live",
        detail = buildString {
            append("Fix ${ageLabel(age)} ago")
            if (accuracy != null) append(", ±${accuracy.toInt()} m")
        },
    )
}

/** Human-readable age, so "45s" and "3m 20s" read better than "45230 ms". */
fun ageLabel(ageMillis: Long): String {
    val seconds = (ageMillis / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3_600L -> "${seconds / 60L}m ${seconds % 60L}s"
        else -> "${seconds / 3_600L}h ${(seconds % 3_600L) / 60L}m"
    }
}

/**
 * How long a first fix may take before "waiting" becomes "acquiring".
 *
 * Ten seconds is a deliberate choice: outdoors a first fix is typically under five seconds, so by ten
 * seconds something is genuinely impeding it and the user deserves that information rather than an
 * indefinite "waiting".
 */
const val ACQUIRING_AFTER_MILLIS: Long = 10_000L

/**
 * How old a fix may be before it stops counting as a live position.
 *
 * Thirty seconds is roughly three missed updates at the default interval, so it means the updates have
 * actually stopped rather than merely being slow.
 */
const val STALE_AFTER_MILLIS: Long = 30_000L
