package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.OutboxEvent
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

    suspend fun health(): AuthorityCall<Boolean> =
        when (val call = send("GET", AuthorityEndpoints.HEALTH, body = null, token = null)) {
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
    ): AuthorityCall<AuthorityZone> = decode {
        when (
            val call = send(
                method = "POST",
                path = AuthorityEndpoints.AUTHORITY_ZONES,
                body = AuthorityApi.zoneBody(name, description, zoneType, vertices),
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

    // ---------------------------------------------------------------- plumbing

    private suspend fun send(
        method: String,
        path: String,
        body: String?,
        token: String?,
    ): AuthorityCall<AuthorityHttpResponse> {
        val url = AuthorityUrls.resolve(baseUrl(), path)
        if (url.isEmpty()) {
            return AuthorityCall.Protocol("No authority base URL is configured.")
        }
        val response = try {
            http.execute(
                AuthorityHttpRequest(method = method, url = url, body = body, bearerToken = token),
            )
        } catch (io: IOException) {
            // Connection refused, DNS failure, timeout, TLS failure: all "the server is not answering
            // right now", which the queue must treat as transient.
            return AuthorityCall.Unreachable(io.message ?: io.javaClass.simpleName)
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
