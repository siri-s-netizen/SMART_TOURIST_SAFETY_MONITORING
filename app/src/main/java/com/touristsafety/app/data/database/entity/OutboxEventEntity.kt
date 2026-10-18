package com.touristsafety.app.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The durable offline outbox.
 *
 * The unique index on [dedupeKey] is the duplicate-prevention mechanism: an `INSERT OR IGNORE`
 * enqueues a given crossing exactly once, however many times a retry loop runs.
 */
@Entity(
    tableName = "outbox_events",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index(value = ["status"]),
        Index(value = ["created_at"]),
    ],
)
data class OutboxEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    /** [com.touristsafety.app.domain.model.OutboxKind.storageKey]. */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "payload")
    val payload: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "attempt_count", defaultValue = "0")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    /** [com.touristsafety.app.domain.model.OutboxStatus.storageKey]. */
    @ColumnInfo(name = "status")
    val status: String,

    /** [com.touristsafety.app.domain.model.OutboxOrigin.storageKey]. */
    @ColumnInfo(name = "origin")
    val origin: String,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
)
