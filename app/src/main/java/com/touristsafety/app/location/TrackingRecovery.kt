package com.touristsafety.app.location

/**
 * What the app should do the moment it comes back to the foreground.
 *
 * The audit (`docs/gps_geofence_audit.md`, RC-1) found that the app had **no recovery path at all**
 * for the three most ordinary real-device situations: the user grants location after the permission
 * dialog, the user switches location on in Settings, or permission is revoked while tracking runs.
 * The method that was supposed to handle this was empty and never called, so the user had to work out
 * that Start had to be pressed a second time.
 *
 * This is a pure function of the facts so that every branch is unit-testable without a device — which
 * matters, because the situations it handles are exactly the ones a JVM test cannot otherwise reach.
 */
enum class TrackingRecoveryAction {
    /** Nothing to do: either nothing was asked for, or the pipeline is already in the right state. */
    NONE,

    /** The obstacle is gone and tracking was requested: start it now. */
    START,

    /** Permission was taken away while the pipeline was running: stop it rather than keep failing. */
    STOP,

    /** Tracking was requested but the runtime permission is missing: the dialog must be shown. */
    REQUEST_PERMISSION,

    /** Tracking was requested, permission is fine, but location is switched off device-wide. */
    WAIT_FOR_LOCATION,
}

/** Everything the decision depends on. Deliberately flat so a test can build it in one line. */
data class TrackingRecoveryInput(
    /** The user asked for tracking, whether or not it could be started. */
    val trackingRequested: Boolean,
    /** The pipeline is actually running right now. */
    val isTracking: Boolean,
    val hasForegroundPermission: Boolean,
    val locationServiceEnabled: Boolean,
    val wantsBackground: Boolean,
    val hasBackgroundPermission: Boolean,
)

/** The decision, plus what to tell the user about it — if anything. */
data class TrackingRecovery(
    val action: TrackingRecoveryAction,
    val message: String? = null,
)

/**
 * Decides the single action to take on resume.
 *
 * Order matters. A revoked permission outranks everything: continuing to claim we are tracking when we
 * cannot receive a fix is the one outcome a safety app must never produce. A switched-off location
 * service comes next, because no amount of waiting will produce a fix. Only then is starting
 * considered.
 */
fun trackingRecoveryFor(input: TrackingRecoveryInput): TrackingRecovery {
    // Nothing was ever asked for.
    if (!input.trackingRequested && !input.isTracking) {
        return TrackingRecovery(TrackingRecoveryAction.NONE)
    }

    // 1. Permission is the hard gate. Checked before anything else.
    if (!input.hasForegroundPermission) {
        return if (input.isTracking) {
            TrackingRecovery(
                action = TrackingRecoveryAction.STOP,
                message = "Location permission was revoked, so tracking was stopped. " +
                    "Grant it again and tracking will resume.",
            )
        } else {
            TrackingRecovery(
                action = TrackingRecoveryAction.REQUEST_PERMISSION,
                message = "Tracking cannot start until location permission is granted.",
            )
        }
    }

    // 2. Permission is fine; the device-wide switch is the next blocker.
    if (input.trackingRequested && !input.locationServiceEnabled) {
        return TrackingRecovery(
            action = TrackingRecoveryAction.WAIT_FOR_LOCATION,
            message = "Location is switched off, so tracking did not start. Turn it on and return " +
                "to this screen — tracking will start by itself.",
        )
    }

    // 3. Requested, permitted, location on, but not running: this is the recovery the app was missing.
    if (input.trackingRequested && input.locationServiceEnabled && !input.isTracking) {
        return TrackingRecovery(
            action = TrackingRecoveryAction.START,
            message = "Location is available again — tracking resumed.",
        )
    }

    // 4. Running, but the user asked for background tracking without the extra grant. Honest note
    //    only: this is not an obstacle, the foreground subscription is genuine.
    if (input.isTracking && input.wantsBackground && !input.hasBackgroundPermission) {
        return TrackingRecovery(
            action = TrackingRecoveryAction.NONE,
            message = "Still tracking while the app is open. \"Allow all the time\" is needed to keep " +
                "going with the screen off.",
        )
    }

    return TrackingRecovery(TrackingRecoveryAction.NONE)
}
