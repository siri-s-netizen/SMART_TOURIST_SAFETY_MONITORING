package com.touristsafety.app.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS (or simulated) fix.
 *
 * [source] is stored on every row so that a simulated coordinate can never be presented as an
 * actual GPS reading — not in the UI, not in an export, not after a restart.
 */
@Entity(
    tableName = "location_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["source"]),
    ],
)
data class LocationRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    /** Horizontal accuracy radius in metres. */
    @ColumnInfo(name = "accuracy")
    val accuracy: Float,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** [com.touristsafety.app.domain.model.LocationSource.storageKey]: GPS or SIMULATION. */
    @ColumnInfo(name = "source")
    val source: String,
)
