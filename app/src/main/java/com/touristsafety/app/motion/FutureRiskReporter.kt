package com.touristsafety.app.motion

import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.outbox.OutboxPayloads

/**
 * When a future-risk prediction is worth telling the authority about.
 *
 * The model evaluates roughly every 0.64 s, which is far too often to upload: the authority would
 * receive an unbounded trickle of near-identical rows and the dashboard would be useless. This policy
 * reduces that stream to the two events a person actually needs:
 *
 * * a prediction that is **elevated** (MODERATE or HIGH) - and immediately again if it *escalates*;
 * * one **clearing** record when an elevated prediction falls back to LOW, so the dashboard does not
 *   keep showing a stale warning forever.
 *
 * Nothing is uploaded while the risk is LOW and always has been, and nothing is uploaded for an
 * UNAVAILABLE prediction - "we could not tell" is not a prediction, and storing it as one would let
 * the dashboard imply the model made a call it did not make.
 *
 * Nothing is uploaded for a prediction made by a watch that has since **stopped**, either. Switching
 * the watch off (or signing out) freezes the last band where it was; that frozen value is a reading
 * about the past, not a forecast about now, and a "cleared" record sent because a sensor was switched
 * off would tell the authority a tourist is fine for a reason the model never gave.
 */
data class FutureRiskReportPolicy(
    /** Minimum gap between two routine refreshes of an unchanged band. */
    val refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS,
) {

    /** Why a report was or was not sent. Kept as a value so tests assert behaviour, not strings. */
    enum class Reason {
        FIRST_ELEVATED,
        ESCALATED,
        REFRESHED,
        CLEARED,
        NO_PREDICTION,
        NOT_ELEVATED,
        NOTHING_TO_CLEAR,
        WITHIN_INTERVAL,
        STALE,

        /**
         * The watch is not running, so the last band is a frozen reading rather than a prediction
         * about now. Reporting it would tell the authority something the model has stopped saying —
         * and if the frozen band were LOW it would send a "cleared" record for a watch that was
         * merely switched off, which is worse than saying nothing.
         */
        NOT_MONITORING,
    }

    data class Decision(val send: Boolean, val reason: Reason)

    /**
     * Decide whether [state] should be queued.
     *
     * @param lastReportedBand the band of the last summary actually queued, or `null` if none has been.
     * @param lastReportedAtMillis when that summary was queued.
     */
    fun decide(
        state: FutureRiskState,
        nowMillis: Long,
        lastReportedBand: FutureRiskBand?,
        lastReportedAtMillis: Long?,
    ): Decision {
        // Checked before anything else: with the watch stopped there is no live prediction to judge,
        // so none of the rules below (including "cleared") may fire.
        if (!state.isMonitoring) return Decision(false, Reason.NOT_MONITORING)
        if (!state.hasPrediction) return Decision(false, Reason.NO_PREDICTION)
        // A frozen band from a sensor stream that stopped is not news about the tourist.
        if (state.isStale(nowMillis)) return Decision(false, Reason.STALE)

        val elapsed = lastReportedAtMillis?.let { nowMillis - it }
        // A device whose clock jumped backwards must not suppress reporting forever.
        val withinInterval = elapsed != null && elapsed >= 0 && elapsed < refreshIntervalMillis

        val elevated = state.band == FutureRiskBand.MODERATE || state.band == FutureRiskBand.HIGH
        if (elevated) {
            if (lastReportedBand == null) return Decision(true, Reason.FIRST_ELEVATED)
            if (state.band.ordinal > lastReportedBand.ordinal) return Decision(true, Reason.ESCALATED)
            return if (withinInterval) Decision(false, Reason.WITHIN_INTERVAL) else Decision(true, Reason.REFRESHED)
        }

        // LOW: only interesting if we previously told the authority otherwise.
        if (lastReportedBand == null || lastReportedBand == FutureRiskBand.LOW) {
            return Decision(false, Reason.NOTHING_TO_CLEAR)
        }
        return if (withinInterval) Decision(false, Reason.WITHIN_INTERVAL) else Decision(true, Reason.CLEARED)
    }

    companion object {
        /** Two minutes: often enough to follow a situation, rare enough to be readable. */
        const val DEFAULT_REFRESH_INTERVAL_MILLIS: Long = 120_000L
    }
}

/**
 * Queues future-risk summaries for the authority, subject to a [FutureRiskReportPolicy].
 *
 * It mirrors [com.touristsafety.app.tracking.PositionReporter]: the policy decides *whether*, this
 * class does the queueing, and the last-sent band is remembered in memory. Losing that across a
 * process restart only means one extra summary after a restart, which is harmless.
 *
 * It never touches the SOS path. A risk prediction, however high, does not raise an SOS or contact
 * anyone: the tourist still has to press the button, and that is a design decision, not an oversight.
 */
class FutureRiskReporter(
    private val outboxRepository: OutboxRepository,
    private val policy: FutureRiskReportPolicy = FutureRiskReportPolicy(),
    private val isConfigured: suspend () -> Boolean = { true },
) {

    private var lastReportedBand: FutureRiskBand? = null
    private var lastReportedAtMillis: Long? = null

    /**
     * Offers one prediction to the reporter.
     *
     * @return true when a summary was actually queued.
     */
    suspend fun onPrediction(
        state: FutureRiskState,
        nowMillis: Long,
        modelWindowSamples: Int = FutureRiskFeatures.MIN_SAMPLES,
    ): Boolean {
        val decision = policy.decide(state, nowMillis, lastReportedBand, lastReportedAtMillis)
        if (!decision.send) return false
        // With no authority address there is nothing to send *to*. The bookkeeping is deliberately
        // left untouched so the first summary after an address is entered goes out immediately.
        if (!isConfigured()) return false

        val probability = state.probability
        val event = OutboxEvent(
            dedupeKey = OutboxPayloads.futureRiskDedupeKey(nowMillis),
            kind = OutboxKind.RISK,
            payload = OutboxPayloads.futureRisk(
                OutboxPayloads.FutureRiskSummary(
                    modelVersion = state.modelVersion ?: "unknown",
                    horizonSeconds = state.horizonSeconds,
                    riskBand = state.band.storageKey,
                    // Only a calibrated probability is ever shared. A raw forest vote is not one.
                    probability = probability,
                    windowSamples = modelWindowSamples,
                    observedSamples = state.dataQuality.samples,
                    dataQualityOk = state.dataQuality.ok,
                    dataQualityReason = state.dataQuality.reason,
                    factors = state.factors.map {
                        OutboxPayloads.FutureRiskSummaryFactor(label = it.label, detail = it.detail)
                    },
                    timestampMillis = nowMillis,
                ),
            ),
            createdAtMillis = nowMillis,
            origin = OutboxOrigin.ON_DEVICE_MODEL,
        )
        val queued = outboxRepository.enqueue(event)
        lastReportedBand = state.band
        lastReportedAtMillis = nowMillis
        return queued
    }

    /** The band of the last summary queued, or null. Exposed for tests. */
    fun lastBand(): FutureRiskBand? = lastReportedBand
}
