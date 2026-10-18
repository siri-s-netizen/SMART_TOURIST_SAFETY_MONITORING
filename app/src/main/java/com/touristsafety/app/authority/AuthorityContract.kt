package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact

/**
 * The wire contract with the authority backend, mirroring `backend/tsm/api.py` one route at a time.
 *
 * Keeping the paths in one object (rather than as string literals scattered through the client) is
 * what makes "the app and the server disagree about a URL" a single-line diff instead of a hunt.
 */
object AuthorityEndpoints {

    const val HEALTH: String = "/api/v1/health"
    const val LOGIN: String = "/api/v1/auth/login"

    /** Self-service tourist registration. The one route the app calls before it has any credential. */
    const val REGISTER: String = "/api/v1/auth/register"
    const val LOGOUT: String = "/api/v1/auth/logout"
    const val ME: String = "/api/v1/me"
    const val CONTACTS: String = "/api/v1/me/contacts"
    const val PIN: String = "/api/v1/me/pin"
    const val SYNC_EVENTS: String = "/api/v1/sync/events"

    /** The operator-facing routes the web dashboard uses; here so the app can report reachability. */
    const val AUTHORITY_OVERVIEW: String = "/api/v1/authority/overview"

    /**
     * The officer's register of accounts. Authority role only — the server rejects a tourist token
     * with 403, which is what makes the officer view in this app a role, not a hidden screen.
     */
    const val AUTHORITY_TOURISTS: String = "/api/v1/authority/tourists"

    /**
     * The published restricted/dangerous/safe areas.
     *
     * [ZONES] is read-only and open to any signed-in subject, because a tourist's phone needs the
     * geometry to warn its user. [AUTHORITY_ZONES] is the management surface — create, edit, activate,
     * deactivate, archive — and the server guards every write with `require_auth("authority")`. A
     * tourist token sent to it is rejected with 403 regardless of what any screen shows.
     */
    const val ZONES: String = "/api/v1/zones"
    const val AUTHORITY_ZONES: String = "/api/v1/authority/zones"

    /** `POST` here to activate/deactivate/archive a zone without touching its geometry. */
    fun authorityZoneStatusPath(zoneId: Int): String = "$AUTHORITY_ZONES/$zoneId/status"

    /** `PUT` a full edit, or `DELETE` to archive, at this path. */
    fun authorityZonePath(zoneId: Int): String = "$AUTHORITY_ZONES/$zoneId"
}

/** Role strings the backend accepts on `/auth/login`. A typo here is a 400, so they live in one place. */
object AuthorityRoles {
    const val TOURIST: String = "tourist"
    const val AUTHORITY: String = "authority"
}

/**
 * How reachable the authority actually is, as distinct from "an address has been typed in".
 *
 * The distinction is the whole point: a saved address that nobody answers must read as offline, never
 * as connected, because a tourist who is told they are connected stops worrying about the alert that
 * is quietly queuing on their phone.
 */
enum class AuthorityConnectionState {
    /** No address has been configured at all. */
    NOT_CONFIGURED,

    /** An address exists but nothing has been tried yet. */
    UNKNOWN,

    CHECKING,

    /** The authority answered its health endpoint. */
    CONNECTED,

    /** The address is set but the server did not answer: wrong address, powered off, or off-network. */
    OFFLINE,

    /** Something answered, but not the health endpoint this app expects. */
    ERROR,
    ;

    val isProblem: Boolean get() = this == NOT_CONFIGURED || this == OFFLINE || this == ERROR
}

/**
 * Base-URL handling.
 *
 * The operator types a host ("192.168.1.20:8080") far more often than a full origin, so a missing
 * scheme is assumed to be `http://` — a LAN backend has no certificate — and a trailing slash is
 * dropped so the path join cannot produce `//api/v1/...`, which Flask answers with a 404.
 */
object AuthorityUrls {

    fun normalise(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return ""
        val scheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return scheme.trimEnd('/')
    }

    fun resolve(baseUrl: String, path: String): String {
        val normalised = normalise(baseUrl)
        if (normalised.isEmpty()) return ""
        return if (path.startsWith("/")) "$normalised$path" else "$normalised/$path"
    }
}

/** The signed-in state, persisted so a tourist is not asked to sign in on every launch. */
data class AuthoritySession(
    val baseUrl: String = "",
    val token: String? = null,
    val role: String? = null,
    val subjectId: String? = null,
    val displayName: String? = null,
    val expiresAtMillis: Long = 0L,
    val signedInAtMillis: Long = 0L,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /**
     * A token that the *device* believes is live. The server is still the authority — an expired or
     * revoked token comes back as 401 and the transport retries, it does not silently drop anything.
     */
    fun isSignedIn(nowMillis: Long): Boolean =
        !token.isNullOrBlank() && expiresAtMillis > nowMillis
}

