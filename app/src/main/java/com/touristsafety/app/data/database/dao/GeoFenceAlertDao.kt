package com.touristsafety.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.touristsafety.app.data.database.entity.GeoFenceAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoFenceAlertDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: GeoFenceAlertEntity): Long

    @Query("SELECT * FROM geo_fence_alerts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<GeoFenceAlertEntity>>

    @Query("SELECT * FROM geo_fence_alerts ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<GeoFenceAlertEntity>>

    @Query("SELECT * FROM geo_fence_alerts WHERE acknowledged = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun observeUnacknowledged(limit: Int): Flow<List<GeoFenceAlertEntity>>

    @Query("SELECT * FROM geo_fence_alerts ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<GeoFenceAlertEntity?>

    @Query("SELECT COUNT(*) FROM geo_fence_alerts")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM geo_fence_alerts WHERE acknowledged = 0")
    fun observeUnacknowledgedCount(): Flow<Int>

    @Query("UPDATE geo_fence_alerts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long): Int

    @Query("UPDATE geo_fence_alerts SET acknowledged = 1 WHERE acknowledged = 0")
    suspend fun acknowledgeAll(): Int

    @Query("DELETE FROM geo_fence_alerts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM geo_fence_alerts WHERE source = :source")
    suspend fun deleteBySource(source: String): Int

    @Query("DELETE FROM geo_fence_alerts")
    suspend fun deleteAll(): Int
}
