package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.outbox.DeliveryResult
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The production HTTP sender, against a **real** socket.
 *
 * Every other test in this package drives a fake, which can prove the protocol logic but not that a
 * request is ever actually written. This one runs a real HTTP/1.1 responder on a loopback port — the
 * Android unit-test classpath has `java.net` but not `com.sun.net.httpserver`, so the few lines of
 * framing below are the price of not adding a test-only HTTP client dependency — and then asserts on
 * the method, path, headers and body it received.
 *
 * It is what the opt-in live-backend test repeats against the real Python service; this one runs
 * everywhere, always, so the wire format is never untested on an ordinary build.
 */
class AuthorityHttpServerTest {

    private data class Received(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )

    /**
     * The smallest correct HTTP/1.1 server that a client will talk to: one connection per request,
     * `Content-Length` framing, `Connection: close`.
     */
    private class MiniHttpServer {
        private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val received = CopyOnWriteArrayList<Received>()
        private val worker = Thread({ loop() }, "authority-test-http").apply { isDaemon = true }

        @Volatile
        var responder: (Received) -> Pair<Int, String> = { 200 to "{}" }

        val port: Int get() = serverSocket.localPort

        val requests: List<Received> get() = received.toList()

        fun start() = worker.start()

        fun stop() {
            runCatching { serverSocket.close() }
        }

        private fun loop() {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (closed: IOException) {
                    return
                }
                try {
                    socket.use { handle(it) }
                } catch (ignored: IOException) {
                    // The client hung up; the test asserts on what it did receive.
                }
            }
        }

