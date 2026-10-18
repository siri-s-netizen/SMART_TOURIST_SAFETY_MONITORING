package com.touristsafety.app.data.repository

import com.touristsafety.app.data.database.dao.GeoFenceAlertDao
import com.touristsafety.app.data.mapper.toDomain
import com.touristsafety.app.data.mapper.toEntity
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.LocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Append-only record of zone crossings, kept entirely on the device. */
class AlertRepository(
    private val dao: GeoFenceAlertDao,
) {

    suspend fun record(alert: GeoFenceAlert): Long = dao.insert(alert.toEntity())

    fun observeHistory(): Flow<List<GeoFenceAlert>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    fun observeRecent(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<GeoFenceAlert>> =
        dao.observeRecent(limit).map { entities -> entities.map { it.toDomain() } }

    /** Drives the "unacknowledged alerts" badge on the dashboard. */
    fun observeUnacknowledged(): Flow<List<GeoFenceAlert>> =
        dao.observeUnacknowledged(DEFAULT_HISTORY_LIMIT).map { entities -> entities.map { it.toDomain() } }

    fun observeUnacknowledgedCount(): Flow<Int> = dao.observeUnacknowledgedCount()

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observeLatest(): Flow<GeoFenceAlert?> = dao.observeLatest().map { it?.toDomain() }

    suspend fun acknowledge(id: Long): Boolean = dao.acknowledge(id) > 0

    suspend fun acknowledgeAll(): Int = dao.acknowledgeAll()

    suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    suspend fun clear() {
        dao.deleteAll()
    }

    /** Removes only simulator-produced alerts; real geo-fence alerts are left alone. */
    suspend fun clearSimulated(): Int = dao.deleteBySource(LocationSource.SIMULATION.storageKey)

    companion object {
        const val DEFAULT_HISTORY_LIMIT: Int = 2_000
    }
}