/** What `/auth/login` returns on success. */
data class AuthorityLoginResult(
    val token: String,
    val role: String,
    val subjectId: String,
    val displayName: String,
    val expiresAtMillis: Long,
    val serverTimeMillis: Long,
)

/**
 * An officer's session, kept apart from the tourist session on purpose.
 *
 * One store holds both, under different keys, so there is a single configured address. They are
 * separate *values* because the two roles are separate people: signing in as an officer on this
 * handset must not sign a tourist in, must not make the phone start uploading its own position as a
 * tourist, and must not be destroyed by a tourist signing out. The server is still the authority —
 * a tourist token presented to an officer route is a 403, not a hidden screen.
 */
data class OfficerSession(
    val baseUrl: String = "",
    val token: String? = null,
    val subjectId: String? = null,
    val displayName: String? = null,
    val expiresAtMillis: Long = 0L,
) {
    fun isSignedIn(nowMillis: Long): Boolean =
        !token.isNullOrBlank() && expiresAtMillis > nowMillis
}

/**
 * One row of the officer's registered-tourist register (`GET /authority/tourists`).
 *
 * Registration, tracking and safety arrive as three separate groups of fields and are kept separate
 * here too, so the console cannot accidentally present "registered" as "being tracked" or a forecast
 * as an emergency. [latestPosition] is null until the phone has actually reported — the screen says
 * "Location not yet available" rather than inventing a point.
 */
data class AuthorityTouristRow(
    val touristId: String,
    val displayName: String,
    val phone: String?,
    val registeredAtMillis: Long,
    val registrationSource: String?,
    val registrationStatus: String,
    val trackingStatus: String,
    val lastSeenMillis: Long?,
    val hasPosition: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val positionRecordedAtMillis: Long?,
    val positionSimulated: Boolean,
    val emergencyContactCount: Int,
    val openSosCount: Int,
    val latestRiskBand: String?,
    val state: String,
)

/**
 * What `/auth/register` returns on success: the Tourist ID the **backend** minted.
 *
 * There is deliberately no token here. Registration creates an account; it does not sign anyone in, so
 * the ID is shown to the tourist (to copy down) and the sign-in is a separate, deliberate step. The
 * server also returns no PIN and no hash — the PIN is the one the tourist just chose.
 */
data class AuthorityRegistration(
    val touristId: String,
    val displayName: String,
    val phone: String?,
    val registeredAtMillis: Long,
)

/** The authority's view of one SOS, used only for this tourist's own last-known status. */
data class AuthorityLatestSos(
    val sosId: Long,
    val status: String,
    val raisedAtMillis: Long,
    val acknowledgedAtMillis: Long?,
    val acknowledgedBy: String?,
    val simulated: Boolean,
)

/** The authority's view of the tourist's last reported position, from `GET /me`. */
data class AuthorityLatestPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val recordedAtMillis: Long,
    val receivedAtMillis: Long,
    val simulated: Boolean,
)

/** What `GET /me` returns: the tourist's own record plus what the authority has received. */
data class AuthorityProfile(
    val touristId: String,
    val displayName: String,
    val phone: String?,
    val contacts: List<EmergencyContact>,
    val deliveredEventCount: Int,
    val lastDeliveredAtMillis: Long?,
    val latestSos: AuthorityLatestSos?,
    val latestPosition: AuthorityLatestPosition?,
    val serverTimeMillis: Long,
)

/** The per-event verdict the server reports for a sync batch. */
enum class AuthoritySyncStatus {
    DELIVERED,
    DUPLICATE,
    REJECTED,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String?): AuthoritySyncStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

data class AuthoritySyncOutcome(
    val dedupeKey: String?,
    val status: AuthoritySyncStatus,
    val serverEventId: String? = null,
    val acknowledgementId: String? = null,
    val reason: String? = null,
)

/**
 * The result of one call.
 *
 * Four cases rather than an exception, because the caller's reaction differs completely: an
 * [HttpError] with status 401 means "sign in again and the event will go", a [Protocol] error means
 * the server answered with something this client cannot read (a version mismatch, not a network
 * problem), and only [Unreachable] is genuinely "try again later".
 */
sealed interface AuthorityCall<out T> {

    data class Ok<T>(val value: T) : AuthorityCall<T>

    data class HttpError(val status: Int, val code: String?, val message: String) : AuthorityCall<Nothing>

    data class Unreachable(val message: String) : AuthorityCall<Nothing>

    data class Protocol(val message: String) : AuthorityCall<Nothing>
}

/** A response body that parsed as JSON but did not contain the fields the contract requires. */
class AuthorityProtocolException(message: String) : IllegalStateException(message)
