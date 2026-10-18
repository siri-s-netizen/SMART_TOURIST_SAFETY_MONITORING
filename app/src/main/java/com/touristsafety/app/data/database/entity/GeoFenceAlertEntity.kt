package com.touristsafety.app.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recorded zone crossing.
 *
 * [zoneName] and [zoneType] are copied in rather than joined, so history stays readable and
 * accurate even after the zone is renamed, retyped or deleted — an audit trail should record what
 * was true at the time, not what the zone looks like now.
 */
@Entity(
    tableName = "geo_fence_alerts",
    indices = [
        Index(value = ["zone_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["acknowledged"]),
    ],
)
data class GeoFenceAlertEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "zone_id")
    val zoneId: Long,

    @ColumnInfo(name = "zone_name")
    val zoneName: String,

    @ColumnInfo(name = "zone_type")
    val zoneType: String,

    /** [com.touristsafety.app.domain.model.AlertEventType.storageKey]: ENTRY or EXIT. */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "acknowledged", defaultValue = "0")
    val acknowledged: Boolean,

    /** GPS or SIMULATION, so demo data is always distinguishable from real data. */
    @ColumnInfo(name = "source")
    val source: String,
)
