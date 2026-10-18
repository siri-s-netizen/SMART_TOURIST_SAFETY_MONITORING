package com.touristsafety.app.outbox

import com.touristsafety.app.domain.model.OutboxEvent

/**
 * The outcome of one delivery attempt.
 *
 * The three cases map onto the lifecycle of [com.touristsafety.app.domain.model.OutboxStatus]:
 * [Delivered] -> SENT, [Retry] -> stays PENDING (increments the attempt counter), [Rejected] ->
 * FAILED. Distinguishing a *transient* failure from a *permanent* one is what lets the queue retry a
 * dead network forever without retrying a payload the server will never accept.
 */
sealed interface DeliveryResult {
    data class Delivered(val acknowledgementId: String? = null) : DeliveryResult
    data class Retry(val reason: String) : DeliveryResult
    data class Rejected(val reason: String) : DeliveryResult
}

/**
 * Where queued events go once there is connectivity.
 *
 * An interface so the sync logic can be tested with an in-memory fake and so a real authority
 * endpoint can be dropped in later without touching the queue.
 */
interface SyncTransport {
    val name: String

    suspend fun deliver(event: OutboxEvent): DeliveryResult
}

/**
 * The transport used in this build.
 *
 * There is **no backend server** in this repository, and the project rules forbid faking server
 * responses to make the UI look finished. So this transport is honest: it reports a transient
 * failure, which leaves every event queued and visibly pending on the device. When a real endpoint
 * (or an authority dashboard) is configured, only this class needs to change.
 */
class NoBackendTransport : SyncTransport {

    override val name: String = "none"

    override suspend fun deliver(event: OutboxEvent): DeliveryResult = DeliveryResult.Retry(
        reason = "No authority backend is configured in this build; the event is queued and will " +
            "be retried when one is.",
    )
}
