package com.touristsafety.app.data.mapper

import com.touristsafety.app.data.database.PolygonCodec
import com.touristsafety.app.data.database.entity.GeoFenceAlertEntity
import com.touristsafety.app.data.database.entity.GeoFenceEntity
import com.touristsafety.app.data.database.entity.LocationRecordEntity
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType

/**
 * Entity <-> domain conversion.
 *
 * Keeping this in one file means the persistence format is touched in exactly one place: the
 * repositories and the engine only ever see domain types.
 */

fun GeoFenceEntity.toDomain(): GeoFenceZone = GeoFenceZone(
    id = id,
    name = name,
    description = description,
    type = ZoneType.fromStorage(zoneType),
    vertices = PolygonCodec.decode(vertices),
    enabled = enabled,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
)

fun GeoFenceZone.toEntity(): GeoFenceEntity = GeoFenceEntity(
    id = id,
    name = name,
    description = description,
    zoneType = type.storageKey,
    vertices = PolygonCodec.encode(vertices),
    vertexCount = vertices.size,
    enabled = enabled,
    createdAt = createdAtMillis,
    updatedAt = updatedAtMillis,
)

fun LocationRecordEntity.toDomain(): GeoReading = GeoReading(
    point = GeoPoint(latitude, longitude),
    accuracyMeters = accuracy,
    timestampMillis = timestamp,
    source = LocationSource.fromStorage(source),
)

fun GeoReading.toEntity(): LocationRecordEntity = LocationRecordEntity(
    latitude = point.latitude,
    longitude = point.longitude,
    accuracy = accuracyMeters,
    timestamp = timestampMillis,
    source = source.storageKey,
)

fun GeoFenceAlertEntity.toDomain(): GeoFenceAlert = GeoFenceAlert(
    id = id,
    zoneId = zoneId,
    zoneName = zoneName,
    zoneType = ZoneType.fromStorage(zoneType),
    eventType = AlertEventType.fromStorage(eventType),
    point = GeoPoint(latitude, longitude),
    accuracyMeters = accuracy,
    timestampMillis = timestamp,
    acknowledged = acknowledged,
    source = LocationSource.fromStorage(source),
)

fun GeoFenceAlert.toEntity(): GeoFenceAlertEntity = GeoFenceAlertEntity(
    id = id,
    zoneId = zoneId,
    zoneName = zoneName,
    zoneType = zoneType.storageKey,
    eventType = eventType.storageKey,
    latitude = point.latitude,
    longitude = point.longitude,
    accuracy = accuracyMeters,
    timestamp = timestampMillis,
    acknowledged = acknowledged,
    source = source.storageKey,
)
