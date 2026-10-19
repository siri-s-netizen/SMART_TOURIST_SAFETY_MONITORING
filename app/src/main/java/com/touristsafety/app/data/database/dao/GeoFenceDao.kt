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

    /**
     * The rows this phone downloaded from the authority, and only those.
     *
     * Scoped by `source` rather than filtered in Kotlin so a local zone can never be picked up by the
     * authority sync, no matter what the caller does with the result.
     */
    @Query("SELECT * FROM geo_fences WHERE source = 'AUTHORITY' ORDER BY updated_at DESC")
    suspend fun getAuthorityZones(): List<GeoFenceEntity>

    @Query("SELECT * FROM geo_fences WHERE source = 'AUTHORITY' ORDER BY updated_at DESC")
    fun observeAuthorityZones(): Flow<List<GeoFenceEntity>>

    @Query("SELECT COUNT(*) FROM geo_fences WHERE source = 'AUTHORITY'")
    fun observeAuthorityCount(): Flow<Int>

    /**
     * Withdraw a mirrored zone.
     *
     * The `source = 'AUTHORITY'` predicate is part of the statement, not a comment: the one path that
     * removes a zone automatically is the authority sync, and it must be incapable of deleting a zone
     * a person drew on this phone, whatever id it is handed.
     */
    @Query("DELETE FROM geo_fences WHERE id = :id AND source = 'AUTHORITY'")
    suspend fun deleteAuthorityById(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: GeoFenceEntity): Long

    @Update
    suspend fun update(entity: GeoFenceEntity): Int

    @Query("UPDATE geo_fences SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM geo_fences WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** Delete every row, whatever its provenance. Used by tests and by a full local reset. */
    @Query("DELETE FROM geo_fences")
    suspend fun deleteAll(): Int

    /**
     * Delete the zones this phone drew, and only those.
     *
     * `source = 'LOCAL'` rather than `source != 'AUTHORITY'` on purpose: a row whose `source` this build
     * does not recognise is neither the tourist's to delete nor the sync's to prune, so it is preserved
     * by both paths. Deleting something the app cannot classify would be the destructive reading of an
     * unknown value.
     */
    @Query("DELETE FROM geo_fences WHERE source = 'LOCAL'")
    suspend fun deleteLocal(): Int
}
