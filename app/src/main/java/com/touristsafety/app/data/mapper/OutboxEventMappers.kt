package com.touristsafety.app.data.mapper

import com.touristsafety.app.data.database.entity.OutboxEventEntity
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.domain.model.OutboxStatus

/**
 * Enum <-> storage-key mapping lives here, not in the entity, so a future enum value can be renamed
 * without invalidating rows already on the tourist's device.
 */
fun OutboxEventEntity.toDomain(): OutboxEvent = OutboxEvent(
    id = id,
    dedupeKey = dedupeKey,
    kind = OutboxKind.fromStorage(kind),
    payload = payload,
    createdAtMillis = createdAt,
    attemptCount = attemptCount,
    lastAttemptAtMillis = lastAttemptAt,
    status = OutboxStatus.fromStorage(status),
    origin = OutboxOrigin.fromStorage(origin),
    lastError = lastError,
    ownerSubjectId = ownerSubjectId,
)

fun OutboxEvent.toEntity(): OutboxEventEntity = OutboxEventEntity(
    id = id,
    dedupeKey = dedupeKey,
    kind = kind.storageKey,
    payload = payload,
    createdAt = createdAtMillis,
    attemptCount = attemptCount,
    lastAttemptAt = lastAttemptAtMillis,
    status = status.storageKey,
    origin = origin.storageKey,
    lastError = lastError,
    ownerSubjectId = ownerSubjectId,
)
