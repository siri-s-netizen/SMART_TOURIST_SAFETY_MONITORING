package com.touristsafety.app.tracking

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.FakeLocationSource
import com.touristsafety.app.testing.FakeSettingsRepository
import com.touristsafety.app.testing.RecordingAlertNotifier
import com.touristsafety.app.testing.TestGeo
import com.touristsafety.app.testing.awaitUntil
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.testReading
import com.touristsafety.app.testing.testZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage of the *real GPS* pipeline.
 *
 * The location source is a scripted fake, but everything downstream is production code: the real
 * [GeoFenceEngine], the real ray-casting containment service and a real Room database. That is what
 * makes these tests able to prove the two hardest requirements — "exactly one alert per crossing"
 * and "a simulated coordinate can never masquerade as a GPS fix".
 */
@RunWith(AndroidJUnit4::class)
class TrackingPipelineTest {

    private lateinit var database: AppDatabase
    private lateinit var geoFenceRepository: GeoFenceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var alertRepository: AlertRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var notifier: RecordingAlertNotifier
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        geoFenceRepository = GeoFenceRepository(database.geoFenceDao())
        locationRepository = LocationRepository(database.locationRecordDao())
        alertRepository = AlertRepository(database.geoFenceAlertDao())
        settingsRepository = FakeSettingsRepository()
        notifier = RecordingAlertNotifier()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun controller(source: FakeLocationSource) = TrackingController(
        locationProvider = source,
        geoFenceRepository = geoFenceRepository,
        locationRepository = locationRepository,
        alertRepository = alertRepository,
        alertNotifier = notifier,
        settingsRepository = settingsRepository,
        scope = scope,
    )

    // ------------------------------------------------------------ crossing semantics

    @Test
    fun `outside to inside a restricted zone raises exactly one ENTRY and persists it`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.onReading(testReading(TestGeo.outsideSouth))
        assertEquals(0, notifier.alerts.size)

        controller.onReading(testReading(TestGeo.squareCenter))

