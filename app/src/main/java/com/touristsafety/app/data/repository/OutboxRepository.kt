package com.touristsafety.app.data.repository

import com.touristsafety.app.data.database.dao.OutboxEventDao
import com.touristsafety.app.data.mapper.toDomain
import com.touristsafety.app.data.mapper.toEntity
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The durable outbox: nothing leaves the device directly, everything is queued here first.
 *
 * Keeping the queue in Room (rather than in memory) is what makes "store now, send later when the
 * network returns" survive the app being killed in a tunnel with no signal.
 */
class OutboxRepository(
    private val dao: OutboxEventDao,
) {

    /** @return true if this is a new event, false if an event with the same [dedupeKey] existed. */
    suspend fun enqueue(event: OutboxEvent): Boolean = dao.enqueue(event.toEntity()) != -1L

    suspend fun pending(limit: Int = DEFAULT_BATCH): List<OutboxEvent> =
        dao.pending(limit).map { it.toDomain() }

    fun observeAll(): Flow<List<OutboxEvent>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    fun observePendingCount(kind: OutboxKind): Flow<Int> =
        dao.observePendingCountOfKind(kind.storageKey)

    fun observeLatestOfKind(kind: OutboxKind): Flow<OutboxEvent?> =
        dao.observeLatestOfKind(kind.storageKey).map { it?.toDomain() }

    suspend fun findByDedupeKey(key: String): OutboxEvent? = dao.findByDedupeKey(key)?.toDomain()

    suspend fun recordAttempt(id: Long, attempts: Int, atMillis: Long, error: String?): Boolean =
        dao.recordAttempt(id, attempts, atMillis, error) > 0

    suspend fun markSent(id: Long, attempts: Int, atMillis: Long): Boolean =
        dao.markSent(id, attempts, atMillis) > 0

    suspend fun markFailed(id: Long, attempts: Int, atMillis: Long, error: String?): Boolean =
        dao.markFailed(id, attempts, atMillis, error) > 0

    /** Removes delivered rows so the queue cannot grow without bound. */
    suspend fun purgeSent(): Int = dao.deleteSent()

    suspend fun clear(): Int = dao.deleteAll()

    companion object {
        const val DEFAULT_BATCH: Int = 50
    }
}
