package com.touristsafety.app.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved geo-fence.
 *
 * [vertices] holds the ordered polygon via [com.touristsafety.app.data.database.PolygonCodec].
 * [vertexCount] is denormalised purely so that cheap diagnostics ("which zones are broken?") do not
 * need to decode every row.
 */
@Entity(
    tableName = "geo_fences",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["updated_at"]),
    ],
)
data class GeoFenceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String,

    /** [com.touristsafety.app.domain.model.ZoneType.storageKey]. */
    @ColumnInfo(name = "zone_type")
    val zoneType: String,

    /** Ordered polygon ring, `lat,lon;lat,lon;...`. */
    @ColumnInfo(name = "vertices")
    val vertices: String,

    @ColumnInfo(name = "vertex_count")
    val vertexCount: Int,

    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