        assertEquals(1, notifier.alerts.size)
        assertEquals(AlertEventType.ENTRY, notifier.alerts.first().eventType)
        assertEquals(ZoneType.RESTRICTED, notifier.alerts.first().zoneType)
        assertEquals(1, alertRepository.observeCount().first())
        // Both fed fixes are persisted: the outside reading and the inside one.
        assertEquals(2, locationRepository.observeCount().first())
    }

    @Test
    fun `staying inside a zone never repeats the alert`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.onReading(testReading(TestGeo.outsideSouth))
        repeat(6) { index ->
            controller.onReading(
                testReading(TestGeo.squareCenter, timestampMillis = 1_000L * (index + 1)),
            )
        }

        assertEquals("an extended stay must not alert repeatedly", 1, notifier.alerts.size)
        assertTrue(controller.snapshot.value.zoneStatuses.single().inside)
    }

    @Test
    fun `leaving a zone raises an EXIT that is recorded but is not a warning`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.onReading(testReading(TestGeo.outsideSouth))
        controller.onReading(testReading(TestGeo.squareCenter))
        controller.onReading(testReading(TestGeo.outsideSouth))

        assertEquals(2, notifier.alerts.size)
        val exit = notifier.alerts.last()
        assertEquals(AlertEventType.EXIT, exit.eventType)
        assertFalse("an exit is informational", exit.isWarning)
        assertEquals(1, notifier.warningCount)
        assertEquals(SafetyStatus.SAFE, controller.snapshot.value.overallStatus)
    }

    @Test
    fun `a zone created mid-session is honoured on the very next fix`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }

        controller.onReading(testReading(TestGeo.squareCenter))
        assertEquals("no zones yet, so nothing can alert", 0, notifier.alerts.size)

        geoFenceRepository.create(testZone(id = 0, type = ZoneType.DANGEROUS))
        controller.onReading(testReading(TestGeo.squareCenter))

        assertEquals(1, notifier.alerts.size)
        assertEquals(SafetyStatus.INSIDE_DANGEROUS, controller.snapshot.value.overallStatus)
    }

    // ------------------------------------------------------------ accuracy handling

    @Test
    fun `a poor accuracy fix does not fabricate an alert`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.onReading(testReading(TestGeo.outsideSouth))
        // 250 m of accuracy is worse than the 100 m ceiling, so the engine must refuse to decide.
        controller.onReading(testReading(TestGeo.squareCenter, accuracyMeters = 250f))

        assertEquals(0, notifier.alerts.size)
        assertEquals(SafetyStatus.UNCERTAIN, controller.snapshot.value.overallStatus)
        assertTrue(controller.snapshot.value.accuracyIsPoor)
    }

    @Test
    fun `an uncertain fix inside an entered zone does not fabricate an EXIT`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.onReading(testReading(TestGeo.outsideSouth))
        controller.onReading(testReading(TestGeo.squareCenter))
        controller.onReading(testReading(TestGeo.outsideSouth, accuracyMeters = 400f))

        assertEquals("only the genuine entry should have alerted", 1, notifier.alerts.size)
        assertTrue(controller.snapshot.value.zoneStatuses.single().inside)
    }

    // ------------------------------------------------------------ source integrity

    @Test
    fun `the gps pipeline only ever writes GPS rows`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.onReading(testReading(TestGeo.outsideSouth))
        controller.onReading(testReading(TestGeo.squareCenter))

        val records = locationRepository.observeRecent(10).first()
        assertTrue(records.isNotEmpty())
        assertTrue(
            "no row produced by real tracking may be tagged as simulation",
            records.all { it.source == LocationSource.GPS },
        )
        assertTrue(alertRepository.observeHistory().first().all { it.source == LocationSource.GPS })
    }

    @Test
    fun `notifications can be switched off without losing the recorded alert`() = runBlocking {
        settingsRepository.setNotificationsEnabled(false)
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.onReading(testReading(TestGeo.outsideSouth))
        controller.onReading(testReading(TestGeo.squareCenter))

        assertEquals("the OS must not be asked to notify", 0, notifier.alerts.size)
        assertEquals("but the crossing is still recorded", 1, alertRepository.observeCount().first())
    }

    // ------------------------------------------------------------ subscription lifecycle

    @Test
    fun `starting twice opens exactly one location subscription`() = runBlocking {
        val source = FakeLocationSource()
        val controller = controller(source).also { it.observeSettings() }

        controller.start()
        assertEquals(1, source.subscriptionCount)

        controller.start()
        controller.start()

        assertEquals("Start is idempotent", 1, source.subscriptionCount)
        assertTrue(controller.isTracking())
    }

    @Test
    fun `stopping tears the subscription down`() = runBlocking {
        val source = FakeLocationSource()
        val controller = controller(source).also { it.observeSettings() }

        controller.start()
        assertTrue(controller.isTracking())
        assertEquals(1, source.currentSubscriptions)

        controller.stop()

        assertFalse(controller.isTracking())
        awaitUntil(message = "the location callback was never removed") {
            source.currentSubscriptions == 0
        }
    }

    @Test
    fun `a reading delivered through the subscription reaches the database and the ui`() = runBlocking {
        val source = FakeLocationSource()
        val controller = controller(source).also { it.observeSettings() }
        geoFenceRepository.create(testZone(id = 0, type = ZoneType.RESTRICTED))

        controller.start()
        source.emit(testReading(TestGeo.squareCenter, timestampMillis = 5_000L))

        awaitUntil(message = "the reading never reached the pipeline") {
            controller.snapshot.value.readingCount >= 1
        }
        assertEquals(SafetyStatus.INSIDE_RESTRICTED, controller.snapshot.value.overallStatus)
        assertEquals(1, notifier.alerts.size)
    }

    @Test
    fun `tracking refuses to start when location services are off`() = runBlocking {
        val source = FakeLocationSource(locationServiceEnabled = false)
        val controller = controller(source).also { it.observeSettings() }

        controller.start()

        assertFalse(controller.isTracking())
        assertEquals(TrackingProblem.LOCATION_DISABLED, controller.snapshot.value.problem)
        assertEquals("nothing may be subscribed", 0, source.subscriptionCount)
    }

    @Test
    fun `overlapping zones each alert independently`() = runBlocking {
        val controller = controller(FakeLocationSource()).also { it.observeSettings() }
        geoFenceRepository.create(
            testZone(id = 0, type = ZoneType.RESTRICTED, vertices = TestGeo.squareVertices),
        )
        geoFenceRepository.create(
            testZone(
                id = 0,
                type = ZoneType.DANGEROUS,
                vertices = TestGeo.overlappingSquare(north = 50.0, east = 50.0),
            ),
        )

        controller.onReading(testReading(TestGeo.outsideSouth))
        controller.onReading(testReading(TestGeo.squareCenter))

        assertEquals(2, notifier.alerts.size)
        assertEquals(SafetyStatus.INSIDE_DANGEROUS, controller.snapshot.value.overallStatus)
    }
}
