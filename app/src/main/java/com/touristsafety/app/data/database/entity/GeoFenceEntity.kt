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

    /**
     * [com.touristsafety.app.domain.model.ZoneSource.storageKey].
     *
     * Added in schema version 5. `NOT NULL DEFAULT 'LOCAL'` is deliberate: a row that existed before
     * shared zones were possible *was* a local zone, so the default states a fact rather than
     * inventing one. Declared last so the physical column order matches what
     * `ALTER TABLE ... ADD COLUMN` produces on an upgraded device.
     */
    @ColumnInfo(name = "source", defaultValue = "'LOCAL'")
    val source: String = "LOCAL",

    /** The authority's zone id, for a zone downloaded from `GET /zones`. Null for a local zone. */
    @ColumnInfo(name = "authority_zone_id")
    val authorityZoneId: Int? = null,

    /** The authority's `revision` when this polygon was downloaded. Null for a local zone. */
    @ColumnInfo(name = "authority_revision")
    val authorityRevision: Int? = null,
)
