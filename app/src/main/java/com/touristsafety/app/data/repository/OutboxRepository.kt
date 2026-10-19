package com.touristsafety.app.data.repository

import com.touristsafety.app.data.database.dao.OutboxEventDao
import com.touristsafety.app.data.mapper.toDomain
import com.touristsafety.app.data.mapper.toEntity
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The durable outbox: nothing leaves the device directly, everything is queued here first.
 *
 * Keeping the queue in Room (rather than in memory) is what makes "store now, send later when the
 * network returns" survive the app being killed in a tunnel with no signal.
 *
 * ## Ownership
 *
 * Every queued event records **whose** event it is, because the authority files an upload against the
 * subject of the token that carried it, not against anything in the payload. Without that stamp, an
 * event queued by one tourist but uploaded after a different tourist signed in on the same handset
 * would be filed under the wrong person.
 *
 * The stamp is applied in [enqueue] — the one function every producer (SOS, position report,
 * geo-fence alert, contacts upload, future-risk summary) goes through — so no enqueue path can be
 * missed, and delivery is restricted in [pendingForDelivery].
 */
class OutboxRepository(
    private val dao: OutboxEventDao,
    /**
     * The signed-in tourist's stable subject id, or `null` when nobody is signed in.
     *
     * Deliberately **has no default value**: every construction site has to answer "whose queue is
     * this?", so a new caller cannot quietly end up with an unowned queue.
     */
    private val currentOwner: suspend () -> String?,
) {

    /**
     * Queues [event], stamping it with the signed-in tourist's subject id.
     *
     * An event that already carries an owner keeps it, so a replay or a backup/restore cannot
     * silently re-attribute a row.
     *
     * With nobody signed in the row is stored **unassigned** rather than dropped: losing an SOS
     * because the tourist had signed out is worse than storing one that a human must later attribute.
     *
     * @return true if this is a new event, false if an event with the same [dedupeKey] existed.
     */
    suspend fun enqueue(event: OutboxEvent): Boolean =
        dao.enqueue(event.copy(ownerSubjectId = event.ownerSubjectId ?: owner()).toEntity()) != -1L

    /**
     * Every pending row, whoever it belongs to. For assertions, diagnostics and the "pending upload"
     * badge; **not** for delivery — use [pendingForDelivery] for that.
     */
    suspend fun pending(limit: Int = DEFAULT_BATCH): List<OutboxEvent> =
        dao.pending(limit).map { it.toDomain() }

    /**
     * The pending rows that may be sent right now: exactly those owned by the signed-in tourist.
     *
     * Anything else is **deferred**, never re-stamped and never rejected:
     *
     *  * another tourist's rows are held back until *their* owner signs in again, so a handset that
     *    has been passed on cannot upload the previous user's SOS under the new user's name;
     *  * unassigned rows (queued before ownership existed, or while signed out) are held back
     *    pending an explicit recovery decision — they are never adopted by the next tourist to sign in;
     *  * with nobody signed in there is nothing to deliver, and the list is empty rather than
     *    "everything".
     */
    suspend fun pendingForDelivery(limit: Int = DEFAULT_BATCH): List<OutboxEvent> {
        val owner = owner() ?: return emptyList()
        return dao.pendingOwnedBy(owner, limit).map { it.toDomain() }
    }

    /** How many waiting rows still have no owner — the ones an explicit recovery policy must handle. */
    suspend fun unassignedCount(): Int = dao.countUnassigned()

    /** Waiting rows with no owner, as a live count for a diagnostics or recovery screen. */
    fun observeUnassignedCount(): Flow<Int> = dao.observeUnassignedCount()

    /** The current owner, normalised: blank and whitespace-only ids are treated as "nobody". */
    private suspend fun owner(): String? = currentOwner()?.trim()?.takeIf { it.isNotEmpty() }

    fun observeAll(): Flow<List<OutboxEvent>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    fun observePendingCount(kind: OutboxKind): Flow<Int> =
        dao.observePendingCountOfKind(kind.storageKey)

    fun observeLatestOfKind(kind: OutboxKind): Flow<OutboxEvent?> =
        dao.observeLatestOfKind(kind.storageKey).map { it?.toDomain() }

    suspend fun findByDedupeKey(key: String): OutboxEvent? = dao.findByDedupeKey(key)?.toDomain()

    suspend fun recordAttempt(id: Long, attempts: Int, atMillis: Long, error: String?): Boolean =
        dao.recordAttempt(id, attempts, atMillis, error) > 0

    suspend fun markSent(id: Long, attempts: Int, atMillis: Long): Boolean =
        dao.markSent(id, attempts, atMillis) > 0

    suspend fun markFailed(id: Long, attempts: Int, atMillis: Long, error: String?): Boolean =
        dao.markFailed(id, attempts, atMillis, error) > 0

    /** Removes delivered rows so the queue cannot grow without bound. */
    suspend fun purgeSent(): Int = dao.deleteSent()

    suspend fun clear(): Int = dao.deleteAll()

    companion object {
        const val DEFAULT_BATCH: Int = 50
    }
}
