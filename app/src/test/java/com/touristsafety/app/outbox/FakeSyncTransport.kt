package com.touristsafety.app.outbox

import com.touristsafety.app.domain.model.OutboxEvent

/**
 * A transport whose behaviour the test controls, so "offline", "online" and "rejected" can be
 * simulated without any network or server.
 */
class FakeSyncTransport(
    var result: (OutboxEvent) -> DeliveryResult = { DeliveryResult.Delivered(acknowledgementId = "ack") },
) : SyncTransport {

    override val name: String = "fake"

    var calls: Int = 0
        private set

    val delivered = mutableListOf<OutboxEvent>()

    override suspend fun deliver(event: OutboxEvent): DeliveryResult {
        calls++
        val outcome = result(event)
        if (outcome is DeliveryResult.Delivered) delivered += event
        return outcome
    }
}
