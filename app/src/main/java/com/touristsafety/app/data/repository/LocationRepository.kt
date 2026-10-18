package com.touristsafety.app.data.repository

import com.touristsafety.app.data.database.dao.LocationRecordDao
import com.touristsafety.app.data.mapper.toDomain
import com.touristsafety.app.data.mapper.toEntity
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Offline GPS (and simulation) history. */
class LocationRepository(
    private val dao: LocationRecordDao,
) {

    suspend fun record(reading: GeoReading): Long = dao.insert(reading.toEntity())

    fun observeRecent(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<GeoReading>> =
        dao.observeRecent(limit).map { entities -> entities.map { it.toDomain() } }

    fun observeLatest(): Flow<GeoReading?> = dao.observeLatest().map { it?.toDomain() }

    suspend fun latest(): GeoReading? = dao.getLatest()?.toDomain()

    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Bounds database growth during a long trip. Called periodically by the tracking pipeline
     * rather than on every insert.
     */
    suspend fun pruneToLatest(keep: Int = DEFAULT_HISTORY_LIMIT): Int = dao.pruneToLatest(keep)

    suspend fun clear() {
        dao.deleteAll()
    }

    /**
     * Removes only rows that came from the simulator. Real GPS history is filtered out by `source`,
     * so clearing the demo can never destroy a real track.
     */
    suspend fun clearSimulated(): Int = dao.deleteBySource(LocationSource.SIMULATION.storageKey)

    companion object {
        /** ~1 fix every 5s for 5 hours. Enough to reconstruct a walk without bloating the DB. */
        const val DEFAULT_HISTORY_LIMIT: Int = 5_000
    }
}
