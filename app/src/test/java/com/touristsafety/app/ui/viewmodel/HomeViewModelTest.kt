package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityLoginResult
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.FakeBackgroundTrackingControl
import com.touristsafety.app.testing.FakeLocationSource
import com.touristsafety.app.testing.FakeSettingsRepository
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import com.touristsafety.app.testing.MainDispatcherRule
import com.touristsafety.app.testing.RecordingAlertNotifier
import com.touristsafety.app.testing.awaitUntil
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.ownedOutbox
import com.touristsafety.app.tracking.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signing the tourist out on the safety dashboard.
 *
 * The dashboard used to drop the saved login and navigate away while the GPS subscription, the
 * foreground service and the accelerometer watch all kept running — a phone that had been signed out
 * of went on recording and queueing the tourist's position. These tests pin the whole shutdown: each
 * of the three collection paths is proved *stopped*, and the two things sign-out must **not** do —
 * lose a queued event, or evict an officer — are proved too.
 *
 * Everything downstream of the fake location source is production code, and the outbox assertions run
 * against a real in-memory SQLite database, so "nothing was lost" is a measurement rather than a claim.
 */
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var database: AppDatabase
    private lateinit var geoFenceRepository: GeoFenceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var alertRepository: AlertRepository
    private lateinit var outboxRepository: OutboxRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var locationSource: FakeLocationSource
    private lateinit var trackingController: TrackingController
    private lateinit var backgroundControl: FakeBackgroundTrackingControl
    private lateinit var sessions: InMemoryAuthoritySessionStore
    private lateinit var http: FakeAuthorityHttp
    private lateinit var client: AuthorityClient

    /** Set by the ViewModel's `stopMovementWatch` hook, so "the watch was released" is observable. */
    private var movementWatchStops: Int = 0

    private val now: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        geoFenceRepository = GeoFenceRepository(database.geoFenceDao())
        locationRepository = LocationRepository(database.locationRecordDao())
        alertRepository = AlertRepository(database.geoFenceAlertDao())
        outboxRepository = ownedOutbox(database.outboxEventDao())
        settingsRepository = FakeSettingsRepository()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        locationSource = FakeLocationSource()
        trackingController = TrackingController(
            locationProvider = locationSource,
            geoFenceRepository = geoFenceRepository,
            locationRepository = locationRepository,
            alertRepository = alertRepository,
            alertNotifier = RecordingAlertNotifier(),
            settingsRepository = settingsRepository,
            scope = scope,
        )
        backgroundControl = FakeBackgroundTrackingControl()

        http = FakeAuthorityHttp()
        sessions = InMemoryAuthoritySessionStore()
        client = AuthorityClient(baseUrl = { sessions.current().baseUrl }, http = http)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    /** A dashboard whose tourist is signed in to a configured authority. */
    private fun signedInViewModel(): HomeViewModel = runBlocking {
        sessions.saveLogin(
            BASE_URL,
            AuthorityLoginResult(
                token = "tourist-token",
                role = "tourist",
                subjectId = "T-1001",
                displayName = "Test Tourist",
                expiresAtMillis = now + 30L * 24 * 60 * 60 * 1000,
                serverTimeMillis = now,
            ),
        )
        viewModel()
    }

    private fun viewModel(): HomeViewModel = HomeViewModel(
        trackingController = trackingController,
        backgroundControl = backgroundControl,
        geoFenceRepository = geoFenceRepository,
        alertRepository = alertRepository,
        locationRepository = locationRepository,
        settingsRepository = settingsRepository,
        authoritySessionStore = sessions,
        authorityClient = client,
        outboxRepository = outboxRepository,
        stopMovementWatch = { movementWatchStops += 1 },
    )

    private fun enqueue(key: String, kind: OutboxKind) = runBlocking {
        outboxRepository.enqueue(
            OutboxEvent(dedupeKey = key, kind = kind, payload = "{}", createdAtMillis = 1L),
        )
    }

    @Test
    fun `signing out stops the GPS subscription and leaves no stale tracking state`() = runBlocking {
        val viewModel = signedInViewModel()
        trackingController.start(backgroundTracking = true)
        assertEquals(1, locationSource.currentSubscriptions)

        var navigated = false
        viewModel.signOut(onComplete = { navigated = true })

        val snapshot = trackingController.snapshot.value
        assertFalse("the pipeline must not still be tracking", snapshot.isTracking)
        assertFalse(
            "a stale request would silently restart tracking on the next resume",
            snapshot.trackingRequested,
        )
        assertFalse("no service implies no background tracking flag", snapshot.backgroundTracking)
        awaitUntil(message = "the GPS callback was never released") {
            locationSource.currentSubscriptions == 0
        }
        assertTrue("the caller must be told to navigate away", navigated)
    }

    @Test
    fun `signing out releases the movement watch`() {
        val viewModel = signedInViewModel()

        viewModel.signOut()

        assertEquals("the accelerometer watch must be released, not left running", 1, movementWatchStops)
    }

    @Test
    fun `signing out takes down the foreground service it started`() = runBlocking {
        val viewModel = signedInViewModel()
        trackingController.start(backgroundTracking = true)
        backgroundControl.start()
        trackingController.setForegroundServiceRunning(true)

        viewModel.signOut()

        assertEquals("the running service must be stopped", 1, backgroundControl.stopCalls)
    }

    @Test
    fun `signing out never starts a service in order to stop one`() {
        val viewModel = signedInViewModel()

        viewModel.signOut()

        assertEquals(
            "a phone that never ran background tracking must not have one spawned at sign-out",
            0,
            backgroundControl.startCalls,
        )
        assertEquals(
            "and no stop intent should be sent to a service that was never up",
            0,
            backgroundControl.stopCalls,
        )
    }

    @Test
    fun `the login is cleared even when the authority cannot be reached`() {
        val viewModel = signedInViewModel()
        http.failure = java.io.IOException("connection refused")

        var navigated = false
        viewModel.signOut(onComplete = { navigated = true })

        val session = runBlocking { sessions.current() }
        assertNull("a tourist must be able to leave a session whose server is down", session.token)
        assertTrue("the caller must still be told to navigate away", navigated)
        assertTrue(
            "the configured address must survive, so signing back in is one field",
            session.baseUrl.isNotBlank(),
        )
    }

    @Test
    fun `an unexpected failure while revoking the token cannot leave the tourist signed in`() {
        val viewModel = signedInViewModel()
        http.respondWith { throw IllegalStateException("transport exploded") }

        var navigated = false
        viewModel.signOut(onComplete = { navigated = true })

        assertNull(runBlocking { sessions.current() }.token)
        assertTrue("local sign-out must not depend on the server's answer", navigated)
    }

    @Test
    fun `a queued SOS and risk summary survive the sign-out untouched`() = runBlocking {
        val viewModel = signedInViewModel()
        enqueue("sos:1", OutboxKind.SOS)
        enqueue("alert:2:ENTRY:100", OutboxKind.ZONE_ALERT)
        enqueue("position:100", OutboxKind.POSITION)
        enqueue("risk:100", OutboxKind.RISK)
        assertEquals(4, outboxRepository.pending().size)

        viewModel.signOut()

        val pending = outboxRepository.pending()
        assertEquals(
            "sign-out must preserve every queued event for the next sign-in",
            4,
            pending.size,
        )
        assertEquals(
            listOf("sos:1", "alert:2:ENTRY:100", "position:100", "risk:100"),
            pending.map { it.dedupeKey },
        )
    }

    @Test
    fun `the officer session is not evicted by a tourist signing out`() = runBlocking {
        sessions.saveOfficerLogin(
            BASE_URL,
            AuthorityLoginResult(
                token = "officer-token",
                role = "authority",
                subjectId = "A-01",
                displayName = "Officer One",
                expiresAtMillis = now + 30L * 24 * 60 * 60 * 1000,
                serverTimeMillis = now,
            ),
        )
        val viewModel = signedInViewModel()

        viewModel.signOut()

        assertNull("the tourist's token must be gone", sessions.current().token)
        val officer = sessions.currentOfficer()
        assertEquals("the officer's token must be untouched", "officer-token", officer.token)
        assertTrue(officer.isSignedIn(now))
    }

    private companion object {
        const val BASE_URL = "http://203.0.113.10:8080"
    }
}
