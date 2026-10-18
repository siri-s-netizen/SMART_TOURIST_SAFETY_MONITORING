package com.touristsafety.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.touristsafety.app.data.database.entity.GeoFenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoFenceDao {

    @Query("SELECT * FROM geo_fences ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<GeoFenceEntity>>

    @Query("SELECT * FROM geo_fences WHERE enabled = 1 ORDER BY updated_at DESC")
    fun observeEnabled(): Flow<List<GeoFenceEntity>>

    @Query("SELECT * FROM geo_fences ORDER BY updated_at DESC")
    suspend fun getAll(): List<GeoFenceEntity>

    /** Hot path for the geo-fence engine: only enabled zones are ever tested. */
    @Query("SELECT * FROM geo_fences WHERE enabled = 1 ORDER BY updated_at DESC")
    suspend fun getEnabled(): List<GeoFenceEntity>

    @Query("SELECT * FROM geo_fences WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): GeoFenceEntity?

    @Query("SELECT COUNT(*) FROM geo_fences")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: GeoFenceEntity): Long

    @Update
    suspend fun update(entity: GeoFenceEntity): Int

    @Query("UPDATE geo_fences SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM geo_fences WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM geo_fences")
    suspend fun deleteAll(): Int
}
