package com.touristsafety.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.touristsafety.app.data.database.entity.OutboxEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxEventDao {

    /**
     * `IGNORE` on the unique `dedupe_key` index makes enqueue idempotent; the return value is -1
     * when the row already existed, which is how [com.touristsafety.app.data.repository.OutboxRepository]
     * reports "already queued".
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entity: OutboxEventEntity): Long

    @Query("SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEventEntity>

    @Query("SELECT * FROM outbox_events ORDER BY created_at DESC")
    fun observeAll(): Flow<List<OutboxEventEntity>>

    @Query("SELECT * FROM outbox_events WHERE kind = :kind ORDER BY created_at DESC LIMIT 1")
    fun observeLatestOfKind(kind: String): Flow<OutboxEventEntity?>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE kind = :kind AND status = 'PENDING'")
    fun observePendingCountOfKind(kind: String): Flow<Int>

    @Query("SELECT * FROM outbox_events WHERE dedupe_key = :key LIMIT 1")
    suspend fun findByDedupeKey(key: String): OutboxEventEntity?

    /** Records a transient failure without changing status, so the event is retried later. */
    @Query(
        "UPDATE outbox_events SET attempt_count = :attempts, last_attempt_at = :at, last_error = :error " +
            "WHERE id = :id",
    )
    suspend fun recordAttempt(id: Long, attempts: Int, at: Long, error: String?): Int

    @Query(
        "UPDATE outbox_events SET status = 'SENT', attempt_count = :attempts, last_attempt_at = :at, " +
            "last_error = NULL WHERE id = :id",
    )
    suspend fun markSent(id: Long, attempts: Int, at: Long): Int

    @Query(
        "UPDATE outbox_events SET status = 'FAILED', attempt_count = :attempts, last_attempt_at = :at, " +
            "last_error = :error WHERE id = :id",
    )
    suspend fun markFailed(id: Long, attempts: Int, at: Long, error: String?): Int

    @Query("DELETE FROM outbox_events WHERE status = 'SENT'")
    suspend fun deleteSent(): Int

    @Query("DELETE FROM outbox_events")
    suspend fun deleteAll(): Int
}
