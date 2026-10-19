package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.OutboxEvent
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * The authority client: one method per endpoint, each returning an [AuthorityCall].
 *
 * The base URL is read through a lambda rather than captured, so changing it in Settings takes effect
 * on the next call without rebuilding the graph.
 */
class AuthorityClient(
    private val baseUrl: suspend () -> String,
    private val http: AuthorityHttp,
) {

    /**
     * Is an authority answering its health endpoint?
     *
     * [baseUrlOverride] exists because the settings screen has to test the address the operator is
     * *looking at*, which may not be saved yet. Probing the stored address instead would report on
     * something they did not type, which is exactly the confusion this screen removes. The override
     * is a one-shot: it changes nothing in the session store.
     */
    suspend fun health(baseUrlOverride: String? = null): AuthorityCall<Boolean> =
        when (
            val call = send(
                method = "GET",
                path = AuthorityEndpoints.HEALTH,
                body = null,
                token = null,
                baseUrlOverride = baseUrlOverride,
            )
        ) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(true)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }

    suspend fun login(
        role: String,
        id: String,
        password: String,
        deviceId: String?,
    ): AuthorityCall<AuthorityLoginResult> = decode {
        when (val call = send(
            method = "POST",
            path = AuthorityEndpoints.LOGIN,
            body = AuthorityApi.loginBody(role, id, password, deviceId),
            token = null,
        )) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseLogin(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    suspend fun loginWithPin(
        role: String,
        id: String,
        pin: String,
        deviceId: String?,
    ): AuthorityCall<AuthorityLoginResult> = decode {
        when (val call = send(
            method = "POST",
            path = AuthorityEndpoints.LOGIN,
            body = AuthorityApi.pinLoginBody(role, id, pin, deviceId),
            token = null,
        )) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseLogin(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /**
     * Register a new tourist.
     *
     * Sent with no bearer token: this is the one call the app makes before it has a credential. The
     * result is the *server-minted* Tourist ID — the app never invents one — and, on purpose, no token:
     * the tourist is not signed in by registering.
     */
    suspend fun register(
        draft: RegistrationDraft,
        deviceId: String?,
    ): AuthorityCall<AuthorityRegistration> = decode {
        when (
            val call = send(
                method = "POST",
                path = AuthorityEndpoints.REGISTER,
                body = AuthorityApi.registerBody(draft, deviceId),
                token = null,
            )
        ) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseRegistration(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    suspend fun logout(token: String): AuthorityCall<Boolean> =
        when (val call = send("POST", AuthorityEndpoints.LOGOUT, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(true)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }

    suspend fun profile(token: String): AuthorityCall<AuthorityProfile> = decode {
        when (val call = send("GET", AuthorityEndpoints.ME, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseProfile(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    suspend fun putContacts(
        token: String,
        contacts: List<EmergencyContact>,
        timestampMillis: Long,
    ): AuthorityCall<List<EmergencyContact>> = decode {
        when (
            val call = send(
                method = "PUT",
                path = AuthorityEndpoints.CONTACTS,
                body = AuthorityApi.contactsBody(contacts, timestampMillis),
                token = token,
            )
        ) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseContacts(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /** Replaces this tourist's numeric PIN. The password remains valid. */
    suspend fun setPin(token: String, pin: String): AuthorityCall<Boolean> =
        when (val call = send("PUT", AuthorityEndpoints.PIN, AuthorityApi.pinBody(pin), token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(true)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }

    /**
     * The officer's register of accounts.
     *
     * Bearer-authenticated with the *officer* token: the server's `require_auth("authority")` answers a
     * tourist token with 403, so this is where "tourists cannot reach officer data" is actually
     * enforced — the app merely shows the resulting error.
     */
    suspend fun authorityTourists(token: String): AuthorityCall<List<AuthorityTouristRow>> = decode {
        when (val call = send("GET", AuthorityEndpoints.AUTHORITY_TOURISTS, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseTourists(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    suspend fun syncEvents(token: String, events: List<OutboxEvent>): AuthorityCall<List<AuthoritySyncOutcome>> =
        decode {
            when (
                val call = send(
                    method = "POST",
                    path = AuthorityEndpoints.SYNC_EVENTS,
                    body = AuthorityApi.syncBody(events),
                    token = token,
                )
            ) {
                is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseSyncOutcomes(call.value.body))
                is AuthorityCall.HttpError -> call
                is AuthorityCall.Unreachable -> call
                is AuthorityCall.Protocol -> call
            }
        }

    /**
     * The zones a phone should evaluate (`GET /zones`).
     *
     * Readable by any signed-in subject on purpose: a tourist's phone needs the geometry to warn its
     * user, and the published set of closed-off areas is not sensitive. Everything that *changes* a
     * zone lives under `/authority/zones` and is refused for a tourist token.
     */
    suspend fun activeZones(token: String): AuthorityCall<List<AuthorityZone>> = decode {
        when (val call = send("GET", AuthorityEndpoints.ZONES, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseZones(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /** Every zone, drafts and archived included (`GET /authority/zones`). Authority role only. */
    suspend fun authorityZones(token: String): AuthorityCall<List<AuthorityZone>> = decode {
        when (val call = send("GET", AuthorityEndpoints.AUTHORITY_ZONES, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseZones(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /**
     * One zone by id (`GET /authority/zones/<id>`). Authority role only.
     *
     * The editor re-reads the zone rather than being handed the list row, so an edit always starts
     * from the server's current polygon and revision and cannot save over a change made in between.
     */
    suspend fun authorityZone(token: String, zoneId: Int): AuthorityCall<AuthorityZone> = decode {
        when (
            val call = send(
                method = "GET",
                path = AuthorityEndpoints.authorityZonePath(zoneId),
                body = null,
                token = token,
            )
        ) {
            is AuthorityCall.Ok -> singleZone(call.value.body)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /**
     * Publish a new zone (`POST /authority/zones`).
     *
     * Authority role only. Sending this with a tourist token is answered with HTTP 403 by the server;
     * the app cannot create a zone on a tourist's behalf even if a screen were added back.
     */
    suspend fun createZone(
        token: String,
        name: String,
        description: String,
        zoneType: String,
        vertices: List<GeoPoint>,
        status: String? = null,
    ): AuthorityCall<AuthorityZone> = decode {
        when (
            val call = send(
                method = "POST",
                path = AuthorityEndpoints.AUTHORITY_ZONES,
                body = AuthorityApi.zoneBody(name, description, zoneType, vertices, status),
                token = token,
            )
        ) {
            is AuthorityCall.Ok -> singleZone(call.value.body)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /**
     * Activate, deactivate or archive a zone (`POST /authority/zones/<id>/status`). Authority only.
     *
     * Archiving is a status change, not a delete: the server keeps the row so the audit trail of why
     * a tourist was warned survives.
     */
    suspend fun setZoneStatus(
        token: String,
        zoneId: Int,
        status: String,
    ): AuthorityCall<AuthorityZone> = decode {
        when (
            val call = send(
                method = "POST",
                path = AuthorityEndpoints.authorityZoneStatusPath(zoneId),
                body = AuthorityApi.zoneStatusBody(status),
                token = token,
            )
        ) {
            is AuthorityCall.Ok -> singleZone(call.value.body)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /** The `{"zone": …}` a mutating route answers with, or a protocol error if it is missing. */
    private fun singleZone(body: String): AuthorityCall<AuthorityZone> {
        val zone = AuthorityApi.parseZone(body)
        return if (zone == null) {
            AuthorityCall.Protocol("The authority did not return the zone it was asked to change.")
        } else {
            AuthorityCall.Ok(zone)
        }
    }

    /**
     * Replace a zone's details (`PUT /authority/zones/<id>`). Authority role only.
     *
     * A full replace rather than a patch: the server increments the zone `revision` and every phone
     * holding the older polygon re-reads it, so a partial write would make "what changed" ambiguous.
     */
    suspend fun updateZone(
        token: String,
        zoneId: Int,
        name: String,
        description: String,
        zoneType: String,
        vertices: List<GeoPoint>,
        status: String,
    ): AuthorityCall<AuthorityZone> = decode {
        when (
            val call = send(
                method = "PUT",
                path = AuthorityEndpoints.authorityZonePath(zoneId),
                body = AuthorityApi.zoneBody(name, description, zoneType, vertices, status),
                token = token,
            )
        ) {
            is AuthorityCall.Ok -> singleZone(call.value.body)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /**
     * Archive a zone (`DELETE /authority/zones/<id>`). Authority role only.
     *
     * The server soft-deletes: the row is kept and marked `ARCHIVED`, so a phone that was warned by
     * this zone still has a record to point at. It is never evaluated again.
     */
    suspend fun archiveZone(token: String, zoneId: Int): AuthorityCall<AuthorityZone> = decode {
        when (
            val call = send(
                method = "DELETE",
                path = AuthorityEndpoints.authorityZonePath(zoneId),
                body = null,
                token = token,
            )
        ) {
            is AuthorityCall.Ok -> singleZone(call.value.body)
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    // ---------------------------------------------------------------- console reads

    /** Counters and per-tourist status for the dashboard (`GET /authority/overview`). */
    suspend fun authorityOverview(token: String): AuthorityCall<AuthorityOverview> = decode {
        when (val call = send("GET", AuthorityEndpoints.AUTHORITY_OVERVIEW, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseOverview(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /**
     * Incidents (`GET /authority/sos`).
     *
     * [status] is `ALL`, `OPEN` or `ACKNOWLEDGED` — the server's own vocabulary; anything else is
     * rejected with a 400 rather than silently treated as `ALL`, so a typo cannot hide open incidents.
     */
    suspend fun authoritySos(
        token: String,
        status: String? = null,
    ): AuthorityCall<List<AuthoritySosRow>> = decode {
        val path = if (status.isNullOrBlank()) {
            AuthorityEndpoints.AUTHORITY_SOS
        } else {
            "${AuthorityEndpoints.AUTHORITY_SOS}?status=$status"
        }
        when (val call = send("GET", path, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseSos(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /**
     * Acknowledge one incident (`POST /authority/sos/<id>/ack`). Authority role only.
     *
     * The server records *who* acknowledged it from the token, never from the body — there is no
     * field here for the caller to claim a name with.
     */
    suspend fun acknowledgeSos(
        token: String,
        sosId: Long,
        note: String?,
    ): AuthorityCall<AuthoritySosRow> = decode {
        when (
            val call = send(
                method = "POST",
                path = AuthorityEndpoints.authoritySosAckPath(sosId),
                body = AuthorityApi.sosAckBody(note),
                token = token,
            )
        ) {
            is AuthorityCall.Ok -> {
                val row = AuthorityApi.parseSosOne(call.value.body)
                if (row == null) {
                    AuthorityCall.Protocol("The authority did not return the incident it acknowledged.")
                } else {
                    AuthorityCall.Ok(row)
                }
            }

            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /** Trained-model forecasts (`GET /authority/risk`), kept apart from incidents by the server. */
    suspend fun authorityRisk(token: String): AuthorityCall<List<AuthorityRiskRow>> = decode {
        when (val call = send("GET", AuthorityEndpoints.AUTHORITY_RISK, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseRisk(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    /** The raw event feed (`GET /authority/events`). */
    suspend fun authorityEvents(
        token: String,
        kind: String? = null,
        touristId: String? = null,
    ): AuthorityCall<List<AuthorityEventRow>> = decode {
        val query = buildList {
            if (!kind.isNullOrBlank()) add("kind=$kind")
            if (!touristId.isNullOrBlank()) add("touristId=$touristId")
        }.joinToString("&")
        val path = if (query.isEmpty()) {
            AuthorityEndpoints.AUTHORITY_EVENTS
        } else {
            "${AuthorityEndpoints.AUTHORITY_EVENTS}?$query"
        }
        when (val call = send("GET", path, body = null, token = token)) {
            is AuthorityCall.Ok -> AuthorityCall.Ok(AuthorityApi.parseEvents(call.value.body))
            is AuthorityCall.HttpError -> call
            is AuthorityCall.Unreachable -> call
            is AuthorityCall.Protocol -> call
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun send(
        method: String,
        path: String,
        body: String?,
        token: String?,
        baseUrlOverride: String? = null,
    ): AuthorityCall<AuthorityHttpResponse> {
        // Every call reads the address from the store unless a caller explicitly overrides it for a
        // single probe. There is one configured address, shared by the tourist sign-in, the officer
        // sign-in, the outbox, SOS delivery and zone sync, so "which server is this phone talking to?"
        // has exactly one answer at any moment.
        val url = AuthorityUrls.resolve(baseUrlOverride ?: baseUrl(), path)
        if (url.isEmpty()) {
            return AuthorityCall.Protocol("No authority base URL is configured.")
        }
        val response = try {
            http.execute(
                AuthorityHttpRequest(method = method, url = url, body = body, bearerToken = token),
            )
        } catch (cancelled: CancellationException) {
            // Coroutine cancellation is not a network failure; it has to propagate or the caller's
            // scope is silently broken.
            throw cancelled
        } catch (io: IOException) {
            // Connection refused, DNS failure, timeout, TLS failure, a malformed URL, and the
            // platform's "cleartext traffic not permitted": all "the server is not answering right
            // now", which the queue must treat as transient.
            return AuthorityCall.Unreachable(io.message ?: io.javaClass.simpleName)
        } catch (problem: Exception) {
            // Everything else the platform can throw from a socket: `SecurityException` when a
            // permission is missing, `IllegalStateException` from a URL with no host,
            // `ClassCastException` from a non-HTTP scheme. These are still "we could not reach the
            // authority", and they must arrive at the screen as a sentence. An exception escaping
            // here would close the app while the user is looking at the address screen — which is
            // exactly the failure this catch exists to prevent. The type is kept in the message so a
            // genuine defect is still diagnosable rather than swallowed.
            return AuthorityCall.Unreachable(
                "${problem.javaClass.simpleName}: ${problem.message ?: "no detail"}",
            )
        }
        if (response.status !in 200..299) {
            return AuthorityCall.HttpError(
                status = response.status,
                code = AuthorityApi.errorCode(response.body),
                message = AuthorityApi.errorMessage(response.body)
                    ?: "The authority answered HTTP ${response.status}.",
            )
        }
        return AuthorityCall.Ok(response)
    }

    /** Turns a malformed body into a [AuthorityCall.Protocol] instead of letting it escape the client. */
    private inline fun <T> decode(block: () -> AuthorityCall<T>): AuthorityCall<T> = try {
        block()
    } catch (malformed: JsonParseException) {
        AuthorityCall.Protocol("The authority sent a body that is not JSON: ${malformed.message}")
    } catch (invalid: AuthorityProtocolException) {
        AuthorityCall.Protocol(invalid.message ?: "The authority response did not match the contract.")
    }
}
