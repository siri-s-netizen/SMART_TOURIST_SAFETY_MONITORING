package com.touristsafety.app.domain.model

/**
 * What an outbox row represents.
 *
 * An SOS is deliberately a different kind from a routine zone crossing: it should be prioritised,
 * acknowledged and audited differently once an authority backend exists.
 */
enum class OutboxKind(val storageKey: String, val displayName: String) {
    ZONE_ALERT("ZONE_ALERT", "Zone alert"),
    SOS("SOS", "SOS"),

    /**
     * A routine position report: where the tourist is, so the authority's map shows a moving dot
     * instead of only the place the last alert or SOS happened to be raised.
     *
     * Position reports are high-volume compared with a zone crossing, so they are throttled by
     * [com.touristsafety.app.tracking.PositionReportPolicy] and only ever queued for **real GPS**
     * fixes — a simulated reading is never presented to the authority as the tourist's location.
     */
    POSITION("POSITION", "Position report"),

    /**
     * The whole emergency-contact list, replaced on the authority in one transaction.
     *
     * It travels through the outbox rather than being PUT directly so a contact list edited in a
     * valley is still uploaded later, exactly like an SOS.
     */
    CONTACTS("CONTACTS", "Emergency contacts"),

    /**
     * A **summary of a trained future-risk prediction**, uploaded so the authority dashboard can see
     * what the tourist's phone forecast.
     *
     * Deliberately its own kind: a prediction is neither an incident nor an SOS, and the dashboard
     * must be able to show it without letting anyone mistake a forecast for something that happened.
     * It is a *summary* — a band, a calibrated probability and the model version — not a raw sensor
     * stream, and only elevated or clearing predictions are ever queued
     * ([com.touristsafety.app.motion.FutureRiskReportPolicy]).
     */
    RISK("RISK", "Future-risk prediction"),
    ;

    companion object {
        fun fromStorage(value: String?): OutboxKind =
            entries.firstOrNull { it.storageKey == value } ?: ZONE_ALERT
    }
}

/**
 * Delivery lifecycle.
 *
 * [PENDING] is retried indefinitely (that is the point of an offline queue); [SENT] is terminal;
 * [FAILED] means the transport permanently rejected the event so retrying forever would be wrong.
 */
enum class OutboxStatus(val storageKey: String) {
    PENDING("PENDING"),
    SENT("SENT"),
    FAILED("FAILED"),
    ;

    companion object {
        fun fromStorage(value: String?): OutboxStatus =
            entries.firstOrNull { it.storageKey == value } ?: PENDING
    }
}

/**
 * Where an event originated.
 *
 * [SIMULATED_WATCH] exists so a demo triggered from the simulated smartwatch can never be mistaken
 * for a real SOS from physical hardware.
 */
enum class OutboxOrigin(val storageKey: String, val displayName: String, val isSimulated: Boolean) {
    GPS("GPS", "Actual GPS", false),
    SIMULATION("SIMULATION", "Simulation", true),
    SIMULATED_WATCH("SIMULATED_WATCH", "Simulated watch", true),

    /**
     * Produced by a model running on the phone itself.
     *
     * Not simulated: the future-risk model consumes the **real** accelerometer, so its output is a
     * genuine measurement of the tourist, not a demo. It is a separate origin from [GPS] because it
     * carries no coordinates at all.
     */
    ON_DEVICE_MODEL("ON_DEVICE_MODEL", "On-device model", false),
    ;

    companion object {
        fun fromStorage(value: String?): OutboxOrigin =
            entries.firstOrNull { it.storageKey == value } ?: GPS
    }
}

/**
 * One durable, queued outbound event.
 *
 * [dedupeKey] carries a unique SQLite index, so enqueueing the same crossing twice is a no-op. That
 * is what prevents duplicate events when a retry loop runs against a flaky network.
 */
data class OutboxEvent(
    val id: Long = 0L,
    val dedupeKey: String,
    val kind: OutboxKind,
    val payload: String,
    val createdAtMillis: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val origin: OutboxOrigin = OutboxOrigin.GPS,
    val lastError: String? = null,
) {
    val isPending: Boolean get() = status == OutboxStatus.PENDING
}