        private fun handle(socket: Socket) {
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "GET" }
            val target = parts.getOrElse(1) { "/" }

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(bytes, read, length - read)
                    if (count < 0) break
                    read += count
                }
                String(bytes, 0, read, Charsets.UTF_8)
            } else {
                ""
            }

            val request = Received(
                method = method,
                path = target.substringBefore('?'),
                authorization = headers["authorization"],
                contentType = headers["content-type"],
                body = body,
            )
            received += request

            val (status, text) = responder(request)
            val payload = text.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val output = socket.getOutputStream()
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(payload)
            output.flush()
        }

        private fun readLine(input: InputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
                if (byte == '\r'.code) {
                    input.read()
                    return builder.toString()
                }
                if (byte == '\n'.code) return builder.toString()
                builder.append(byte.toChar())
            }
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }

    private lateinit var server: MiniHttpServer
    private lateinit var baseUrl: String

    @Before
    fun startServer() {
        server = MiniHttpServer().apply { start() }
        baseUrl = "127.0.0.1:${server.port}"
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun client() = AuthorityClient(
        baseUrl = { baseUrl },
        http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000),
    )

    private val event = OutboxEvent(
        dedupeKey = "sos:1000",
        kind = OutboxKind.SOS,
        payload = """{"type":"SOS","simulated":true,"timestampMillis":1000,"latitude":18.5,"longitude":73.9}""",
        createdAtMillis = 1000L,
        origin = OutboxOrigin.SIMULATED_WATCH,
    )

    @Test
    fun `a sync batch really reaches the server with the expected bytes`() = runBlocking {
        server.responder = {
            200 to """{"results":[{"dedupeKey":"sos:1000","status":"DELIVERED",
                "serverEventId":"evt_1","acknowledgementId":"evt_1"}],"serverTimeMillis":1}"""
        }

        val outcomes = client().syncEvents("tok_1", listOf(event))

        assertTrue("expected a body from the server, got $outcomes", outcomes is AuthorityCall.Ok)
        assertEquals(
            AuthoritySyncStatus.DELIVERED,
            (outcomes as AuthorityCall.Ok).value.single().status,
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sync/events", request.path)
        assertEquals("Bearer tok_1", request.authorization)
        assertTrue("content type was ${request.contentType}", request.contentType!!.startsWith("application/json"))
        val body = Json.parse(request.body)
        val entry = body.array("events").single()
        assertEquals("sos:1000", entry.string("dedupeKey"))
        assertEquals("SOS", entry.string("kind"))
        assertEquals(true, entry.objectOrNull("payload")!!.boolean("simulated"))
    }

    @Test
    fun `registration really reaches the server, unauthenticated, and returns the minted id`() = runBlocking {
        server.responder = {
            201 to """{"tourist":{"touristId":"TR-2026-00124","displayName":"Nadia Qureshi",
                "phone":"+91 90000 11111","registeredAtMillis":1730000000000,
                "registrationSource":"SELF","emergencyContactCount":1},
                "note":"Registration complete.","serverTimeMillis":1730000000000}"""
        }

        val registration = client().register(
            RegistrationDraft(
                displayName = "Nadia Qureshi",
                phone = "+91 90000 11111",
                pin = "4821",
                contacts = listOf(EmergencyContact(name = "Asha", phone = "112", relationship = "Sister")),
            ),
            deviceId = "Pixel 7",
        )

        assertTrue("expected an id back, got $registration", registration is AuthorityCall.Ok)
        assertEquals("TR-2026-00124", (registration as AuthorityCall.Ok).value.touristId)

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/register", request.path)
        // The point of the route: a tourist has no credential until they register, so the request
        // that creates the account goes out with no Authorization header at all.
        assertNull("registration must not send a bearer token", request.authorization)
        val body = Json.parse(request.body)
        assertEquals("tourist", body.string("role"))
        assertEquals("4821", body.string("pin"))
        assertNull("a registration request never carries a password", body.string("password"))
        assertEquals("Asha", body.array("contacts").single().string("name"))
    }

    @Test
    fun `a 409 duplicate phone surfaces the server's own message`() = runBlocking {
        server.responder = {
            409 to """{"error":"conflict","message":"An account is already registered with that phone number."}"""
        }

        val result = client().register(
            RegistrationDraft("Nadia", "+91 90000 11111", "4821", emptyList()),
            deviceId = null,
        )

        assertTrue("expected a conflict, got $result", result is AuthorityCall.HttpError)
        val error = result as AuthorityCall.HttpError
        assertEquals(409, error.status)
        assertEquals("conflict", error.code)
        assertEquals("An account is already registered with that phone number.", error.message)
    }

    @Test
    fun `the transport delivers through a real socket`() = runBlocking {        server.responder = {
            200 to """{"results":[{"dedupeKey":"sos:1000","status":"DELIVERED","serverEventId":"evt_7",
                "acknowledgementId":"evt_7"}],"serverTimeMillis":1}"""
        }
        val store = InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = baseUrl,
                token = "tok_1",
                role = AuthorityRoles.TOURIST,
                subjectId = "T-1001",
                expiresAtMillis = System.currentTimeMillis() + 60_000,
            ),
        )
        val transport = AuthoritySyncTransport(
            sessions = store,
            client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = HttpUrlConnectionAuthorityHttp()),
        )

        assertEquals(DeliveryResult.Delivered("evt_7"), transport.deliver(event))
        assertEquals("/api/v1/sync/events", server.requests.single().path)
    }

    @Test
    fun `a 401 from a real server keeps the event queued`() = runBlocking {
        server.responder = { 401 to """{"error":"unauthorized","message":"Sign in first."}""" }
        val store = InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = baseUrl,
                token = "stale",
                expiresAtMillis = System.currentTimeMillis() + 60_000,
            ),
        )
        val transport = AuthoritySyncTransport(
            sessions = store,
            client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = HttpUrlConnectionAuthorityHttp()),
        )

        val result = transport.deliver(event)

        // The *status* is what must drive the decision, and it does: a 401 leaves the event queued so
        // re-signing-in will deliver it. The reason text is asserted only loosely here, on purpose:
        // the JDK's HttpURLConnection replays a POST that receives 401 (its Authenticator path) and
        // discards the error body while doing so, whereas Android's does not. Asserting the body would
        // therefore be asserting a JVM quirk. That the server's own reason reaches the caller on a
        // normal error is proven by the 400 test below, and per-event rules arrive as a 200 +
        // REJECTED verdict, asserted in `a rejected payload from a real server fails the event`.
        assertTrue("expected a retry, got $result", result is DeliveryResult.Retry)
        assertTrue(
            "the 401 should be explained as a sign-in problem: ${(result as DeliveryResult.Retry).reason}",
            result.reason.contains("401") || result.reason.contains("Sign in"),
        )
    }

    @Test
    fun `a 400 body from a real server reaches the caller so the operator sees why`() = runBlocking {
        server.responder = {
            400 to """{"error":"bad_request","message":"events must be a non-empty list"}"""
        }
        val store = InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = baseUrl,
                token = "tok_1",
                expiresAtMillis = System.currentTimeMillis() + 60_000,
            ),
        )
        val transport = AuthoritySyncTransport(
            sessions = store,
            client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = HttpUrlConnectionAuthorityHttp()),
        )

        val result = transport.deliver(event)

        assertEquals(
            DeliveryResult.Rejected("events must be a non-empty list"),
            result,
        )
    }

    @Test
    fun `a rejected payload from a real server fails the event with the reason`() = runBlocking {
        server.responder = {
            200 to """{"results":[{"dedupeKey":"sos:1000","status":"REJECTED",
                "reason":"latitude must be between -90 and 90"}]}"""
        }

        val outcomes = client().syncEvents("tok_1", listOf(event))

        val outcome = (outcomes as AuthorityCall.Ok).value.single()
        assertEquals(AuthoritySyncStatus.REJECTED, outcome.status)
        assertEquals("latitude must be between -90 and 90", outcome.reason)
    }

    @Test
    fun `login posts the credentials and reads the token back`() = runBlocking {
        server.responder = {
            200 to """{"token":"tok_live","role":"tourist","subjectId":"T-1001","displayName":"Demo Tourist",
                "issuedAtMillis":1,"expiresAtMillis":99,"serverTimeMillis":1}"""
        }

        val result = client().login(AuthorityRoles.TOURIST, "T-1001", "s3cret", "test-device")

        assertEquals("tok_live", (result as AuthorityCall.Ok).value.token)
        val request = server.requests.single()
        assertEquals("/api/v1/auth/login", request.path)
        assertEquals("s3cret", Json.parse(request.body).string("password"))
        assertNull("login must not send an old token", request.authorization)
    }

    @Test
    fun `a health check reports the real reachability of the port`() = runBlocking {
        server.responder = { 200 to """{"status":"ok","version":"1.0.0"}""" }

        assertTrue(client().health() is AuthorityCall.Ok)
        assertEquals("/api/v1/health", server.requests.single().path)
    }

    @Test
    fun `a closed port is Unreachable rather than an exception escaping`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = AuthorityClient(
            baseUrl = { "127.0.0.1:$closedPort" },
            http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000),
        )

        val result = offline.health()

        assertTrue("expected Unreachable, was $result", result is AuthorityCall.Unreachable)
    }

    @Test
    fun `a gateway error page is an HttpError, never a success`() = runBlocking {
        server.responder = { 502 to "<html><body>Bad gateway</body></html>" }

        val result = client().health()

        assertTrue("expected an HttpError, was $result", result is AuthorityCall.HttpError)
        assertEquals(502, (result as AuthorityCall.HttpError).status)
    }

    @Test
    fun `a truncated json body is a protocol error, not a delivery`() = runBlocking {
        server.responder = { 200 to """{"results":[{"dedupeKey":"sos:1000","st""" }

        val result = client().syncEvents("tok_1", listOf(event))

        assertTrue("expected Protocol, was $result", result is AuthorityCall.Protocol)
    }

    @Test
    fun `the officer register is fetched with the officer bearer token and parsed end to end`() = runBlocking {
        server.responder = {
            200 to """{"tourists":[{"touristId":"TR-2026-00124","displayName":"Nadia Qureshi",
                "phone":"+91 90000 11111","createdAtMillis":1730000000000,
                "registrationSource":"SELF","registeredAtMillis":1730000000000,
                "registrationStatus":"REGISTERED","trackingStatus":"AWAITING_FIRST_REPORT",
                "lastSeenMillis":null,"hasPosition":false,"latestPosition":null,
                "emergencyContactCount":1,"openSosCount":0,"latestRiskBand":null,
                "latestRiskAtMillis":null,"state":"STALE"}],"serverTimeMillis":1730000000000}"""
        }

        val result = client().authorityTourists("officer_token")

        assertTrue("expected the register back, got $result", result is AuthorityCall.Ok)
        val row = (result as AuthorityCall.Ok).value.single()
        assertEquals("TR-2026-00124", row.touristId)
        assertEquals("Nadia Qureshi", row.displayName)

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/authority/tourists", request.path)
        assertEquals("Bearer officer_token", request.authorization)
    }

    @Test
    fun `a tourist token refused by the officer route is an HttpError, not an empty register`() = runBlocking {
        // This is the security claim, expressed over a real socket: the server refuses, and the app
        // must report the refusal rather than showing an empty list as if everything were fine.
        server.responder = {
            403 to """{"error":"forbidden","message":"This route requires the authority role."}"""
        }

        val result = client().authorityTourists("tourist_token")

        assertTrue("expected a 403, was $result", result is AuthorityCall.HttpError)
        assertEquals(403, (result as AuthorityCall.HttpError).status)
    }
}
