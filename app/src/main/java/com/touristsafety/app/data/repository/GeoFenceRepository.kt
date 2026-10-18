package com.touristsafety.app.data.repository

import com.touristsafety.app.data.database.dao.GeoFenceDao
import com.touristsafety.app.data.mapper.toDomain
import com.touristsafety.app.data.mapper.toEntity
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.geofencing.PolygonValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Result of attempting to persist a zone. */
sealed class SaveZoneResult {
    data class Saved(val id: Long) : SaveZoneResult()

    data class Rejected(val reason: String) : SaveZoneResult()
}

/**
 * Single source of truth for saved geo-fences.
 *
 * Every write goes through [PolygonValidator] first, so an invalid polygon can never reach SQLite
 * no matter which screen (editor, simulation, import) asked for it.
 */
class GeoFenceRepository(
    private val dao: GeoFenceDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeZones(): Flow<List<GeoFenceZone>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    fun observeEnabledZones(): Flow<List<GeoFenceZone>> =
        dao.observeEnabled().map { entities -> entities.map { it.toDomain() } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Snapshot of enabled zones for the tracking hot path.
     *
     * The tracking pipeline reads this once per fix rather than holding a long-lived Flow, so a
     * zone edited mid-walk takes effect on the very next reading.
     */
    suspend fun getEnabledZones(): List<GeoFenceZone> = dao.getEnabled().map { it.toDomain() }

    suspend fun getAllZones(): List<GeoFenceZone> = dao.getAll().map { it.toDomain() }

    suspend fun getZone(id: Long): GeoFenceZone? = dao.getById(id)?.toDomain()

    suspend fun create(zone: GeoFenceZone): SaveZoneResult {
        rejectNameIfBlank(zone.name)?.let { return it }

        val validation = PolygonValidator.validate(zone.vertices)
        if (!validation.isValid) {
            return SaveZoneResult.Rejected(validation.reason ?: "The polygon is not valid.")
        }

        val now = clock()
        val id = dao.insert(
            zone.copy(
                id = 0L,
                createdAtMillis = now,
                updatedAtMillis = now,
            ).toEntity(),
        )
        return SaveZoneResult.Saved(id)
    }

    suspend fun update(zone: GeoFenceZone): SaveZoneResult {
        rejectNameIfBlank(zone.name)?.let { return it }

        val validation = PolygonValidator.validate(zone.vertices)
        if (!validation.isValid) {
            return SaveZoneResult.Rejected(validation.reason ?: "The polygon is not valid.")
        }

        val rows = dao.update(zone.copy(updatedAtMillis = clock()).toEntity())
        return if (rows > 0) {
            SaveZoneResult.Saved(zone.id)
        } else {
            SaveZoneResult.Rejected("That zone no longer exists.")
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): Boolean =
        dao.setEnabled(id, enabled, clock()) > 0

    suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    private fun rejectNameIfBlank(name: String): SaveZoneResult.Rejected? =
        if (name.isBlank()) {
            SaveZoneResult.Rejected("Give the zone a name before saving.")
        } else {
            null
        }
}
