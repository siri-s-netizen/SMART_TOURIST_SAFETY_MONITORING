package com.touristsafety.app.watch

import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.domain.model.OutboxStatus
import com.touristsafety.app.outbox.OutboxPayloads
import com.touristsafety.app.outbox.OutboxSyncer
import com.touristsafety.app.tracking.TrackingSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The brain of the **simulated** smartwatch.
 *
 * It consumes the same real tracking snapshot the dashboard uses (there is no separate, faked data
 * source) and turns it into a watch state:
 *
 *  * entering a restricted/dangerous zone fires the WARNING haptic **once** per entry (a repeated
 *    buzz every fix while walking along a boundary would be unusable);
 *  * the SOS button enqueues a durable SOS event and fires the distinct SOS haptic;
 *  * delivery status is derived from the real outbox, so with no backend configured it honestly
 *    reads "queued" rather than pretending to have reached an authority.
 *
 * All the interesting logic is here and has no Android dependency, so it is fully unit-tested with a
 * recording haptics fake and an in-memory outbox.
 */
class WatchController(
    private val scope: CoroutineScope,
    private val haptics: WatchHaptics,
    private val outboxRepository: OutboxRepository,
    private val syncer: OutboxSyncer,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(WatchUiState())
    val state: StateFlow<WatchUiState> = _state.asStateFlow()

    private var sosActive = false
    private var warningActive = false
    private var insideDanger = false
    private var latestReading: GeoReading? = null
    private var latestAlertSummary: String? = null

    /** The outbox row the delivery indicator currently describes; -1 until one is observed. */
    private var deliveryEventId: Long = -1L

    init {
        // Mirror the real outbox into the watch face's delivery indicator. This is the path that
        // catches changes made *elsewhere* - a background sync worker draining the queue, or a
        // previous run's event being retried.
        scope.launch {
            outboxRepository.observeLatestOfKind(OutboxKind.SOS).collect { event ->
                if (event == null) return@collect
                applyDelivery(event)
            }
        }
    }

    /** Projects one stored outbox row onto the watch face's delivery indicator. */
    private fun applyDelivery(event: OutboxEvent) {
        val sameEvent = deliveryEventId == event.id
        val observed = when (event.status) {
            OutboxStatus.SENT -> SosDeliveryState.DELIVERED
            OutboxStatus.FAILED -> SosDeliveryState.FAILED
            OutboxStatus.PENDING -> SosDeliveryState.QUEUED
        }
        val next = _state.value.sosDelivery.afterObserving(observed, sameEvent)
        deliveryEventId = event.id
        // A suppressed (stale) observation must not overwrite the detail text either, or the face
        // would read "Delivered to the authority dashboard." next to "Queued (offline)".
        if (next != observed) return
        _state.update { it.copy(sosDelivery = next, sosDeliveryDetail = deliveryDetail(event)) }
    }

    /** Fed from the real GPS pipeline; never from a synthetic coordinate. */
    fun onTrackingSnapshot(snapshot: TrackingSnapshot) {
        latestReading = snapshot.lastReading
        insideDanger = snapshot.overallStatus.isElevated
        if (insideDanger && !warningActive) {
            haptics.vibrate(HapticPattern.WARNING)
        }
        warningActive = insideDanger
        refresh()
    }

    fun onAlert(alert: GeoFenceAlert) {
        latestAlertSummary =
            "${alert.zoneType.displayName} · ${alert.eventType.displayName}: ${alert.zoneName}"
        refresh()
    }

    /** Raises a simulated SOS: distinct haptic, durable event, honest delivery status. */
    fun triggerSos() {
        val now = clock()
        val event = OutboxEvent(
            dedupeKey = OutboxPayloads.sosDedupeKey(now),
            kind = OutboxKind.SOS,
            payload = OutboxPayloads.sos(latestReading, now),
            createdAtMillis = now,
            origin = OutboxOrigin.SIMULATED_WATCH,
        )

        sosActive = true
        haptics.vibrate(HapticPattern.SOS)
        _state.update {
            it.copy(
                state = WatchState.SOS,
                sosDelivery = SosDeliveryState.QUEUED,
                sosDeliveryDetail = "SOS queued on the device.",
            )
        }

        scope.launch {
            outboxRepository.enqueue(event)
            syncer.flush()
            // Re-read the row and update the watch face *here*, rather than waiting for the mirror
            // above to be notified of the change.
            //
            // `flush()` has already finished by the time it returns, and the mirror only learns about
            // the new status when Room's invalidation tracker next runs the query. Reading back what
            // we just wrote removes that latency: the face must not still say "Queued on the device"
            // for an SOS that has been delivered. The mirror still does the job it is there for —
            // catching updates this call did not make.
            outboxRepository.findByDedupeKey(event.dedupeKey)?.let(::applyDelivery)
        }
    }

    fun acknowledgeSos() {
        sosActive = false
        refresh()
    }

    /** Manual "retry now" from the watch face. */
    fun retrySync() {
        scope.launch { syncer.flush() }
    }

    private fun refresh() {
        val reading = latestReading
        val watchState = when {
            sosActive -> WatchState.SOS
            insideDanger -> WatchState.WARNING
            else -> WatchState.SAFE
        }
        _state.update { current ->
            current.copy(
                state = watchState,
                latitude = reading?.point?.latitude,
                longitude = reading?.point?.longitude,
                accuracyMeters = reading?.accuracyMeters,
                locationSource = reading?.source,
                statusDetail = describe(watchState, reading),
                latestAlertSummary = latestAlertSummary,
            )
        }
    }

    private fun describe(state: WatchState, reading: GeoReading?): String = when {
        state == WatchState.SOS -> "SOS raised from the simulated watch."
        reading == null -> "Waiting for the first fix…"
        state == WatchState.WARNING -> "Inside a restricted or dangerous zone."
        else -> "Outside all restricted and dangerous zones."
    }

    private fun deliveryDetail(event: OutboxEvent): String = when (event.status) {
        OutboxStatus.SENT -> "Delivered to the authority dashboard."
        OutboxStatus.FAILED -> event.lastError ?: "The authority rejected this SOS."
        OutboxStatus.PENDING ->
            event.lastError ?: "Queued on the device (${event.attemptCount} attempts)."
    }
}
