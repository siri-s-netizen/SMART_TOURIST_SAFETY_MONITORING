package com.touristsafety.app.authority

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.domain.model.OutboxStatus
import com.touristsafety.app.outbox.OutboxSyncer
import com.touristsafety.app.outbox.OutboxPayloads
import com.touristsafety.app.testing.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end: the production Android transport against the **real** Python authority service.
 *
 * ## It is opt-in, and it is the only test that proves the integration
 *
 * With no backend published for the run the test skips, so an ordinary `testDebugUnitTest` never needs
 * a server. When one is published it does the whole journey with production code on both sides:
 *
 * 1. the production [AuthorityClient] signs in over a real socket and receives a real token;
 * 2. an SOS is written to a real (in-memory) Room outbox by the same code path the watch uses;
 * 3. the production [OutboxSyncer] drains it through the production [AuthoritySyncTransport];
 * 4. `GET /me` — the server's own view — is read back;
 * 5. **the duty officer's API is signed in to separately** and asked for its SOS queue, so "it
 *    arrived" is confirmed from the authority's side rather than from the phone's own claim;
 * 6. the same event is delivered a second time and the authority still holds exactly one row for it,
 *    which is server-side idempotency demonstrated rather than asserted.
 *
 * A fifth test drives an **on-device future-risk summary** the same way — the production payload
 * builder and queue, a real RISK row on the server, read back from `/authority/risk` and labelled
 * `PREDICTION` on the wire — and asserts it is counted as elevated risk while the **SOS total does not
 * move**: a forecast is not an incident.
 *
 * Run it with:
 *
 * ```
 * python backend/run.py --database backend/var/e2e.db
 * gradlew :app:testDebugUnitTest --tests "com.touristsafety.app.authority.AuthorityLiveBackendTest" `
 *   -Dtsm.e2e.baseUrl=http://127.0.0.1:8080 `
 *   -Dtsm.e2e.credentials=D:\TouristSafetyMonitoring-src\backend\var\DEV_CREDENTIALS.txt
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AuthorityLiveBackendTest {

    private lateinit var database: AppDatabase
    private lateinit var outbox: OutboxRepository

    private val baseUrl: String =
        (System.getProperty("tsm.e2e.baseUrl") ?: "").trim().takeIf { it.isNotEmpty() } ?: ""

    private val credentialsPath: String =
        (System.getProperty("tsm.e2e.credentials") ?: "").trim().takeIf { it.isNotEmpty() } ?: ""

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        outbox = OutboxRepository(database.outboxEventDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun client() = AuthorityClient(
        baseUrl = { baseUrl },
        http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 5_000, readTimeoutMillis = 15_000),
    )

    /** The credentials file the server writes on its first start: `role<TAB>id<TAB>password`. */
    private fun credentials(): Map<String, Pair<String, String>> {
        val file = File(credentialsPath)
        assumeTrue(
            "Set -Dtsm.e2e.credentials=<path to DEV_CREDENTIALS.txt> to run the live test",
            file.isFile,
        )
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) null else parts[0] to (parts[1] to parts[2])
            }
            .toMap()
    }

    @Test
    fun `an SOS raised on the phone reaches the authority, persists, and is never duplicated`() = runBlocking {
        assumeTrue(
            "Set -Dtsm.e2e.baseUrl=http://host:port to run the live test",
            baseUrl.isNotEmpty(),
        )
        val accounts = credentials()
        val tourist = accounts["tourist"]
        val officer = accounts["authority"]
        assertTrue("the credentials file has no tourist account", tourist != null)
        assertTrue("the credentials file has no authority account", officer != null)

        // ---- 1. the service is up, and says so ------------------------------------------------
        val health = client().health()
        assertTrue("health check failed: $health", health is AuthorityCall.Ok)
        println("[e2e] health OK at $baseUrl")

        val http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 5_000, readTimeoutMillis = 15_000)
        val touristClient = AuthorityClient(baseUrl = { baseUrl }, http = http)

        // ---- 2. sign in exactly as the phone does --------------------------------------------
        val login = touristClient.login(AuthorityRoles.TOURIST, tourist!!.first, tourist.second, "e2e-test-device")
        assertTrue("tourist login failed: $login", login is AuthorityCall.Ok)
        val token = (login as AuthorityCall.Ok).value.token
        println("[e2e] signed in as ${login.value.subjectId} (token length ${token.length})")

        val sessions = com.touristsafety.app.testing.InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = baseUrl,
                token = token,
                role = AuthorityRoles.TOURIST,
                subjectId = login.value.subjectId,
                displayName = login.value.displayName,
                expiresAtMillis = login.value.expiresAtMillis,
                signedInAtMillis = login.value.serverTimeMillis,
            ),
        )

        // ---- 3. raise an SOS into the real outbox --------------------------------------------
        // Unique per run so the test can be run repeatedly against one server without colliding.
        val raisedAt = System.currentTimeMillis()
        val sosPayload = OutboxPayloads.sos(
            reading = com.touristsafety.app.domain.model.GeoReading(
                point = com.touristsafety.app.domain.model.GeoPoint(18.5204, 73.8567),
                accuracyMeters = 6f,
                timestampMillis = raisedAt,
                source = com.touristsafety.app.domain.model.LocationSource.SIMULATION,
            ),
            timestampMillis = raisedAt,
        )
        val dedupeKey = OutboxPayloads.sosDedupeKey(raisedAt)
        val event = OutboxEvent(
            dedupeKey = dedupeKey,
            kind = OutboxKind.SOS,
            payload = sosPayload,
            createdAtMillis = raisedAt,
            origin = OutboxOrigin.SIMULATED_WATCH,
        )
        assertTrue("the SOS should be new to the queue", outbox.enqueue(event))

        // ---- 4. deliver through the production queue and transport ---------------------------
        val transport = AuthoritySyncTransport(
            sessions = sessions,
            client = AuthorityClient(baseUrl = { sessions.current().baseUrl }, http = http),
        )
        val report = OutboxSyncer(outbox, transport).flush()

        println("[e2e] flush report: $report")
        assertEquals("exactly one event should have been inspected", 1, report.inspected)
        assertEquals("the SOS should have been delivered, not just queued", 1, report.delivered)
        assertEquals(0, report.retried)
        assertEquals(0, report.rejected)
        assertEquals(
            "the outbox row must be terminal SENT",
            OutboxStatus.SENT,
            outbox.findByDedupeKey(dedupeKey)?.status,
        )

        // The acknowledgement id the server returned for *our* event.
        val deliveryResult = transport.deliver(event)
        assertTrue("a re-delivery should be acknowledged, not refused: $deliveryResult", deliveryResult is com.touristsafety.app.outbox.DeliveryResult.Delivered)
        val serverEventId = (deliveryResult as com.touristsafety.app.outbox.DeliveryResult.Delivered).acknowledgementId
        assertTrue("the server should have returned a serverEventId", !serverEventId.isNullOrBlank())
        println("[e2e] server event id: $serverEventId (re-delivery reported DELIVERED)")

        // ---- 5. the tourist's own view of what the authority holds ---------------------------
        val profile = touristClient.profile(token)
        assertTrue("GET /me failed: $profile", profile is AuthorityCall.Ok)
        val me = (profile as AuthorityCall.Ok).value
        assertTrue("the authority should have recorded at least this event", me.deliveredEventCount >= 1)
        assertTrue("the authority should show a latest SOS", me.latestSos != null)
        println("[e2e] /me: delivered=${me.deliveredEventCount} latestSos=${me.latestSos?.status} simulated=${me.latestSos?.simulated}")
        assertEquals("OPEN", me.latestSos!!.status)
        assertTrue("the SOS must be flagged simulated, never as a real one", me.latestSos!!.simulated)

        // ---- 6. the duty officer's own API, signed in separately -----------------------------
        val officerLogin = touristClient.login(AuthorityRoles.AUTHORITY, officer!!.first, officer.second, "e2e-officer")
        assertTrue("authority login failed: $officerLogin", officerLogin is AuthorityCall.Ok)
        val officerToken = (officerLogin as AuthorityCall.Ok).value.token

        val sosList = http.execute(
            AuthorityHttpRequest(
                method = "GET",
                url = AuthorityUrls.resolve(baseUrl, "/api/v1/authority/sos?status=ALL"),
                bearerToken = officerToken,
            ),
        )
        assertEquals(200, sosList.status)
        val sosRows = Json.parse(sosList.body).array("sos")
        val matching = sosRows.filter { it.string("serverEventId") == serverEventId }
        println("[e2e] officer queue holds ${sosRows.size} SOS row(s); ours appears ${matching.size} time(s)")
        assertEquals(
            "the server must hold exactly one row for our event, however often it was delivered",
            1,
            matching.size,
        )
        assertEquals("OPEN", matching.single().string("status"))
        assertEquals(login.value.subjectId, matching.single().string("touristId"))
        assertEquals(true, matching.single().boolean("simulated"))

        // ---- 7. the officer's overview counts it ---------------------------------------------
        val overview = http.execute(
            AuthorityHttpRequest(
                method = "GET",
                url = AuthorityUrls.resolve(baseUrl, AuthorityEndpoints.AUTHORITY_OVERVIEW),
                bearerToken = officerToken,
            ),
        )
        assertEquals(200, overview.status)
        val counts = Json.parse(overview.body).objectOrNull("counts")!!
        println("[e2e] overview counts: ${Json.write(counts)}")
        assertTrue("the overview should count at least one SOS", (counts.int("sosTotal") ?: 0) >= 1)
    }

    @Test
    fun `a dead backend leaves the SOS queued and reports why`() = runBlocking {
        assumeTrue("needs a configured base URL to be meaningful", baseUrl.isNotEmpty())
        // A port nothing is listening on, to prove the honest-failure path against a real socket.
        val sessions = com.touristsafety.app.testing.InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = "127.0.0.1:1",
                token = "tok",
                expiresAtMillis = System.currentTimeMillis() + 60_000,
            ),
        )
        val transport = AuthoritySyncTransport(
            sessions = sessions,
            client = AuthorityClient(
                baseUrl = { sessions.current().baseUrl },
                http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 1_500, readTimeoutMillis = 1_500),
            ),
        )
        val raisedAt = System.currentTimeMillis()
        val event = OutboxEvent(
            dedupeKey = OutboxPayloads.sosDedupeKey(raisedAt),
            kind = OutboxKind.SOS,
            payload = OutboxPayloads.sos(null, raisedAt),
            createdAtMillis = raisedAt,
            origin = OutboxOrigin.SIMULATED_WATCH,
        )
        assertTrue(outbox.enqueue(event))

        val report = OutboxSyncer(outbox, transport).flush()

        assertEquals(1, report.retried)
        assertEquals(0, report.delivered)
        assertEquals(
            "a backend that is down must leave the row PENDING, never SENT",
            OutboxStatus.PENDING,
            outbox.findByDedupeKey(event.dedupeKey)?.status,
        )
        val reason = outbox.findByDedupeKey(event.dedupeKey)?.lastError
        println("[e2e] queued after a dead backend, reason: $reason")
        assertTrue("the row should explain itself: $reason", !reason.isNullOrBlank())
    }

    @Test
    fun `a routine position report reaches the authority's map`() = runBlocking {
        assumeTrue("Set -Dtsm.e2e.baseUrl=http://host:port to run the live test", baseUrl.isNotEmpty())
        val tourist = credentials()["tourist"]!!
        val http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 5_000, readTimeoutMillis = 15_000)
        val client = AuthorityClient(baseUrl = { baseUrl }, http = http)

        val login = client.login(AuthorityRoles.TOURIST, tourist.first, tourist.second, "e2e-position")
        assertTrue("tourist login failed: $login", login is AuthorityCall.Ok)
        val token = (login as AuthorityCall.Ok).value.token

        val sessions = com.touristsafety.app.testing.InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = baseUrl,
                token = token,
                role = AuthorityRoles.TOURIST,
                subjectId = login.value.subjectId,
                displayName = login.value.displayName,
                expiresAtMillis = login.value.expiresAtMillis,
                signedInAtMillis = login.value.serverTimeMillis,
            ),
        )

        // A unique fix timestamp per run, and a *real GPS* source so it is not flagged simulated.
        val at = System.currentTimeMillis()
        val reading = com.touristsafety.app.domain.model.GeoReading(
            point = com.touristsafety.app.domain.model.GeoPoint(18.5204, 73.8567),
            accuracyMeters = 6f,
            timestampMillis = at,
            source = com.touristsafety.app.domain.model.LocationSource.GPS,
        )
        val dedupeKey = OutboxPayloads.positionDedupeKey(at)
        assertTrue(
            outbox.enqueue(
                OutboxEvent(
                    dedupeKey = dedupeKey,
                    kind = OutboxKind.POSITION,
                    payload = OutboxPayloads.position(reading, at),
                    createdAtMillis = at,
                    origin = OutboxOrigin.GPS,
                ),
            ),
        )

        val transport = AuthoritySyncTransport(
            sessions = sessions,
            client = AuthorityClient(baseUrl = { sessions.current().baseUrl }, http = http),
        )
        val report = OutboxSyncer(outbox, transport).flush()
        assertEquals("the position report should be delivered", 1, report.delivered)
        assertEquals(OutboxStatus.SENT, outbox.findByDedupeKey(dedupeKey)?.status)

        val me = (client.profile(token) as AuthorityCall.Ok).value
        val position = me.latestPosition
        assertTrue("the authority should now hold a position for the tourist", position != null)
        println("[e2e] /me latestPosition: ${position!!.latitude},${position.longitude} simulated=${position.simulated}")
        assertEquals(18.5204, position.latitude, 1e-6)
        assertEquals(73.8567, position.longitude, 1e-6)
        assertEquals("a real-GPS report must not be flagged simulated", false, position.simulated)
    }

    @Test
    fun `an on-device future-risk summary reaches the authority and is never an incident`() = runBlocking {
        assumeTrue("Set -Dtsm.e2e.baseUrl=http://host:port to run the live test", baseUrl.isNotEmpty())
        val accounts = credentials()
        val tourist = accounts["tourist"]
        val officer = accounts["authority"]
        assertTrue("the credentials file has no tourist account", tourist != null)
        assertTrue("the credentials file has no authority account", officer != null)

        val http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 5_000, readTimeoutMillis = 15_000)
        val client = AuthorityClient(baseUrl = { baseUrl }, http = http)

        val login = client.login(AuthorityRoles.TOURIST, tourist!!.first, tourist.second, "e2e-risk")
        assertTrue("tourist login failed: $login", login is AuthorityCall.Ok)
        val token = (login as AuthorityCall.Ok).value.token
        val sessions = com.touristsafety.app.testing.InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = baseUrl,
                token = token,
                role = AuthorityRoles.TOURIST,
                subjectId = login.value.subjectId,
                displayName = login.value.displayName,
                expiresAtMillis = login.value.expiresAtMillis,
                signedInAtMillis = login.value.serverTimeMillis,
            ),
        )

        // ---- queue exactly what FutureRiskReporter queues, through the production payload builder ----
        // Unique per run so the test can run repeatedly against one server without colliding.
        val at = System.currentTimeMillis()
        val summary = OutboxPayloads.FutureRiskSummary(
            modelVersion = "future-risk-unimib-v1",
            horizonSeconds = 1.0f,
            riskBand = "HIGH",
            probability = 0.62f,
            windowSamples = 50,
            observedSamples = 50,
            dataQualityOk = true,
            dataQualityReason = null,
            factors = listOf(
                OutboxPayloads.FutureRiskSummaryFactor("Acceleration magnitude", "peak 4.2 g"),
                OutboxPayloads.FutureRiskSummaryFactor("Free-fall fraction", "0.18 of the window"),
            ),
            timestampMillis = at,
        )
        val dedupeKey = OutboxPayloads.futureRiskDedupeKey(at)
        assertTrue(
            "the risk summary should be new to the queue",
            outbox.enqueue(
                OutboxEvent(
                    dedupeKey = dedupeKey,
                    kind = OutboxKind.RISK,
                    payload = OutboxPayloads.futureRisk(summary),
                    createdAtMillis = at,
                    origin = OutboxOrigin.ON_DEVICE_MODEL,
                ),
            ),
        )

        // ---- the authority's own API, signed in separately (before, so the SOS count can be compared)
        val officerLogin = client.login(AuthorityRoles.AUTHORITY, officer!!.first, officer.second, "e2e-officer-risk")
        assertTrue("authority login failed: $officerLogin", officerLogin is AuthorityCall.Ok)
        val officerToken = (officerLogin as AuthorityCall.Ok).value.token

        suspend fun overviewCounts(): com.touristsafety.app.authority.JsonValue.Obj {
            val response = http.execute(
                AuthorityHttpRequest(
                    method = "GET",
                    url = AuthorityUrls.resolve(baseUrl, AuthorityEndpoints.AUTHORITY_OVERVIEW),
                    bearerToken = officerToken,
                ),
            )
            assertEquals(200, response.status)
            return Json.parse(response.body).objectOrNull("counts")!!
        }

        val before = overviewCounts()
        val sosBefore = before.int("sosTotal") ?: 0
        val riskBefore = before.int("riskAssessments") ?: 0

        // ---- deliver through the production queue and transport ----
        val transport = AuthoritySyncTransport(
            sessions = sessions,
            client = AuthorityClient(baseUrl = { sessions.current().baseUrl }, http = http),
        )
        val report = OutboxSyncer(outbox, transport).flush()
        println("[e2e] risk flush report: $report")
        assertEquals("the risk summary should have been delivered", 1, report.delivered)
        assertEquals(0, report.rejected)
        assertEquals(
            "the outbox row must be terminal SENT",
            OutboxStatus.SENT,
            outbox.findByDedupeKey(dedupeKey)?.status,
        )

        val riskResponse = http.execute(
            AuthorityHttpRequest(
                method = "GET",
                url = AuthorityUrls.resolve(baseUrl, "/api/v1/authority/risk?limit=50"),
                bearerToken = officerToken,
            ),
        )
        assertEquals(200, riskResponse.status)
        val rows = Json.parse(riskResponse.body).array("risk")
        val ours = rows.filter { it.long("assessedAtMillis") == at }
        println("[e2e] authority holds ${rows.size} prediction row(s); ours appears ${ours.size} time(s)")
        assertEquals("the server must hold exactly one row for our prediction", 1, ours.size)
        val row = ours.single()
        assertEquals("HIGH", row.string("riskBand"))
        assertEquals("future-risk-unimib-v1", row.string("modelVersion"))
        assertEquals(1.0, row.double("horizonSeconds")!!, 1e-6)
        assertEquals(0.62, row.double("probability")!!, 1e-6)
        assertEquals(2, row.array("factors").size)
        // Labelled as a forecast on the wire, so no consumer can read it as an incident.
        assertEquals("PREDICTION", row.string("kind"))
        assertTrue("a prediction must carry a disclaimer", !row.string("disclaimer").isNullOrBlank())

        // ---- and it did not become an incident or an SOS ----
        val after = overviewCounts()
        println("[e2e] overview counts after a HIGH forecast: ${Json.write(after)}")
        assertEquals(
            "the prediction should add exactly one assessment",
            riskBefore + 1,
            after.int("riskAssessments") ?: 0,
        )
        assertTrue("a HIGH forecast should count as elevated risk", (after.int("riskElevated") ?: 0) >= 1)
        assertEquals(
            "a HIGH forecast must never create an SOS",
            sosBefore,
            after.int("sosTotal") ?: 0,
        )
    }

    @Test
    fun `a tourist can set a PIN and then sign in with it instead of the password`() = runBlocking {
        assumeTrue("Set -Dtsm.e2e.baseUrl=http://host:port to run the live test", baseUrl.isNotEmpty())
        val tourist = credentials()["tourist"]!!
        val client = client()

        val login = client.login(AuthorityRoles.TOURIST, tourist.first, tourist.second, "e2e-pin-setup")
        assertTrue("tourist login failed: $login", login is AuthorityCall.Ok)
        val token = (login as AuthorityCall.Ok).value.token

        val newPin = "4821"
        val set = client.setPin(token, newPin)
        assertTrue("setting the PIN failed: $set", set is AuthorityCall.Ok)

        val pinLogin = client.loginWithPin(AuthorityRoles.TOURIST, tourist.first, newPin, "e2e-pin-login")
        assertTrue("PIN sign-in failed: $pinLogin", pinLogin is AuthorityCall.Ok)
        assertEquals(tourist.first, (pinLogin as AuthorityCall.Ok).value.subjectId)
        println("[e2e] signed in with the PIN as ${pinLogin.value.subjectId}")

        val wrong = client.loginWithPin(AuthorityRoles.TOURIST, tourist.first, "000000", "e2e-pin-wrong")
        assertTrue("a wrong PIN must be an HTTP error, not a success: $wrong", wrong is AuthorityCall.HttpError)
        assertEquals(401, (wrong as AuthorityCall.HttpError).status)
    }

    /**
     * The self-service journey, end to end: register → receive the minted Tourist ID → sign in with it
     * and the chosen PIN → appear on the officer's dashboard → gain a position when the phone reports.
     *
     * Every step uses the production client, and the dashboard row is read back from the officer's own
     * authenticated endpoint rather than inferred from the registration response.
     */
    @Test
    fun `a self-registered tourist appears on the authority dashboard and then reports a position`() = runBlocking {
        assumeTrue("Set -Dtsm.e2e.baseUrl=http://host:port to run the live test", baseUrl.isNotEmpty())
        val accounts = credentials()
        val officer = accounts["authority"]
        assertTrue("the credentials file has no authority account", officer != null)

        val http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 5_000, readTimeoutMillis = 15_000)
        val client = AuthorityClient(baseUrl = { baseUrl }, http = http)

        // ---- 1. register exactly as the app does, from a phone that holds no credential ------------
        val suffix = System.currentTimeMillis()
        val pin = "4821"
        val draft = RegistrationDraft(
            displayName = "E2E Self Registered $suffix",
            phone = "+91 8$suffix",
            pin = pin,
            contacts = listOf(
                com.touristsafety.app.domain.model.EmergencyContact(
                    name = "Asha (e2e)",
                    phone = "112",
                    relationship = "Sister",
                ),
            ),
        )
        val registration = client.register(draft, "e2e-self-registration")
        assertTrue("registration failed: $registration", registration is AuthorityCall.Ok)
        val touristId = (registration as AuthorityCall.Ok).value.touristId
        println("[e2e] registered $touristId for ${draft.phone}")
        assertTrue(
            "the id should look like TR-2026-00124, was $touristId",
            Regex("^TR-\\d{4}-\\d{5}$").matches(touristId),
        )

        // ---- 2. the server-minted id signs the tourist in with the PIN they chose ---------------
        val login = client.loginWithPin(AuthorityRoles.TOURIST, touristId, pin, "e2e-self-registration")
        assertTrue("sign-in with the new id + PIN failed: $login", login is AuthorityCall.Ok)
        val touristToken = (login as AuthorityCall.Ok).value.token
        assertEquals(touristId, login.value.subjectId)
        println("[e2e] signed in as the freshly-registered $touristId")

        // ---- 3. the officer's API shows it, with a registration date and no position yet --------
        val officerLogin = client.login(AuthorityRoles.AUTHORITY, officer!!.first, officer.second, "e2e-officer-registration")
        assertTrue("authority login failed: $officerLogin", officerLogin is AuthorityCall.Ok)
        val officerToken = (officerLogin as AuthorityCall.Ok).value.token

        suspend fun authorityTourist(): com.touristsafety.app.authority.JsonValue {
            val response = http.execute(
                AuthorityHttpRequest(
                    method = "GET",
                    url = AuthorityUrls.resolve(baseUrl, "/api/v1/authority/tourists"),
                    bearerToken = officerToken,
                ),
            )
            assertEquals(200, response.status)
            val ours = Json.parse(response.body).array("tourists")
                .filter { it.string("touristId") == touristId }
            assertEquals("the new registration must appear exactly once", 1, ours.size)
            return ours.single()
        }

        val registeredRow = authorityTourist()
        println("[e2e] dashboard row after registration: ${Json.write(registeredRow)}")
        assertEquals(draft.displayName, registeredRow.string("displayName"))
        assertEquals("SELF", registeredRow.string("registrationSource"))
        assertEquals("REGISTERED", registeredRow.string("registrationStatus"))
        assertTrue(
            "a registration date must be present",
            (registeredRow.long("registeredAtMillis") ?: 0L) > 0L,
        )
        // Registration and tracking are separate facts: registered, but nothing has been reported yet.
        assertEquals("AWAITING_FIRST_REPORT", registeredRow.string("trackingStatus"))
        assertEquals(false, registeredRow.boolean("hasPosition"))
        assertTrue(
            "with no report there must be no position, not a fabricated 0,0",
            registeredRow.objectOrNull("latestPosition") == null,
        )

        // ---- 4. once the phone reports a position the row gains it -------------------------------
        val sessions = com.touristsafety.app.testing.InMemoryAuthoritySessionStore(
            AuthoritySession(
                baseUrl = baseUrl,
                token = touristToken,
                role = AuthorityRoles.TOURIST,
                subjectId = touristId,
                expiresAtMillis = login.value.expiresAtMillis,
            ),
        )
        val at = System.currentTimeMillis()
        val reading = com.touristsafety.app.domain.model.GeoReading(
            point = com.touristsafety.app.domain.model.GeoPoint(18.5204, 73.8567),
            accuracyMeters = 6f,
            timestampMillis = at,
            source = com.touristsafety.app.domain.model.LocationSource.GPS,
        )
        val dedupeKey = OutboxPayloads.positionDedupeKey(at)
        assertTrue(
            outbox.enqueue(
                OutboxEvent(
                    dedupeKey = dedupeKey,
                    kind = OutboxKind.POSITION,
                    payload = OutboxPayloads.position(reading, at),
                    createdAtMillis = at,
                    origin = OutboxOrigin.GPS,
                ),
            ),
        )
        val transport = AuthoritySyncTransport(
            sessions = sessions,
            client = AuthorityClient(baseUrl = { sessions.current().baseUrl }, http = http),
        )
        assertEquals("the position report should be delivered", 1, OutboxSyncer(outbox, transport).flush().delivered)

        val reportingRow = authorityTourist()
        println("[e2e] dashboard row after a real report: ${Json.write(reportingRow)}")
        assertEquals("OK", reportingRow.string("trackingStatus"))
        assertEquals(true, reportingRow.boolean("hasPosition"))
        val position = reportingRow.objectOrNull("latestPosition")
        assertTrue("the dashboard row must now carry a position", position != null)
        assertEquals(18.5204, position!!.double("latitude")!!, 1e-6)
        assertEquals(73.8567, position.double("longitude")!!, 1e-6)
        assertEquals("a real-GPS report must not be flagged simulated", false, position.boolean("simulated"))
    }

    /**
     * The in-app officer console, through the very method the Android screen calls.
     *
     * Two claims, and both have to come from the server to mean anything: an officer token reads the
     * register, and a **tourist** token presented to the same route is refused. The second is what makes
     * the role real — hiding a screen in the app would prove nothing at all, because the request is
     * what either succeeds or does not.
     */
    @Test
    fun `the officer console reads the register and a tourist token is refused it`() = runBlocking {
        assumeTrue("Set -Dtsm.e2e.baseUrl=http://host:port to run the live test", baseUrl.isNotEmpty())
        val accounts = credentials()
        val officer = accounts["authority"]
        val tourist = accounts["tourist"]
        assertTrue("the credentials file has no authority account", officer != null)
        assertTrue("the credentials file has no tourist account", tourist != null)

        val http = HttpUrlConnectionAuthorityHttp(connectTimeoutMillis = 5_000, readTimeoutMillis = 15_000)
        val client = AuthorityClient(baseUrl = { baseUrl }, http = http)

        // ---- the officer signs in and reads the register through the app's own method -------------
        val officerLogin = client.login(AuthorityRoles.AUTHORITY, officer!!.first, officer.second, "e2e-console")
        assertTrue("authority login failed: $officerLogin", officerLogin is AuthorityCall.Ok)
        val officerToken = (officerLogin as AuthorityCall.Ok).value.token

        val register = client.authorityTourists(officerToken)
        assertTrue("reading the register failed: $register", register is AuthorityCall.Ok)
        val rows = (register as AuthorityCall.Ok).value
        println("[e2e] officer console read ${rows.size} account(s)")
        assertTrue("the register should hold at least the seeded tourist", rows.isNotEmpty())
        assertTrue(
            "every row must carry an id and both status fields, because the console labels all three",
            rows.all {
                it.touristId.isNotBlank() &&
                    it.registrationStatus.isNotBlank() &&
                    it.trackingStatus.isNotBlank()
            },
        )
        assertTrue(
            "a row with no position must say so rather than carrying a fabricated 0,0",
            rows.none { !it.hasPosition && it.latitude != null },
        )

        // ---- the same route, with a tourist's token ----------------------------------------------
        val touristLogin =
            client.login(AuthorityRoles.TOURIST, tourist!!.first, tourist.second, "e2e-console-tourist")
        assertTrue("tourist login failed: $touristLogin", touristLogin is AuthorityCall.Ok)
        val touristToken = (touristLogin as AuthorityCall.Ok).value.token

        val refused = client.authorityTourists(touristToken)
        assertTrue(
            "a tourist token must not be able to read the officer register, was $refused",
            refused is AuthorityCall.HttpError,
        )
        assertEquals(403, (refused as AuthorityCall.HttpError).status)
        println("[e2e] a tourist token was refused the officer register with HTTP 403")
    }
}
