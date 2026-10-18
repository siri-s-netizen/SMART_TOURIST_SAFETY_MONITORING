package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.outbox.DeliveryResult
import com.touristsafety.app.outbox.NoBackendTransport
import com.touristsafety.app.outbox.SyncTransport

/**
 * The transport the phone actually uses.
 *
 * It is the missing link the backend was already written against: `backend/verify_integration.py`
 * describes step 5 as "delivers an outbox batch exactly as the phone's `HttpSyncTransport` shapes
 * it", and until this class existed the app could not shape one at all.
 *
 * ## What each answer means
 *
 * The rule is asymmetric on purpose. Anything that *might* succeed later leaves the event queued;
 * only a verdict that can never succeed marks it failed. Losing an SOS because the operator mistyped
 * a port would be far worse than retrying it forever.
 */
class AuthoritySyncTransport(
    private val sessions: AuthoritySessionStore,
    private val client: AuthorityClient,
    private val now: () -> Long = System::currentTimeMillis,
) : SyncTransport {

    override val name: String = TRANSPORT_NAME

    override suspend fun deliver(event: OutboxEvent): DeliveryResult {
        val session = sessions.current()

        if (!session.isConfigured) {
            return DeliveryResult.Retry(
                "No authority backend address is configured on this phone, so the event stays queued.",
            )
        }
        val token = session.token?.takeIf { it.isNotBlank() }
        if (token == null || !session.isSignedIn(now())) {
            return DeliveryResult.Retry(
                "Not signed in to ${session.baseUrl}; the event stays queued until the tourist signs in.",
            )
        }

        return when (val call = client.syncEvents(token, listOf(event))) {
            is AuthorityCall.Ok -> interpret(call.value, event)
            is AuthorityCall.HttpError -> interpretHttpError(call)
            is AuthorityCall.Unreachable -> DeliveryResult.Retry(
                "The authority at ${session.baseUrl} is unreachable: ${call.message}",
            )

            is AuthorityCall.Protocol -> DeliveryResult.Retry(
                "The authority answered in a way this app does not understand: ${call.message}",
            )
        }
    }

    private fun interpret(
        outcomes: List<AuthoritySyncOutcome>,
        event: OutboxEvent,
    ): DeliveryResult {
        // Match on the dedupe key so a batched future cannot mis-attribute an acknowledgement. A
        // response with no key at all falls back to the single verdict, which is unambiguous when one
        // event was sent.
        val outcome = outcomes.firstOrNull { it.dedupeKey == event.dedupeKey }
            ?: outcomes.singleOrNull()
            ?: return DeliveryResult.Retry(
                "The authority did not report a verdict for ${event.dedupeKey}; the event stays queued.",
            )

        return when (outcome.status) {
            AuthoritySyncStatus.DELIVERED -> DeliveryResult.Delivered(
                acknowledgementId = outcome.acknowledgementId ?: outcome.serverEventId,
            )

            // DUPLICATE is success: the server already has it, which is exactly what idempotency means.
            AuthoritySyncStatus.DUPLICATE -> DeliveryResult.Delivered(
                acknowledgementId = outcome.serverEventId,
            )

            AuthoritySyncStatus.REJECTED -> DeliveryResult.Rejected(
                outcome.reason ?: "The authority rejected this event and will never accept it.",
            )

            AuthoritySyncStatus.UNKNOWN -> DeliveryResult.Retry(
                "The authority reported an unrecognised status for ${event.dedupeKey}.",
            )
        }
    }

    private fun interpretHttpError(call: AuthorityCall.HttpError): DeliveryResult = when (call.status) {
        // The token expired or was revoked. The event is fine; the tourist's sign-in is not.
        401, 403 -> DeliveryResult.Retry("${call.message} (sign in again and it will be delivered)")

        // 400/422 come only from the batch envelope or the per-field validators refusing the payload
        // itself. Retrying the same bytes cannot fix either, so the row moves to a terminal state with
        // the server's own explanation attached.
        400, 422 -> DeliveryResult.Rejected(call.message)

        // 404/409/5xx are as likely to be a wrong base URL, a proxy or a restarting server as a bad
        // event, so they must not be allowed to discard anything.
        else -> DeliveryResult.Retry(call.message)
    }

    companion object {
        const val TRANSPORT_NAME: String = "authority"
    }
}

/**
 * Chooses a transport per delivery.
 *
 * The switch is per delivery rather than at construction because the backend address is entered in
 * Settings at runtime: a build that was started with nothing configured must begin delivering the
 * moment an operator types an address, without a restart. When nothing is configured this is exactly
 * the old honest behaviour — [NoBackendTransport] says so, and every event stays queued.
 */
class SelectingSyncTransport(
    private val configured: SyncTransport,
    private val isConfigured: suspend () -> Boolean,
    private val unconfigured: SyncTransport = NoBackendTransport(),
) : SyncTransport {

    @Volatile
    private var activeName: String = unconfigured.name

    /** The transport that handled the most recent delivery; what the diagnostics card shows. */
    override val name: String get() = activeName

    override suspend fun deliver(event: OutboxEvent): DeliveryResult {
        val active = if (isConfigured()) configured else unconfigured
        activeName = active.name
        return active.deliver(event)
    }
}
