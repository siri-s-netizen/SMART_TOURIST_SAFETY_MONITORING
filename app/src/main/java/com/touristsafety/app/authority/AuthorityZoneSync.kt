package com.touristsafety.app.authority

import com.touristsafety.app.data.repository.AuthorityZoneSyncResult
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.ZoneSource
import com.touristsafety.app.domain.model.ZoneType as LocalZoneType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the last zone fetch did, for a status line that says something true.
 *
 * Deliberately not a boolean: "never run", "run and nothing changed", "run and 2 zones were updated"
 * and "run and failed, the mirror is unchanged" are four different states, and an officer or a tourist
 * reading a sync indicator deserves to be told which one they are in.
 */
data class ZoneSyncState(
    val inFlight: Boolean = false,
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    /** How many zones the authority last published to this phone. */
    val publishedCount: Int = 0,
    val lastResult: AuthorityZoneSyncResult? = null,
    val lastError: String? = null,
) {
    val hasEverSucceeded: Boolean get() = lastSuccessAtMillis != null
}

/** The outcome of one [AuthorityZoneSync.sync] call. */
sealed interface ZoneSyncOutcome {

    /** No tourist session, so there was nothing to fetch. Not a failure. */
    object SignedOut : ZoneSyncOutcome

    /** The authority answered and the local mirror now matches it. */
    data class Applied(
        val published: Int,
        val result: AuthorityZoneSyncResult,
    ) : ZoneSyncOutcome

    /** The authority could not be read. The mirror is **unchanged** — nothing was guessed. */
    data class Failed(val message: String) : ZoneSyncOutcome
}

/**
 * Keeps this phone's copy of the authority's zones current.
 *
 * ## Why a mirror at all
 *
 * The tourist app already evaluates whatever is in `geo_fences` —
 * [com.touristsafety.app.tracking.TrackingController] reads
 * `geoFenceRepository.getEnabledZones()` on every fix. So the honest way to
 * make an authority-published zone warn a tourist is **not** to add a second geofence engine or a
 * parallel list of zones; it is to put the authority's polygons in the same table the engine already
 * reads, marked with where they came from. That is what this class does, and it is why the tracking
 * path needed no change at all.
 *
 * ## What it will not do
 *
 * - It never invents a zone. Nothing is written unless the authority returned it.
 * - It never touches a local zone: the read is scoped `WHERE source = 'AUTHORITY'`.
 * - It never clears the mirror on failure. A fetch that fails leaves the last known zones in place and
 *   says so, because deleting every warning because the network blipped would be the worst of both
 *   worlds.
 * - It never treats a tourist token as an officer's. It calls `GET /zones`, the read-only route any
 *   signed-in subject may use; the write routes stay under `/authority/zones`.
 */
class AuthorityZoneSync(
    private val sessions: AuthoritySessionStore,
    private val client: AuthorityClient,
    private val geoFences: GeoFenceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(ZoneSyncState())
    val state: StateFlow<ZoneSyncState> = _state.asStateFlow()

    /**
     * Starts the background refresh: once when a tourist signs in, then every [INTERVAL_MILLIS] while
     * that session lasts.
     *
     * Driven by the **session flow**, so signing in starts it, signing out ends it, and a token that
     * expires simply stops the loop instead of hammering the server with requests that can only 401.
     * Called once at start-up; the collector is cancelled with [scope].
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            sessions.session.collectLatest { session ->
                val now = clock()
                if (!session.isSignedIn(now)) return@collectLatest
                while (true) {
                    sync()
                    delay(INTERVAL_MILLIS)
                }
            }
        }
    }

    /**
     * Reads the authority's published zones and makes the local mirror match them.
     *
     * Safe to call at any time, from any thread. Concurrent calls are not deduplicated: two overlapping
     * fetches each write a complete, self-consistent mirror, so the last one to finish still leaves the
     * table exactly matching one real server response.
     */
    suspend fun sync(): ZoneSyncOutcome {
        val now = clock()
        val session = sessions.current()
        if (!session.isSignedIn(now)) {
            _state.update {
                it.copy(inFlight = false, lastAttemptAtMillis = now, lastError = null)
            }
            return ZoneSyncOutcome.SignedOut
        }
        val token = session.token
        if (token.isNullOrBlank()) {
            _state.update {
                it.copy(inFlight = false, lastAttemptAtMillis = now, lastError = null)
            }
            return ZoneSyncOutcome.SignedOut
        }

        _state.update { it.copy(inFlight = true, lastAttemptAtMillis = now) }

        return when (val call = client.activeZones(token)) {
            is AuthorityCall.Ok -> {
                val zones = call.value
                val result = geoFences.replaceAuthorityZones(
                    zones = zones.map { zone -> zone.toLocalZone() },
                    nowMillis = now,
                )
                _state.update {
                    it.copy(
                        inFlight = false,
                        lastSuccessAtMillis = now,
                        publishedCount = zones.size,
                        lastResult = result,
                        lastError = null,
                    )
                }
                ZoneSyncOutcome.Applied(published = zones.size, result = result)
            }

            is AuthorityCall.HttpError -> {
                val message = if (call.status == 401 || call.status == 403) {
                    "The authority refused this session (HTTP ${call.status}), so the zones already " +
                        "on this phone are unchanged. Sign in again to refresh them."
                } else {
                    "Zone refresh failed (HTTP ${call.status}): ${call.message}. The zones already on " +
                        "this phone are unchanged."
                }
                _state.update { it.copy(inFlight = false, lastError = message) }
                ZoneSyncOutcome.Failed(message)
            }

            is AuthorityCall.Unreachable -> {
                val message = "Cannot reach the authority, so the zones already on this phone are " +
                    "unchanged: ${call.message}"
                _state.update { it.copy(inFlight = false, lastError = message) }
                ZoneSyncOutcome.Failed(message)
            }

            is AuthorityCall.Protocol -> {
                val message = "The authority's zone list could not be read: ${call.message}. The " +
                    "zones already on this phone are unchanged."
                _state.update { it.copy(inFlight = false, lastError = message) }
                ZoneSyncOutcome.Failed(message)
            }
        }
    }

    /**
     * A published zone as a local fence the engine will evaluate.
     *
     * `id` and `createdAtMillis` are left at zero: they belong to the *local* row and are supplied by
     * the repository, which knows whether this zone is new here or an update of one already stored.
     * Stamping them here would be a guess that could collide with a local zone's row.
     */
    private fun AuthorityZone.toLocalZone(): GeoFenceZone = GeoFenceZone(
        id = 0L,
        name = name,
        description = description,
        type = LocalZoneType.fromStorage(zoneType),
        vertices = vertices,
        // The authority publishes only what it wants evaluated, so a published zone is on.
        enabled = true,
        createdAtMillis = 0L,
        updatedAtMillis = updatedAtMillis,
        source = ZoneSource.AUTHORITY,
        authorityZoneId = zoneId,
        authorityRevision = revision,
    )

    companion object {
        /** Long enough not to be a poll loop, short enough that a new zone reaches a tourist soon. */
        const val INTERVAL_MILLIS: Long = 5 * 60 * 1000L
    }
}
