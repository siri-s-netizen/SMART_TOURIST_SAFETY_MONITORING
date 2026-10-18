package com.touristsafety.app.outbox

import com.touristsafety.app.data.repository.OutboxRepository

/** A summary of one drain of the queue; returned so callers/tests can assert on real behaviour. */
data class SyncReport(
    val inspected: Int = 0,
    val delivered: Int = 0,
    val retried: Int = 0,
    val rejected: Int = 0,
)

/**
 * Drains the offline outbox through a [SyncTransport].
 *
 * Deliberately small and dependency-light so it can be unit-tested with a fake transport and a fake
 * clock. Duplicate protection lives in the unique `dedupe_key` index (see
 * [com.touristsafety.app.data.database.dao.OutboxEventDao.enqueue]), so a retry storm cannot create
 * duplicate events even if this method runs concurrently.
 */
class OutboxSyncer(
    private val repository: OutboxRepository,
    private val transport: SyncTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun flush(limit: Int = OutboxRepository.DEFAULT_BATCH): SyncReport {
        val batch = repository.pending(limit)
        var delivered = 0
        var retried = 0
        var rejected = 0

        for (event in batch) {
            val attempts = event.attemptCount + 1
            when (val result = transport.deliver(event)) {
                is DeliveryResult.Delivered -> {
                    repository.markSent(event.id, attempts, clock())
                    delivered++
                }

                is DeliveryResult.Retry -> {
                    // Stays PENDING on purpose: the next flush will try again.
                    repository.recordAttempt(event.id, attempts, clock(), result.reason)
                    retried++
                }

                is DeliveryResult.Rejected -> {
                    repository.markFailed(event.id, attempts, clock(), result.reason)
                    rejected++
                }
            }
        }

        return SyncReport(
            inspected = batch.size,
            delivered = delivered,
            retried = retried,
            rejected = rejected,
        )
    }
}
