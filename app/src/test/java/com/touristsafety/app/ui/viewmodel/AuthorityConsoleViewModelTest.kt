package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityConnectionState
import com.touristsafety.app.authority.AuthorityHttpRequest
import com.touristsafety.app.authority.AuthorityHttpResponse
import com.touristsafety.app.authority.AuthorityLoginResult
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import com.touristsafety.app.testing.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The officer console, driven through its ViewModel.
 *
 * What is under test is not "does the dashboard draw" but the handful of claims that make it
 * trustworthy: the console reads the authority's numbers rather than computing its own, an
 * acknowledgement that the server refused is **not** shown as done, a forecast never lands in the
 * incident list, and one dead feed does not blank the other three.
 */
@RunWith(AndroidJUnit4::class)
class AuthorityConsoleViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val future = System.currentTimeMillis() + 3_600_000L

    // ------------------------------------------------------------ fixtures

    private fun overviewBody(sosOpen: Int = 2): String = """
        {"counts":{"tourists":5,"events":27,"eventsLast24h":27,"sosTotal":6,"sosOpen":$sosOpen,
        "sosAcknowledged":4,"zoneAlerts":1,"emergencyContacts":2,"riskAssessments":11,
        "riskElevated":9,"touristsWithElevatedRiskLast24h":3},
        "tourists":[{"touristId":"T-1001","displayName":"Demo Tourist","latitude":12.9716,
        "longitude":77.5946,"accuracyMeters":5.0,"positionRecordedAtMillis":1730000000000,
        "lastSeenMillis":1730000001000,"lastEventAtMillis":1730000002000,"openSosCount":1,
        "emergencyContactCount":1,"simulated":false,"state":"SOS","latestRiskBand":"HIGH",
        "latestRiskProbability":0.83,"latestRiskAtMillis":1730000003000}],
        "serverTimeMillis":1730000010000}
    """.trimIndent()

    private fun sosRowBody(
        status: String = "OPEN",
        acknowledgedBy: String? = null,
    ): String {
        val acknowledgedAt = if (acknowledgedBy == null) "null" else "1730000009000"
        val by = if (acknowledgedBy == null) "null" else "\"$acknowledgedBy\""
        val note = if (acknowledgedBy == null) "null" else "\"On our way.\""
        return """
            {"sosId":6,"serverEventId":"srv-6","touristId":"T-1001","displayName":"Demo Tourist",
            "latitude":12.9716,"longitude":77.5946,"accuracyMeters":5.0,"raisedAtMillis":1730000000000,
            "receivedAtMillis":1730000000500,"status":"$status","simulated":false,
            "acknowledgedAtMillis":$acknowledgedAt,"acknowledgedBy":$by,"note":$note}
        """.trimIndent()
    }

    private fun sosBody(status: String = "OPEN", acknowledgedBy: String? = null): String =
        """{"sos":[${sosRowBody(status, acknowledgedBy)}],"serverTimeMillis":1730000010000}"""

    private val riskBody = """
        {"risk":[{"kind":"PREDICTION","touristId":"T-1001","assessedAtMillis":1730000000000,
        "receivedAtMillis":1730000000500,"riskBand":"HIGH","probability":0.83,"horizonSeconds":45.0,
        "modelVersion":"fall-risk-1.2","observedSamples":64,"windowSamples":80,"dataOk":true,
        "dataReason":null,"factors":[],"disclaimer":"A forecast, not an incident."}],
        "serverTimeMillis":1730000010000}
    """.trimIndent()

    private val eventsBody = """
        {"events":[{"serverEventId":"e-1","touristId":"T-1001","dedupeKey":"k","kind":"ZONE_ALERT",
        "origin":"GPS","simulated":false,"latitude":12.97,"longitude":77.59,"accuracyMeters":5.0,
        "zoneId":7,"zoneName":"Old quarry","zoneType":"RESTRICTED","eventType":"ENTRY",
        "deviceTimestampMillis":1730000000000,"receivedAtMillis":1730000000100}],
        "serverTimeMillis":1730000010000}
    """.trimIndent()

    private fun officerLogin() = AuthorityLoginResult(
        token = "officer-token",
        role = "authority",
        subjectId = "A-01",
        displayName = "Duty Officer",
        expiresAtMillis = future,
        serverTimeMillis = 1_730_000_000_000L,
    )

    /** A client whose individual routes can be made to fail. */
    private fun http(
        overviewStatus: Int = 200,
        sosStatus: Int = 200,
        riskStatus: Int = 200,
        eventsStatus: Int = 200,
        acknowledgement: String = sosRowBody(status = "ACKNOWLEDGED", acknowledgedBy = "A-01"),
    ): FakeAuthorityHttp {
        val http = FakeAuthorityHttp()
        http.respondWith { request: AuthorityHttpRequest ->
            when {
                request.url.contains("/authority/sos/") && request.method == "POST" ->
                    AuthorityHttpResponse(200, """{"sos":$acknowledgement}""")

                request.url.contains("/authority/sos") -> AuthorityHttpResponse(sosStatus, sosBody())

                request.url.contains("/authority/overview") ->
                    AuthorityHttpResponse(overviewStatus, overviewBody())

                request.url.contains("/authority/risk") -> AuthorityHttpResponse(riskStatus, riskBody)

                request.url.contains("/authority/events") -> AuthorityHttpResponse(eventsStatus, eventsBody)

                request.url.endsWith("/api/v1/health") ->
                    AuthorityHttpResponse(200, """{"status":"ok","version":"1.0.0"}""")

                else -> AuthorityHttpResponse(404, """{"error":"not_found"}""")
            }
        }
        return http
    }

    private fun viewModel(
        http: FakeAuthorityHttp,
        signedIn: Boolean = true,
        baseUrl: String = "192.168.1.20:8080",
    ): Pair<AuthorityConsoleViewModel, InMemoryAuthoritySessionStore> {
        val store = InMemoryAuthoritySessionStore(AuthoritySession(baseUrl = baseUrl))
        if (signedIn) {
            runBlocking { store.saveOfficerLogin(baseUrl, officerLogin()) }
        }
        val client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)
        return AuthorityConsoleViewModel(store, client) to store
    }

    // ------------------------------------------------------------ reads

    @Test
    fun `refresh reads all four feeds and shows the authority's own counters`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.refresh()

        assertEquals(2, viewModel.overview.value!!.counts.sosOpen)
        assertEquals(1, viewModel.overview.value!!.tourists.size)
        assertEquals(1, viewModel.sos.value.size)
        assertEquals(1, viewModel.risk.value.size)
        assertEquals(1, viewModel.events.value.size)
        assertTrue(viewModel.loaded.value)
        assertNotNull("an \"as of\" time is a real timestamp", viewModel.lastRefreshedAtMillis.value)
        assertEquals(AuthorityConnectionState.CONNECTED, viewModel.connection.value)

        // The four reads carry the *officer* token; the tourist token is a 403 on these routes.
        val consoleCalls = http.requests.filter { it.url.contains("/authority/") }
        assertEquals(4, consoleCalls.size)
        assertTrue(consoleCalls.all { it.bearerToken == "officer-token" })
    }

    @Test
    fun `an incident and a forecast stay in separate lists`() {
        val (viewModel, _) = viewModel(http())

        viewModel.refresh()

        assertEquals(1, viewModel.sos.value.size)
        assertEquals(1, viewModel.risk.value.size)
        assertEquals("OPEN", viewModel.sos.value.single().status)
        assertEquals("HIGH", viewModel.risk.value.single().riskBand)
        // Nothing in the incident list came from the forecast feed, and vice versa.
        assertEquals("srv-6", viewModel.sos.value.single().serverEventId)
        assertEquals("fall-risk-1.2", viewModel.risk.value.single().modelVersion)
    }

    @Test
    fun `one failing feed does not blank the others`() {
        val (viewModel, _) = viewModel(http(riskStatus = 500))

        viewModel.refresh()

        assertEquals("the counters still arrive", 5, viewModel.overview.value!!.counts.tourists)
        assertEquals("and so do the incidents", 1, viewModel.sos.value.size)
        assertEquals("and the event feed", 1, viewModel.events.value.size)
        assertTrue(viewModel.risk.value.isEmpty())
        assertTrue(
            "the failure must be named, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("Predictions"),
        )
        assertTrue(viewModel.message.value!!.contains("500"))
    }

    @Test
    fun `an unreachable authority is named rather than left unexplained`() {
        val http = http()
        http.failure = java.io.IOException("Connection refused")
        val (viewModel, _) = viewModel(http)

        viewModel.refresh()

        assertTrue(
            "the message must name the address, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("192.168.1.20:8080"),
        )
        assertEquals(AuthorityConnectionState.OFFLINE, viewModel.connection.value)
    }

    @Test
    fun `refreshing without a session asks for a sign-in and calls nothing`() {
        val http = http()
        val (viewModel, _) = viewModel(http, signedIn = false)

        viewModel.refresh()

        assertEquals("Sign in first.", viewModel.message.value)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a refused token drops the officer session instead of leaving a console that cannot work`() {
        val (viewModel, store) = viewModel(http(overviewStatus = 403))

        viewModel.refresh()

        assertNull(runBlocking { store.currentOfficer().token })
        assertNull("the stale dashboard must not stay on screen", viewModel.overview.value)
        assertTrue(viewModel.sos.value.isEmpty())
        assertTrue(
            "a refusal must be reported as a refusal, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("refused"),
        )
    }

    // ------------------------------------------------------------ acknowledgement

    @Test
    fun `acknowledging applies the row the server returned, not a local guess`() {
        val http = http()
        val (viewModel, _) = viewModel(http)
        // The "All" view, so the acknowledged row stays visible and can be inspected directly.
        viewModel.setSosFilter(SosFilter.ALL)
        viewModel.refresh()
        assertEquals("OPEN", viewModel.sos.value.single().status)
        assertNull(viewModel.sos.value.single().acknowledgedBy)

        viewModel.acknowledge(sosId = 6, note = "On our way.")

        val row = viewModel.sos.value.single()
        assertEquals("ACKNOWLEDGED", row.status)
        assertEquals("the server records who acknowledged it", "A-01", row.acknowledgedBy)
        assertEquals(1_730_000_009_000L, row.acknowledgedAtMillis)
        assertEquals("and the note it stored", "On our way.", row.note)

        val post = http.requests.last()
        assertEquals("POST", post.method)
        assertTrue(post.url.endsWith("/api/v1/authority/sos/6/ack"))
        assertTrue(post.body!!.contains("On our way."))
        assertTrue(
            "the caller must not be able to claim a name: ${post.body}",
            !post.body!!.contains("acknowledgedBy") && !post.body!!.contains("A-01"),
        )
    }

    @Test
    fun `acknowledging removes the incident from the open view`() {
        val http = http()
        val (viewModel, _) = viewModel(http)
        viewModel.setSosFilter(SosFilter.OPEN)
        viewModel.refresh()
        assertEquals(1, viewModel.sos.value.size)

        viewModel.acknowledge(sosId = 6, note = null)

        assertTrue(
            "an acknowledged incident is not waiting for acknowledgement any more",
            viewModel.sos.value.isEmpty(),
        )
    }

    @Test
    fun `a refused acknowledgement is reported and the incident stays open`() {
        val http = http()
        val (viewModel, _) = viewModel(http)
        viewModel.refresh()

        http.respondWith {
            AuthorityHttpResponse(500, """{"error":"server_error","message":"boom"}""")
        }
        viewModel.acknowledge(sosId = 6, note = null)

        assertEquals(
            "the incident must not be marked acknowledged just because the app asked",
            "OPEN",
            viewModel.sos.value.single().status,
        )
        assertTrue(viewModel.message.value!!.contains("500"))
    }

    @Test
    fun `changing the filter re-reads incidents with the server's own filter value`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.setSosFilter(SosFilter.ACKNOWLEDGED)

        val call = http.requests.last()
        assertTrue(
            "the filter is the server's vocabulary, not the app's: ${call.url}",
            call.url.endsWith("/api/v1/authority/sos?status=ACKNOWLEDGED"),
        )
    }

    // ------------------------------------------------------------ connection probe

    @Test
    fun `the connection probe actually calls the authority and reports the answer`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.testConnection()

        assertEquals(AuthorityConnectionState.CONNECTED, viewModel.connection.value)
        assertTrue(http.requests.single().url.endsWith("/api/v1/health"))
        assertTrue(viewModel.message.value!!.contains("192.168.1.20:8080"))
    }

    @Test
    fun `a blank address is not reported as reachable`() {
        val http = http()
        val (viewModel, _) = viewModel(http, baseUrl = "   ")

        viewModel.testConnection()

        assertEquals(AuthorityConnectionState.NOT_CONFIGURED, viewModel.connection.value)
        assertTrue(http.requests.isEmpty())
    }
}
