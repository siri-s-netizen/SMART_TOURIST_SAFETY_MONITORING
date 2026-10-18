package com.touristsafety.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.touristsafety.app.data.database.entity.LocationRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: LocationRecordEntity): Long

    @Query("SELECT * FROM location_records ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LocationRecordEntity>>

    @Query("SELECT * FROM location_records ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<LocationRecordEntity?>

    @Query("SELECT * FROM location_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): LocationRecordEntity?

    @Query("SELECT COUNT(*) FROM location_records")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM location_records WHERE source = :source ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentBySource(source: String, limit: Int): List<LocationRecordEntity>

    /**
     * Trims the history table so long tracking sessions cannot grow the database without bound.
     * Keeps the most recent [keep] rows.
     */
    @Query(
        "DELETE FROM location_records WHERE id NOT IN " +
            "(SELECT id FROM location_records ORDER BY timestamp DESC LIMIT :keep)",
    )
    suspend fun pruneToLatest(keep: Int): Int

    @Query("DELETE FROM location_records WHERE source = :source")
    suspend fun deleteBySource(source: String): Int

    @Query("DELETE FROM location_records")
    suspend fun deleteAll(): Int
}
