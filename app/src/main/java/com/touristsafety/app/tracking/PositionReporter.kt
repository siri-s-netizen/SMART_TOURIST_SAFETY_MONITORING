package com.touristsafety.app.tracking

import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.outbox.OutboxPayloads

/**
 * How often a routine position report is queued for the authority.
 *
 * A fix arrives every few seconds; the authority only needs to know roughly where the tourist is, so
 * reporting every fix would spend battery and data on precision nobody reads. A report is queued when
 * **either** the interval has elapsed **or** the tourist has moved at least [minDistanceMeters] — the
 * second rule is what keeps a moving tourist's dot current between interval ticks, and the first is
 * what keeps a stationary tourist visible at all.
 */
data class PositionReportPolicy(
    val enabled: Boolean = true,
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val minDistanceMeters: Double = DEFAULT_MIN_DISTANCE_METERS,
) {

    /**
     * True when [next] should be queued, given the last fix that was actually reported.
     *
     * The very first real fix is always reported, so a tourist who starts tracking and then stands
     * still still appears on the authority's map.
     */
    fun shouldReport(previous: GeoReading?, next: GeoReading): Boolean {
        if (!enabled) return false
        val last = previous ?: return true
        // A device whose clock moved backwards must not suppress reports forever.
        val elapsed = next.timestampMillis - last.timestampMillis
        if (elapsed < 0) return true
        if (elapsed >= intervalMillis) return true
        return last.point.flatDistanceMeters(next.point) >= minDistanceMeters
    }

    companion object {
        /** One minute: frequent enough to track a walk, cheap enough to leave on all day. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 60_000L

        /** ~100 m is a city block; below that the dot has barely moved. */
        const val DEFAULT_MIN_DISTANCE_METERS: Double = 100.0

        /** Intervals offered by the Settings screen. */
        val INTERVAL_CHOICES_MILLIS: List<Long> =
            listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
    }
}

/**
 * Turns real-GPS fixes into queued [OutboxKind.POSITION] events, subject to a [PositionReportPolicy].
 *
 * It is deliberately **not** wired to the simulator: a simulated coordinate must never be delivered
 * to the authority as the tourist's location (see [com.touristsafety.app.simulation.SimulationController],
 * which keeps its own, separate pipeline for exactly this reason).
 *
 * The last *reported* fix is remembered in memory. Losing it across a process restart only means one
 * extra report after a restart, which is harmless.
 */
class PositionReporter(
    private val outboxRepository: OutboxRepository,
    private val policy: () -> PositionReportPolicy = { PositionReportPolicy() },
    private val isConfigured: suspend () -> Boolean = { true },
) {

    private var lastReported: GeoReading? = null

    /**
     * Offers one real-GPS fix to the reporter.
     *
     * @return true when a report was actually queued. A `false` is not an error: it means the policy
     *   suppressed this fix, no authority address is configured yet, or the fix's coordinates were
     *   not valid WGS-84 (in which case queueing it would earn a `REJECTED` from the server).
     */
    suspend fun onReading(reading: GeoReading): Boolean {
        if (reading.isSimulated) return false
        if (!reading.point.hasValidRange) return false
        if (!policy().shouldReport(lastReported, reading)) return false
        // With no address there is nothing to report *to*; keep lastReported unchanged so the first
        // report after an address is entered goes out immediately rather than up to an interval later.
        if (!isConfigured()) return false

        val event = OutboxEvent(
            dedupeKey = OutboxPayloads.positionDedupeKey(reading.timestampMillis),
            kind = OutboxKind.POSITION,
            payload = OutboxPayloads.position(reading, reading.timestampMillis),
            createdAtMillis = reading.timestampMillis,
            origin = OutboxOrigin.GPS,
        )
        val queued = outboxRepository.enqueue(event)
        lastReported = reading
        return queued
    }

    /** The fix the reporter last queued, or null if none has been queued yet. Exposed for tests. */
    fun lastReportedReading(): GeoReading? = lastReported
}
