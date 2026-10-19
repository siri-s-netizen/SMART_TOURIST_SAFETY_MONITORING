package com.touristsafety.app.data.repository

import com.touristsafety.app.data.database.dao.GeoFenceDao
import com.touristsafety.app.data.mapper.toDomain
import com.touristsafety.app.data.mapper.toEntity
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.ZoneSource
import com.touristsafety.app.geofencing.PolygonValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Result of attempting to persist a zone. */
sealed class SaveZoneResult {
    data class Saved(val id: Long) : SaveZoneResult()

    data class Rejected(val reason: String) : SaveZoneResult()
}

/**
 * What one authority-zone sync changed.
 *
 * Counted rather than logged so the result is assertable in a test and displayable on the Settings
 * screen: "3 updated, 1 withdrawn" is a fact, "sync complete" is not.
 */
data class AuthorityZoneSyncResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val withdrawn: Int = 0,
    val rejected: Int = 0,
) {
    val changed: Int get() = inserted + updated + withdrawn

    val isEmpty: Boolean get() = changed == 0 && rejected == 0
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

    /**
     * Makes this phone's copy of the authority's published zones match [zones] exactly.
     *
     * This is the tourist's half of shared zone management, and it is a **mirror**, not a merge:
     *
     * - a zone the authority publishes that this phone has not seen is inserted, carrying the
     *   authority's `zoneId` and `revision`;
     * - a zone it already holds is rewritten when the revision, the polygon or the labels differ —
     *   the revision alone would usually be enough, but comparing the geometry too means an
     *   interrupted earlier write heals itself instead of leaving a stale boundary in place;
     * - a zone it holds that the authority no longer publishes is removed, because a withdrawn
     *   boundary that keeps warning tourists is worse than no boundary;
     * - a **local** zone is not read, not written and not removed. [GeoFenceDao.getAuthorityZones]
     *   cannot even see one.
     *
     * A polygon that fails [PolygonValidator] is counted as rejected and skipped rather than stored:
     * a self-intersecting ring from a buggy or hostile server must never become a fence the engine
     * then evaluates. The rejection is reported, never swallowed.
     *
     * Alert history is unaffected by a removal: [com.touristsafety.app.domain.model.GeoFenceAlert]
     * denormalises the zone's name and type, so an alert raised by a zone that has since been
     * withdrawn still reads correctly afterwards.
     *
     * @param zones the authority's published zones, already mapped to local domain objects with
     *   [GeoFenceZone.source], [GeoFenceZone.authorityZoneId] and [GeoFenceZone.authorityRevision] set.
     */
    suspend fun replaceAuthorityZones(
        zones: List<GeoFenceZone>,
        nowMillis: Long = clock(),
    ): AuthorityZoneSyncResult {
        val existing = dao.getAuthorityZones().map { it.toDomain() }
        val existingByAuthorityId = existing
            .mapNotNull { zone -> zone.authorityZoneId?.let { id -> id to zone } }
            .toMap()

        val stillPublished = mutableSetOf<Int>()
        var inserted = 0
        var updated = 0
        var rejected = 0

        zones.forEach { incoming ->
            val authorityZoneId = incoming.authorityZoneId
            if (authorityZoneId == null || !incoming.isFromAuthority) {
                rejected++
                return@forEach
            }
            if (incoming.name.isBlank() || !incoming.hasEnoughVertices) {
                rejected++
                return@forEach
            }
            if (!PolygonValidator.validate(incoming.vertices).isValid) {
                rejected++
                return@forEach
            }

            stillPublished += authorityZoneId
            val mirrored = incoming.copy(
                source = ZoneSource.AUTHORITY,
                enabled = true,
                // The authority's own "updated" time when it sent one, so the list reads in the
                // authority's order rather than in the order this phone happened to fetch.
                updatedAtMillis = incoming.updatedAtMillis.takeIf { it > 0L } ?: nowMillis,
            )

            val current = existingByAuthorityId[authorityZoneId]
            when {
                current == null -> {
                    dao.insert(
                        mirrored.copy(id = 0L, createdAtMillis = nowMillis).toEntity(),
                    )
                    inserted++
                }

                differs(current, mirrored) -> {
                    dao.update(
                        mirrored.copy(
                            id = current.id,
                            createdAtMillis = current.createdAtMillis,
                        ).toEntity(),
                    )
                    updated++
                }
            }
        }

        val toWithdraw = existing.filter { zone ->
            zone.authorityZoneId == null || zone.authorityZoneId !in stillPublished
        }
        toWithdraw.forEach { dao.deleteAuthorityById(it.id) }

        return AuthorityZoneSyncResult(
            inserted = inserted,
            updated = updated,
            withdrawn = toWithdraw.size,
            rejected = rejected,
        )
    }

    /**
     * Whether a mirrored zone differs from what the authority now publishes.
     *
     * The revision is the authority's own change counter and is what a phone is meant to trust, but
     * the geometry is compared as well: if a previous sync was interrupted after the revision was
     * written but before the polygon was, only the geometry reveals it.
     */
    private fun differs(current: GeoFenceZone, incoming: GeoFenceZone): Boolean =
        current.authorityRevision != incoming.authorityRevision ||
            current.vertices != incoming.vertices ||
            current.name != incoming.name ||
            current.description != incoming.description ||
            current.type != incoming.type ||
            current.enabled != incoming.enabled

    suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    /**
     * Removes the zones this phone drew, leaving the authority's published mirrors alone.
     *
     * This is what the tourist-facing "delete all" uses. Deleting the mirrors would be both wrong and
     * pointless: they are not the user's zones to remove, and the next sync would put them straight
     * back — so the button would appear not to have worked. Returns how many rows went, so the screen
     * can report a real number rather than an estimate.
     */
    suspend fun deleteLocalZones(): Int = dao.deleteLocal()

    private fun rejectNameIfBlank(name: String): SaveZoneResult.Rejected? =
        if (name.isBlank()) {
            SaveZoneResult.Rejected("Give the zone a name before saving.")
        } else {
            null
        }
}
