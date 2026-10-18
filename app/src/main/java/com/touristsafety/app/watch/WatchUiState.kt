package com.touristsafety.app.watch

import com.touristsafety.app.domain.model.LocationSource

/** The three states the simulated watch can show. */
enum class WatchState {
    SAFE,
    WARNING,
    SOS,
}

/** Where an SOS raised from the watch has got to. */
enum class SosDeliveryState {
    IDLE,
    QUEUED,
    DELIVERED,
    FAILED,
    ;

    /** True once the outbox has settled on an outcome for this event. */
    val isTerminal: Boolean
        get() = this == DELIVERED || this == FAILED
}

/**
 * The delivery status to show after observing [observed] for the same outbox row.
 *
 * The watch face is fed by two asynchronous sources: the direct re-read performed right after a
 * flush, and the mirror that follows the outbox flow. Those can interleave, and Room's change
 * notifications carry no ordering guarantee relative to a write the app itself just made. A settled
 * delivery outcome must not be undone by a "still queued" observation that was read earlier — the
 * face would show "Queued (offline)" for an SOS that was delivered — so a terminal status is sticky
 * **for its own event**, while a newly raised SOS (a different outbox row) still resets the
 * indicator from scratch.
 */
internal fun SosDeliveryState.afterObserving(
    observed: SosDeliveryState,
    sameEvent: Boolean,
): SosDeliveryState = if (sameEvent && isTerminal && !observed.isTerminal) this else observed

/**
 * Everything the simulated watch face renders.
 *
 * [simulated] is a constant `true` and is shown on the face: the project has no physical wearable
 * and this watch must never be mistaken for real hardware.
 */
data class WatchUiState(
    val state: WatchState = WatchState.SAFE,
    val simulated: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationSource: LocationSource? = null,
    val statusDetail: String = "Waiting for the first fix…",
    val latestAlertSummary: String? = null,
    val sosDelivery: SosDeliveryState = SosDeliveryState.IDLE,
    val sosDeliveryDetail: String? = null,
)
