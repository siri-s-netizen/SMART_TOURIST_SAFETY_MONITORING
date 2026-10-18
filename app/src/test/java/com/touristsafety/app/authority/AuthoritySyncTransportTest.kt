package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.outbox.DeliveryResult
import com.touristsafety.app.outbox.SyncTransport
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The retry/reject rules.
 *
 * The asymmetry under test is the important part: an answer that *might* succeed later must leave the
 * event queued, because losing an SOS to a misconfigured port or a restarting server is far worse than
 * retrying it forever.
 */
class AuthoritySyncTransportTest {

    private val signedIn = AuthoritySession(
        baseUrl = "http://127.0.0.1:8080",
        token = "tok_1",
        role = AuthorityRoles.TOURIST,
        subjectId = "T-1001",
        displayName = "Demo Tourist",
        expiresAtMillis = 10_000L,
        signedInAtMillis = 1L,
    )

    private val event = OutboxEvent(
        dedupeKey = "sos:1000",
        kind = OutboxKind.SOS,
        payload = """{"type":"SOS","simulated":true,"timestampMillis":1000,"latitude":18.5,"longitude":73.9}""",
        createdAtMillis = 1000L,
        origin = OutboxOrigin.SIMULATED_WATCH,
    )

    private fun transport(
        session: AuthoritySession,
        http: FakeAuthorityHttp,
    ): Pair<AuthoritySyncTransport, InMemoryAuthoritySessionStore> {
        val store = InMemoryAuthoritySessionStore(session)
        val client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)
        // `now` is fixed just after the token was issued, so "expired" has to be explicit.
        return AuthoritySyncTransport(sessions = store, client = client, now = { 1_000L }) to store
    }

    private fun httpReturning(status: Int, body: String): FakeAuthorityHttp =
        FakeAuthorityHttp { AuthorityHttpResponse(status = status, body = body) }

    private fun deliveredBody(): String =
        """{"results":[{"dedupeKey":"sos:1000","status":"DELIVERED","serverEventId":"evt_9",
            "acknowledgementId":"evt_9"}],"serverTimeMillis":1}"""

    // ---------------------------------------------------------------- stays queued

    @Test
    fun `with no address configured the event stays queued and says so`() = runBlocking {
        val (transport, _) = transport(AuthoritySession(), FakeAuthorityHttp())

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
        assertTrue(
            (result as DeliveryResult.Retry).reason.contains("No authority backend address"),
        )
    }

    @Test
    fun `with an address but no sign-in the event stays queued`() = runBlocking {
        val (transport, _) = transport(AuthoritySession(baseUrl = "http://127.0.0.1:8080"), FakeAuthorityHttp())

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
        assertTrue((result as DeliveryResult.Retry).reason.contains("Not signed in"))
    }

    @Test
    fun `an expired token is treated as not signed in rather than sent and rejected`() = runBlocking {
        val expired = signedIn.copy(expiresAtMillis = 500L)
        val http = FakeAuthorityHttp()
        val (transport, _) = transport(expired, http)

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
        assertTrue("nothing should have been sent", http.requests.isEmpty())
    }

    @Test
    fun `http 401 keeps the event queued because re-signing-in will deliver it`() = runBlocking {
        val http = httpReturning(401, """{"error":"unauthorized","message":"Sign in first."}""")
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertTrue("a 401 must not discard the event", result is DeliveryResult.Retry)
        assertTrue((result as DeliveryResult.Retry).reason.contains("Sign in first."))
    }

    @Test
    fun `http 500 keeps the event queued`() = runBlocking {
        val http = httpReturning(503, """{"error":"server_error","message":"database is locked"}""")
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
        assertTrue((result as DeliveryResult.Retry).reason.contains("database is locked"))
    }

    @Test
    fun `a dead network keeps the event queued`() = runBlocking {
        val http = FakeAuthorityHttp().apply { failure = IOException("Connection refused") }
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
        assertTrue((result as DeliveryResult.Retry).reason.contains("Connection refused"))
    }

    @Test
    fun `an unrecognised verdict keeps the event queued`() = runBlocking {
        val http = httpReturning(200, """{"results":[{"dedupeKey":"sos:1000","status":"QUARANTINED"}]}""")
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
    }

    @Test
    fun `a response with no verdict for our event keeps it queued`() = runBlocking {
        val http = httpReturning(200, """{"results":[]}""")
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
    }

    @Test
    fun `a non-json success body is a protocol error, not a delivery`() = runBlocking {
        val http = httpReturning(200, "<html>proxy login page</html>")
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
        assertTrue((result as DeliveryResult.Retry).reason.contains("does not understand"))
    }

    // ---------------------------------------------------------------- terminal states

    @Test
    fun `a delivered verdict is reported with the acknowledgement id`() = runBlocking {
        val http = httpReturning(200, deliveredBody())
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertEquals(DeliveryResult.Delivered("evt_9"), result)
    }

    @Test
    fun `a duplicate verdict counts as delivered because the server already has it`() = runBlocking {
        val http = httpReturning(
            200,
            """{"results":[{"dedupeKey":"sos:1000","status":"DUPLICATE","serverEventId":"evt_9",
                "reason":"Already received."}]}""",
        )
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertEquals(DeliveryResult.Delivered("evt_9"), result)
    }

    @Test
    fun `a rejected verdict fails the event with the server's reason`() = runBlocking {
        val http = httpReturning(
            200,
            """{"results":[{"dedupeKey":"sos:1000","status":"REJECTED",
                "reason":"timestampMillis must be a whole number"}]}""",
        )
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertEquals(
            DeliveryResult.Rejected("timestampMillis must be a whole number"),
            result,
        )
    }

    @Test
    fun `http 400 fails the event because the same bytes can never be accepted`() = runBlocking {
        val http = httpReturning(400, """{"error":"bad_request","message":"events must be a non-empty list"}""")
        val (transport, _) = transport(signedIn, http)

        val result = transport.deliver(event)

        assertEquals(DeliveryResult.Rejected("events must be a non-empty list"), result)
    }

    // ---------------------------------------------------------------- the wire

    @Test
    fun `delivery posts to the real sync path with a bearer token`() = runBlocking {
        val http = httpReturning(200, deliveredBody())
        val (transport, _) = transport(signedIn, http)

        transport.deliver(event)

        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("http://127.0.0.1:8080/api/v1/sync/events", request.url)
        // The raw token, without the scheme: `HttpUrlConnectionAuthorityHttp` is what turns this into
        // an `Authorization: Bearer ...` header, and that is asserted against a real socket in
        // AuthorityHttpServerTest.
        assertEquals("tok_1", request.bearerToken)
        val body = Json.parse(request.body!!)
        assertEquals("sos:1000", body.array("events").single().string("dedupeKey"))
    }

    // ---------------------------------------------------------------- selection

    @Test
    fun `the selector uses the honest no-backend transport until an address exists`() = runBlocking {
        val store = InMemoryAuthoritySessionStore()
        val http = FakeAuthorityHttp { AuthorityHttpResponse(200, deliveredBody()) }
        val authority = AuthoritySyncTransport(
            sessions = store,
            client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http),
            now = { 1_000L },
        )
        val transport: SyncTransport = SelectingSyncTransport(
            configured = authority,
            isConfigured = { store.current().isConfigured },
        )

        val result = transport.deliver(event)

        assertTrue(result is DeliveryResult.Retry)
        assertTrue((result as DeliveryResult.Retry).reason.contains("No authority backend is configured"))
        assertEquals("none", transport.name)

        // The very next delivery, after an address appears, must start delivering — no restart.
        store.setBaseUrl("127.0.0.1:8080")
        store.saveLogin(
            "127.0.0.1:8080",
            AuthorityLoginResult("tok_1", "tourist", "T-1001", "Demo", 10_000L, 1L),
        )
        val second = transport.deliver(event)

        assertEquals(DeliveryResult.Delivered("evt_9"), second)
        assertEquals("authority", transport.name)
    }
}
